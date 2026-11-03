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
