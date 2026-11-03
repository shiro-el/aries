//! Answering a Call through the core API: a fake Agent in a real herdr-core
//! Terminal sends a Call with the real `aries-beacon call`; the core runs the
//! real `aries-beacon reply` and the Choice lands in the Agent's input.

mod common;

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use aries_core::{AriesCore, CallAnswerState, CallInfo, CallReplyOutcome, CoreError, Endpoint};
use aries_test_support::{FakeAgent, HcSession, beacon_bin};
use common::{core_in, local_endpoint};

struct Setup {
    hc: HcSession,
    core: Arc<AriesCore>,
    host: String,
    _data: tempfile::TempDir,
}

fn setup_with_beacon(beacon: &str) -> Setup {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let Endpoint::Local { argv, mut env } = local_endpoint(&hc) else {
        unreachable!()
    };
    env.insert("ARIES_BEACON".into(), beacon.into());
    let host = core
        .add_host("dev".into(), Endpoint::Local { argv, env })
        .unwrap()
        .id;
    Setup {
        hc,
        core,
        host,
        _data: data,
    }
}

fn setup() -> Setup {
    setup_with_beacon(&beacon_bin().to_string_lossy())
}

impl Setup {
    fn env_var(&self, key: &str) -> String {
        let env: HashMap<_, _> = self.hc.env().into_iter().collect();
        env[key].clone()
    }

    /// A fake Agent that sends a Call, then waits for one input line.
    async fn agent_with_call(&self, choices: &[&str]) -> (FakeAgent, CallInfo) {
        let bin_dir = self.hc.bin().parent().unwrap().display().to_string();
        let choices: String = choices.iter().map(|c| format!(" --choice '{c}'")).collect();
        let script = format!(
            "status working\n\
             run env 'PATH={bin_dir}:/usr/bin:/bin' 'XDG_STATE_HOME={}' {} call --title 'Merge?'{choices} >/dev/null 2>&1\n\
             status idle\n\
             wait\n\
             status working\n",
            self.env_var("XDG_STATE_HOME"),
            beacon_bin().display(),
        );
        let agent = self.hc.run_fake_agent("agent", &script);
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let calls = self.core.calls(self.host.clone()).await.unwrap();
            if let Some(c) = calls
                .into_iter()
                .find(|c| c.terminal.as_deref() == Some(agent.address.as_str()))
            {
                return (agent, c);
            }
            assert!(Instant::now() < deadline, "the Call never arrived");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    async fn call(&self, id: &str) -> CallInfo {
        let calls = self.core.calls(self.host.clone()).await.unwrap();
        calls.into_iter().find(|c| c.id == id).unwrap()
    }
}

async fn inputs_after(agent: &FakeAgent, want: usize) -> Vec<String> {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let got = agent.inputs();
        if got.len() >= want {
            return got;
        }
        assert!(Instant::now() < deadline, "no input: {got:?}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

#[tokio::test]
async fn answering_a_call_types_the_choice_once_and_records_it() {
    let s = setup();
    let (agent, call) = s.agent_with_call(&["Ship it", "Hold"]).await;
    assert_eq!(call.answer, None);

    let reply = s
        .core
        .reply_call(s.host.clone(), call.id.clone(), 0)
        .await
        .unwrap();

    assert_eq!(reply.outcome, CallReplyOutcome::Delivered);
    assert_eq!(inputs_after(&agent, 1).await, ["Ship it"]);
    let answer = s.call(&call.id).await.answer.unwrap();
    assert_eq!(answer.state, CallAnswerState::Delivered);
    assert_eq!(answer.text, "Ship it");
    assert_eq!(reply.answer, Some(answer));

    // A retry (or another phone) gets the recorded answer; nothing typed.
    let again = s
        .core
        .reply_call(s.host.clone(), call.id.clone(), 1)
        .await
        .unwrap();
    assert_eq!(again.outcome, CallReplyOutcome::Answered);
    assert_eq!(again.answer.unwrap().text, "Ship it");
    tokio::time::sleep(Duration::from_millis(800)).await;
    assert_eq!(agent.inputs(), ["Ship it"]);
}

#[tokio::test]
async fn answering_a_call_whose_terminal_is_gone_fails_and_says_so() {
    let s = setup();
    let (agent, call) = s.agent_with_call(&["Yes"]).await;
    s.hc.hc(&["kill", &agent.address]);
    tokio::time::sleep(Duration::from_millis(500)).await;

    let reply = s
        .core
        .reply_call(s.host.clone(), call.id.clone(), 0)
        .await
        .unwrap();

    assert_eq!(reply.outcome, CallReplyOutcome::Failed);
    assert!(reply.detail.is_some());
    let answer = s.call(&call.id).await.answer.unwrap();
    assert_eq!(answer.state, CallAnswerState::Failed);
    assert!(agent.inputs().is_empty());
}

#[tokio::test]
async fn a_call_reply_is_refused_after_its_agent_returns_to_a_live_shell() {
    let s = setup();
    let script = s._data.path().join("agent-script");
    std::fs::write(&script, format!(
        "status working\nrun env PATH={}:/usr/bin:/bin XDG_STATE_HOME={} {} call --title 'Question' --choice 'echo unsafe'\nwait\nexit 0\n",
        s.hc.bin().parent().unwrap().display(), s.env_var("XDG_STATE_HOME"), beacon_bin().display()
    )).unwrap();
    let terminal = s.hc.run_bg(
        "shell",
        &[
            "sh",
            "-c",
            &format!(
                "sh {} {}; echo agent-exited; exec sleep 600",
                aries_test_support::fake_agent_path().display(),
                script.display()
            ),
        ],
    );
    let call = tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if let Some(call) = s
                .core
                .calls(s.host.clone())
                .await
                .unwrap()
                .into_iter()
                .find(|c| c.terminal.as_deref() == Some(&terminal))
            {
                break call;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .unwrap();
    s.hc.hc(&["send", &terminal, "finish"]);
    tokio::time::timeout(Duration::from_secs(10), async {
        while !s.hc.hc(&["read", &terminal]).contains("agent-exited") {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .unwrap();
    let reply = s.core.reply_call(s.host.clone(), call.id, 0).await.unwrap();
    assert_eq!(reply.outcome, CallReplyOutcome::Failed);
}

#[tokio::test]
async fn a_call_reply_rejects_a_different_terminal_at_the_same_address() {
    let s = setup();
    let (agent, call) = s.agent_with_call(&["Yes"]).await;
    let fake = s._data.path().join("herdr-core");
    std::fs::write(&fake, r#"#!/bin/sh
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  case $line in
    *'"hello"'*) printf '{"id":%s,"result":{"version":"0.1.17"}}\n' "$id" ;;
    *'"terminal.get"'*) printf '{"id":%s,"result":{"state":"live","pid":1,"created_ms":1}}\n' "$id" ;;
    *'"terminal.send"'*) touch "$(dirname "$0")/sent"; printf '{"id":%s,"result":{}}\n' "$id" ;;
  esac
done
"#).unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).unwrap();
    let Endpoint::Local { argv, mut env } = local_endpoint(&s.hc) else {
        unreachable!()
    };
    env.insert("ARIES_BEACON".into(), beacon_bin().display().to_string());
    env.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", s._data.path().display()),
    );
    let host = s
        .core
        .add_host("reused".into(), Endpoint::Local { argv, env })
        .unwrap();
    let reply = s.core.reply_call(host.id, call.id, 0).await.unwrap();
    assert_eq!(reply.outcome, CallReplyOutcome::Failed);
    assert!(!s._data.path().join("sent").exists());
    assert!(agent.inputs().is_empty());
}

#[tokio::test]
async fn a_reply_that_started_but_never_reported_is_unknown() {
    // The Beacon starts, then the connection dies before it prints.
    let dir = tempfile::tempdir().unwrap();
    let beacon = dir.path().join("aries-beacon");
    std::fs::write(&beacon, "#!/bin/sh\nexit 1\n").unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&beacon, std::fs::Permissions::from_mode(0o755)).unwrap();
    let s = setup_with_beacon(&beacon.to_string_lossy());

    let reply = s
        .core
        .reply_call(s.host.clone(), "c1-x".into(), 0)
        .await
        .unwrap();

    assert_eq!(reply.outcome, CallReplyOutcome::Unknown);
    assert_eq!(reply.answer, None);
}

#[tokio::test]
async fn a_host_without_a_beacon_says_so() {
    let s = setup_with_beacon("/nonexistent/aries-beacon");
    let reply = s
        .core
        .reply_call(s.host.clone(), "c1-x".into(), 0)
        .await
        .unwrap();
    assert_eq!(reply.outcome, CallReplyOutcome::NoBeacon);
}

#[tokio::test]
async fn a_reply_that_could_not_start_is_an_error() {
    // Nothing listens on this port: the reply never reaches the Host.
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap();
    let port = {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    };
    let host = core
        .add_host(
            "gone".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port,
                user: "nobody".into(),
            },
        )
        .unwrap()
        .id;

    let err = core.reply_call(host, "c1-x".into(), 0).await.unwrap_err();

    assert!(matches!(err, CoreError::Unreachable { .. }), "{err:?}");
}

#[tokio::test]
async fn a_host_that_never_finishes_signing_in_is_not_sent_within_the_limit() {
    // It accepts the connection, then says nothing: no SSH banner, ever.
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let silent = std::thread::spawn(move || {
        let held: Vec<_> = listener.incoming().take(1).collect();
        std::thread::sleep(Duration::from_secs(5));
        drop(held);
    });
    let host = core
        .add_host(
            "silent".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port,
                user: "nobody".into(),
            },
        )
        .unwrap()
        .id;

    let started = Instant::now();
    let err = core
        .reply_call_within(host, "c1-x".into(), 0, Duration::from_secs(1))
        .await
        .unwrap_err();

    assert!(
        started.elapsed() < Duration::from_secs(4),
        "{:?}",
        started.elapsed()
    );
    assert!(matches!(err, CoreError::Unreachable { .. }), "{err:?}");
    silent.join().unwrap();
}
