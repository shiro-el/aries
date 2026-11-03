//! `aries-beacon call` pushes the Call to the paired phone through a fake
//! Relay, as the push policy says, after it is recorded. Calls run inside
//! Terminals of an isolated herdr-core so the caller is confirmed.

use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::calls::{Call, CallsLine, Urgency};
use aries_protocol::push::{KeyPair, PairReply, PairRequest, PublicKey, PushMessage, open};
use aries_test_support::{FakeRelay, HcSession, http_request};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

/// A Host: an isolated herdr-core, a paired Beacon and the phone's keys.
struct Host {
    hc: HcSession,
    state: tempfile::TempDir,
    relay: FakeRelay,
    phone: KeyPair,
    beacon_key: PublicKey,
}

impl Host {
    fn new() -> Self {
        let mut host = Self {
            hc: HcSession::start(),
            state: tempfile::tempdir().unwrap(),
            relay: FakeRelay::start(),
            phone: KeyPair::generate(),
            beacon_key: KeyPair::generate().public,
        };
        host.pair();
        host
    }

    fn path_var(&self) -> String {
        let bin_dir = self.hc.bin().parent().unwrap();
        format!("{}:/usr/bin:/bin", bin_dir.display())
    }

    /// The Beacon run outside any Terminal (like the phone over SSH).
    fn beacon(&self, arg: &str) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.arg(arg).env_clear();
        cmd.envs(self.hc.env());
        cmd.env("XDG_STATE_HOME", self.state.path());
        cmd.env("HOME", self.state.path());
        cmd.env("PATH", self.path_var());
        cmd
    }

    fn pair(&mut self) {
        let body = serde_json::json!({"platform": "fcm", "device_token": "dev"}).to_string();
        let (status, body) = http_request(
            "POST",
            &format!("{}/v1/pairings", self.relay.url()),
            &[("Content-Type".into(), "application/json".into())],
            body.as_bytes(),
        )
        .unwrap();
        assert_eq!(status, 201);
        let token: serde_json::Value = serde_json::from_slice(&body).unwrap();
        let req = PairRequest {
            relay_url: self.relay.url().into(),
            token: token["token"].as_str().unwrap().into(),
            phone_key: self.phone.public.to_text(),
        };
        let mut child = self
            .beacon("pair")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let mut stdin = child.stdin.take().unwrap();
        stdin
            .write_all(serde_json::to_string(&req).unwrap().as_bytes())
            .unwrap();
        drop(stdin);
        let out = child.wait_with_output().unwrap();
        assert!(out.status.success(), "pair: {out:?}");
        let reply: PairReply = serde_json::from_slice(&out.stdout).unwrap();
        self.beacon_key = reply.beacon_key.parse().unwrap();
    }

    fn set_policy(&self, json: &str) {
        let dir = self.state.path().join("aries-beacon");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("push-policy.json"), json).unwrap();
    }

    fn script(&self, out: &Path, args: &[&str]) -> String {
        let quoted: Vec<String> = args
            .iter()
            .map(|a| format!("'{}'", a.replace('\'', r"'\''")))
            .collect();
        let out = out.display();
        format!(
            "env 'PATH={}' 'XDG_STATE_HOME={}' 'HOME={}' {BEACON} call {} > {out}.tmp 2>&1; echo \"exit $?\" >> {out}.tmp; mv {out}.tmp {out}; sleep 60",
            self.path_var(),
            self.state.path().display(),
            self.state.path().display(),
            quoted.join(" "),
        )
    }

    /// Runs `aries-beacon call <args>` in a new top-level Terminal; returns
    /// what it printed (last line `exit <code>`).
    fn call(&self, name: &str, args: &[&str]) -> String {
        let out = self.state.path().join(format!("{name}.out"));
        self.hc
            .run_bg(name, &["sh", "-c", &self.script(&out, args)]);
        wait_file(&out)
    }

    /// Like [`Host::call`], in a child Terminal of a new coordinator.
    fn child_call(&self, name: &str, args: &[&str]) -> String {
        let parent = self.hc.run_bg(&format!("{name}-parent"), &["sleep", "60"]);
        let out = self.state.path().join(format!("{name}.out"));
        let script = self.script(&out, args);
        self.hc.hc(&[
            "run",
            "--bg",
            "--json",
            "--no-notify",
            "--parent",
            &parent,
            "--name",
            name,
            "--",
            "sh",
            "-c",
            &script,
        ]);
        wait_file(&out)
    }

    fn recorded(&self) -> Vec<Call> {
        let out = self.beacon("calls").stdin(Stdio::null()).output().unwrap();
        assert!(out.status.success(), "calls failed: {out:?}");
        String::from_utf8(out.stdout)
            .unwrap()
            .lines()
            .filter_map(|l| match serde_json::from_str(l).unwrap() {
                CallsLine::Call { call, .. } => Some(call),
                _ => None,
            })
            .collect()
    }

    /// Every push the Relay got, opened as the phone does. `call` returns
    /// only after its push, so nothing more is on its way.
    fn pushes(&self) -> Vec<PushMessage> {
        self.relay
            .log()
            .pushes
            .iter()
            .map(|(_, envelope)| open(envelope, &self.phone, &self.beacon_key).unwrap())
            .collect()
    }
}

fn wait_file(path: &Path) -> String {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        if let Ok(s) = std::fs::read_to_string(path) {
            return s;
        }
        assert!(
            Instant::now() < deadline,
            "{} never appeared",
            path.display()
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn printed_call(out: &str) -> Call {
    let line = out
        .lines()
        .rfind(|l| l.starts_with('{'))
        .unwrap_or_else(|| panic!("no Call printed: {out:?}"));
    serde_json::from_str(line).unwrap()
}

#[test]
fn a_top_level_call_reaches_the_phone_with_everything_to_answer_it() {
    let host = Host::new();
    let out = host.call(
        "agent",
        &[
            "--title",
            "Review ready",
            "--body",
            "Tests pass. Merge?",
            "--urgency",
            "urgent",
            "--choice",
            "Merge",
            "--choice",
            "Wait",
        ],
    );
    assert!(out.ends_with("exit 0\n"), "{out}");
    let call = printed_call(&out);
    assert_eq!(
        host.pushes(),
        [PushMessage::Call {
            id: call.id.clone(),
            terminal: call.terminal.clone().unwrap(),
            top_level: true,
            title: "Review ready".into(),
            body: "Tests pass. Merge?".into(),
            urgency: Urgency::Urgent,
            choices: vec!["Merge".into(), "Wait".into()],
            at_ms: call.at_ms,
        }]
    );
}

#[test]
fn record_urgency_and_unconfirmed_calls_are_only_recorded() {
    let host = Host::new();
    let out = host.call("agent", &["--title", "FYI", "--urgency", "record"]);
    assert!(out.ends_with("exit 0\n"), "{out}");
    let outside = host
        .beacon("call")
        .args(["--title", "From cron", "--urgency", "emergency"])
        .stdin(Stdio::null())
        .output()
        .unwrap();
    assert!(outside.status.success(), "{outside:?}");

    assert_eq!(host.recorded().len(), 2);
    assert_eq!(host.pushes(), []);
}

#[test]
fn subagent_calls_follow_the_child_agents_option() {
    let host = Host::new();
    let out = host.child_call("helper", &["--title", "Helper asks"]);
    assert!(out.ends_with("exit 0\n"), "{out}");
    assert_eq!(host.recorded().len(), 1);
    assert_eq!(host.pushes(), []);

    host.set_policy(r#"{"child_agents": true}"#);
    host.child_call("helper2", &["--title", "Helper asks again"]);
    match host.pushes().as_slice() {
        [
            PushMessage::Call {
                title, top_level, ..
            },
        ] => {
            assert_eq!(title, "Helper asks again");
            assert!(!top_level);
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn a_relay_failure_keeps_the_call_recorded() {
    let host = Host::new();
    host.relay.fail_pushes(429);
    let out = host.call(
        "agent",
        &["--title", "Still here", "--urgency", "emergency"],
    );
    assert!(out.ends_with("exit 0\n"), "{out}");
    assert!(out.contains("push failed"), "{out}");
    let call = printed_call(&out);
    assert_eq!(host.recorded(), [call]);
    assert!(host.relay.log().pushes.is_empty());
}

#[test]
fn a_long_body_is_cut_to_fit_the_envelope() {
    let host = Host::new();
    let body = "\u{1}x".repeat(1999);
    let out = host.call("agent", &["--title", "Long", "--body", &body]);
    assert!(out.ends_with("exit 0\n"), "{out}");
    assert_eq!(printed_call(&out).body, body, "the record keeps it all");
    match host.pushes().as_slice() {
        [PushMessage::Call { body: pushed, .. }] => {
            assert!(pushed.len() < body.len());
            assert!(pushed.ends_with('…'));
        }
        other => panic!("{other:?}"),
    }
}
