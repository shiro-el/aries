//! `aries-beacon serve` watches the local herdr-core (an isolated session)
//! and pushes Agent status changes to the paired phone through a fake Relay,
//! as the push policy says. Fake Agents report their status with OSC 7501.

use std::io::Write;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::agent_status::{AgentStatus, BlockedReason};
use aries_protocol::beacon::BeaconStatus;
use aries_protocol::push::{KeyPair, PairReply, PairRequest, PublicKey, PushMessage, open};
use aries_test_support::{FakeRelay, HcSession, fake_agent_path, http_request};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

/// A Host: an isolated herdr-core, a paired Beacon and the phone's keys.
struct Host {
    hc: HcSession,
    home: tempfile::TempDir,
    relay: FakeRelay,
    phone: KeyPair,
    beacon_key: PublicKey,
    serve: Option<Child>,
}

impl Host {
    fn new() -> Self {
        let hc = HcSession::start();
        let home = tempfile::tempdir().unwrap();
        let relay = FakeRelay::start();
        let phone = KeyPair::generate();
        let mut host = Self {
            hc,
            home,
            relay,
            phone,
            beacon_key: KeyPair::generate().public,
            serve: None,
        };
        host.pair();
        host
    }

    fn command(&self, arg: &str) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.arg(arg).env_clear();
        cmd.envs(self.hc.env());
        let bin_dir = self.hc.bin().parent().unwrap();
        cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
        cmd.env("HOME", self.home.path());
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
            .command("pair")
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

    /// Changes the policy the way the phone does: `aries-beacon policy set`.
    fn set_policy(&self, json: &str) {
        let mut child = self
            .command("policy")
            .arg("set")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        child
            .stdin
            .take()
            .unwrap()
            .write_all(json.as_bytes())
            .unwrap();
        let out = child.wait_with_output().unwrap();
        assert!(out.status.success(), "policy set: {out:?}");
    }

    /// Starts `serve` and waits until it watches the core.
    fn serve(&mut self) {
        let child = self
            .command("serve")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        self.serve = Some(child);
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            let out = self.command("status").output().unwrap();
            let s: BeaconStatus = serde_json::from_slice(&out.stdout).unwrap();
            if s.core_version.is_some() {
                return;
            }
            assert!(Instant::now() < deadline, "serve did not attach: {s:?}");
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    /// A fake Agent with `script`, a child of `parent` when given.
    fn agent(&self, name: &str, parent: Option<&str>, script: &str) -> String {
        let Some(parent) = parent else {
            return self.hc.run_fake_agent(name, script).address;
        };
        let script_path = self.home.path().join(format!("{name}.script"));
        std::fs::write(&script_path, script).unwrap();
        let agent = fake_agent_path();
        let input = format!(
            "FAKE_AGENT_INPUT={}",
            self.home.path().join(format!("{name}.input")).display()
        );
        let out = self.hc.hc(&[
            "run",
            "--bg",
            "--json",
            "--no-notify",
            "--parent",
            parent,
            "--name",
            name,
            "--",
            "env",
            &input,
            "/bin/sh",
            &agent.to_string_lossy(),
            &script_path.to_string_lossy(),
        ]);
        let v: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
        v["address"].as_str().unwrap().to_owned()
    }

    /// The first `n` pushes, opened as the phone does.
    fn pushes(&self, n: usize) -> Vec<PushMessage> {
        self.relay
            .wait_pushes(n, Duration::from_secs(15))
            .iter()
            .map(|(_, envelope)| open(envelope, &self.phone, &self.beacon_key).unwrap())
            .collect()
    }

    /// Exactly `n` pushes arrived (the last one is a marker sent after
    /// everything that must not be pushed, so nothing else is on its way).
    fn exactly(&self, n: usize) -> Vec<(String, AgentStatus)> {
        let got = self.pushes(n);
        std::thread::sleep(Duration::from_millis(300));
        let all = self.relay.log().pushes.len();
        assert_eq!(all, n, "unexpected extra pushes: {:?}", self.pushes(all));
        got.into_iter().map(status_of).collect()
    }
}

impl Drop for Host {
    fn drop(&mut self) {
        if let Some(mut c) = self.serve.take() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}

fn status_of(m: PushMessage) -> (String, AgentStatus) {
    match m {
        PushMessage::Status {
            terminal, status, ..
        } => (terminal, status),
        other => panic!("not a status push: {other:?}"),
    }
}

fn blocked(reason: BlockedReason, message: &str) -> AgentStatus {
    AgentStatus::Blocked {
        reason: Some(reason),
        message: Some(message.into()),
    }
}

fn error(message: &str) -> AgentStatus {
    AgentStatus::Error {
        message: Some(message.into()),
    }
}

#[test]
fn pushes_a_top_level_agent_that_becomes_blocked() {
    let mut host = Host::new();
    host.serve();

    let t = host.agent(
        "claude",
        None,
        "status working\nstatus blocked permission Allow Bash?\nwait\n",
    );

    let pushes = host.pushes(1);
    match &pushes[0] {
        PushMessage::Status {
            terminal,
            name,
            top_level,
            status,
            at,
            ..
        } => {
            assert_eq!(terminal, &t);
            assert_eq!(name.as_deref(), Some("claude"));
            assert!(top_level);
            assert_eq!(status, &blocked(BlockedReason::Permission, "Allow Bash?"));
            assert!(*at > 1_700_000_000);
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn pushes_blocked_once_per_reason_and_again_after_it_moved_on() {
    let mut host = Host::new();
    host.serve();

    let t = host.agent(
        "claude",
        None,
        "status blocked permission Allow Bash?\nsleep 0.2\n\
         status blocked permission Allow Edit?\nsleep 0.2\n\
         status blocked question Which one?\nsleep 0.2\n\
         status working\nsleep 0.2\n\
         status blocked question Which one?\nsleep 0.2\n\
         status error - boom\nsleep 0.2\n\
         status error - boom again\nsleep 0.2\n\
         status done\nsleep 0.2\n\
         status blocked auth Log in\nwait\n",
    );

    assert_eq!(
        host.exactly(5),
        [
            (t.clone(), blocked(BlockedReason::Permission, "Allow Bash?")),
            (t.clone(), blocked(BlockedReason::Question, "Which one?")),
            (t.clone(), blocked(BlockedReason::Question, "Which one?")),
            (t.clone(), error("boom")),
            (t.clone(), blocked(BlockedReason::Auth, "Log in")),
        ]
    );
}

#[test]
fn does_not_push_subagents_by_default() {
    let mut host = Host::new();
    host.serve();
    let parent = host.agent("coordinator", None, "status working\nwait\n");

    host.agent(
        "sub",
        Some(&parent),
        "status blocked permission Allow Bash?\nwait\n",
    );
    std::thread::sleep(Duration::from_millis(500));
    // Marker: it arrives, the subagent's Blocked never did.
    let marker = host.agent("marker", None, "status error - marker\nwait\n");

    assert_eq!(host.exactly(1), [(marker, error("marker"))]);
}

#[test]
fn the_policy_file_can_add_done_and_subagents() {
    let mut host = Host::new();
    host.set_policy(r#"{"done": true, "child_agents": true, "error": false}"#);
    host.serve();
    let parent = host.agent("coordinator", None, "status working\nwait\n");

    let child = host.agent(
        "sub",
        Some(&parent),
        "status blocked permission Allow Bash?\nsleep 0.3\nstatus error - ignored\nsleep 0.3\n\
         status working\nsleep 0.2\nstatus done\nwait\n",
    );

    let pushes = host.exactly(2);
    assert_eq!(
        pushes,
        [
            (
                child.clone(),
                blocked(BlockedReason::Permission, "Allow Bash?")
            ),
            (child, AgentStatus::Done),
        ]
    );
    match &host.pushes(1)[0] {
        PushMessage::Status { top_level, .. } => assert!(!top_level),
        other => panic!("{other:?}"),
    }
}

#[test]
fn done_is_pushed_only_for_a_turn_the_user_has_not_seen() {
    let mut host = Host::new();
    host.set_policy(r#"{"done": true}"#);
    host.serve();
    // Another client (the desktop app) already marked the turn seen
    // (`herdr.seen`), so finishing it is Idle, not Done.
    let seen = host.agent("seen", None, "status working\nwait\nstatus done\nwait\n");
    host.hc.hc(&["label", &seen, "herdr.seen=1000"]);
    host.hc.hc(&["send", &seen, "go"]);
    std::thread::sleep(Duration::from_millis(500));
    let unseen = host.agent(
        "unseen",
        None,
        "status working\nsleep 0.2\nstatus done\nwait\n",
    );

    assert_eq!(host.exactly(1), [(unseen, AgentStatus::Done)]);
}

#[test]
fn an_agent_already_blocked_when_serve_starts_is_not_pushed() {
    let mut host = Host::new();
    host.agent(
        "early",
        None,
        "status blocked permission Allow Bash?\nwait\n",
    );
    std::thread::sleep(Duration::from_millis(300));
    host.serve();

    let marker = host.agent("marker", None, "status error - marker\nwait\n");

    assert_eq!(host.exactly(1), [(marker, error("marker"))]);
}

/// Ticket 02's setup: the real Claude Code, asked to write a file, waits for
/// permission (OSC 7501 `blocked`/`permission`) and the phone gets one push.
/// Uses the user's Claude Code login and a little of its quota, so it only
/// runs on request: `ARIES_REAL_CLAUDE=1 cargo test -p aries-beacon --test
/// status_push -- --ignored real_claude`.
#[test]
#[ignore = "runs the real Claude Code"]
fn real_claude_code_waiting_for_permission_is_pushed() {
    if std::env::var_os("ARIES_REAL_CLAUDE").is_none() {
        eprintln!("skipped: set ARIES_REAL_CLAUDE=1");
        return;
    }
    let mut host = Host::new();
    // Restart the core without the CLAUDE* variables of the session running
    // this test, or Claude Code would act as its child (ticket 02 notes).
    host.hc.hc(&["server", "stop"]);
    let mut start = host.hc.command();
    for (k, _) in std::env::vars().filter(|(k, _)| k.starts_with("CLAUDE")) {
        start.env_remove(k);
    }
    assert!(start.args(["server", "start"]).status().unwrap().success());
    host.serve();

    let dir = tempfile::tempdir().unwrap();
    let cwd = dir.path().to_string_lossy().into_owned();
    let out = host.hc.hc(&[
        "run",
        "--bg",
        "--json",
        "--no-parent",
        "--name",
        "ca",
        "--cwd",
        &cwd,
        "--",
        "claude",
        "--model",
        "haiku",
        "--permission-mode",
        "default",
    ]);
    let address = serde_json::from_str::<serde_json::Value>(out.trim()).unwrap()["address"]
        .as_str()
        .unwrap()
        .to_owned();
    std::thread::sleep(Duration::from_secs(5));
    host.hc.hc(&["keys", "ca", "down", "enter"]); // the folder trust dialog
    std::thread::sleep(Duration::from_secs(3));
    host.hc.hc(&[
        "send",
        "ca",
        "Create a file hello.txt containing the word hi, using the Write tool.",
    ]);

    let push = host
        .relay
        .wait_pushes(1, Duration::from_secs(120))
        .remove(0);
    let (terminal, status) = status_of(open(&push.1, &host.phone, &host.beacon_key).unwrap());
    assert_eq!(terminal, address);
    match status {
        AgentStatus::Blocked {
            reason: Some(BlockedReason::Permission),
            message: Some(m),
        } => assert!(m.contains("hello.txt"), "{m}"),
        other => panic!("{other:?}"),
    }
    let _ = host.hc.command().args(["kill", "ca"]).status();
}
