//! Seam 1: sending a Chat message into an Agent's Terminal
//! (`HostConnection::send_message`), against a real herdr-core and the fake
//! Agent's input log.

mod common;

use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use aries_core::{BlockedReason, HostConnection, SendListener, SendOutcome};
use aries_test_support::{FakeAgent, HcSession};
use common::{core_in, local_endpoint};

#[derive(Default)]
struct Held(AtomicUsize);

impl SendListener for Held {
    fn on_held(&self) {
        self.0.fetch_add(1, Ordering::SeqCst);
    }
}

impl Held {
    fn count(&self) -> usize {
        self.0.load(Ordering::SeqCst)
    }
}

async fn connect(hc: &HcSession, data: &tempfile::TempDir) -> Arc<HostConnection> {
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(hc)).unwrap();
    core.connect(host.id).await.unwrap()
}

/// Waits until the fake Agent has read `want` lines.
fn inputs_after(agent: &FakeAgent, want: usize) -> Vec<String> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let got = agent.inputs();
        if got.len() >= want || Instant::now() > deadline {
            return got;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// Waits until the Agent's status (as `hc get` shows it) is `state`.
fn wait_state(hc: &HcSession, address: &str, state: &str) {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let t: serde_json::Value =
            serde_json::from_str(hc.hc(&["get", "--json", address]).trim()).unwrap();
        if t["status"]["state"] == state {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "{address} never became {state}: {t}"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

#[tokio::test]
async fn a_message_is_typed_into_the_agents_terminal() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status idle\nwait\nstatus working\n");
    wait_state(&hc, &agent.address, "idle");
    let data = tempfile::tempdir().unwrap();
    let conn = connect(&hc, &data).await;

    let held = Arc::new(Held::default());
    let outcome = conn
        .send_message(agent.address.clone(), "fix the test".into(), held.clone())
        .await
        .unwrap();

    assert_eq!(outcome, SendOutcome::Sent);
    assert_eq!(inputs_after(&agent, 1), vec!["fix the test"]);
    assert_eq!(held.count(), 0, "nobody was typing");
}

#[tokio::test]
async fn a_blocked_agent_refuses_the_message_and_says_why() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status blocked permission Allow Write: a.txt?\n");
    wait_state(&hc, &agent.address, "blocked");
    let data = tempfile::tempdir().unwrap();
    let conn = connect(&hc, &data).await;

    let outcome = conn
        .send_message(
            agent.address.clone(),
            "yes".into(),
            Arc::new(Held::default()),
        )
        .await
        .unwrap();

    assert_eq!(
        outcome,
        SendOutcome::Blocked {
            reason: Some(BlockedReason::Permission),
            message: Some("Allow Write: a.txt?".into()),
        }
    );
    std::thread::sleep(Duration::from_millis(500));
    assert!(agent.inputs().is_empty(), "nothing may answer its dialog");
}

/// A person at the Host typing into the Terminal, through `hc attach` on a
/// pseudo-terminal (`script`): one key of `keys` every 400 ms.
fn person_typing(hc: &HcSession, address: &str, keys: &str) -> Child {
    let attach = format!("{} attach {address}", hc.bin().display());
    let typed: String = keys
        .chars()
        .map(|c| format!("printf {c}; sleep 0.4; "))
        .collect();
    // script types what it reads on the attach's tty; Ctrl+\ (034) detaches
    // at the end, the timeout is a backstop.
    let feed = format!(
        "( {typed}sleep 1; printf '\\034' ) | timeout 20 script -qfc '{attach}' /dev/null >/dev/null 2>&1"
    );
    let mut cmd = Command::new("sh");
    cmd.arg("-c").arg(feed).stdin(Stdio::null());
    for (k, v) in hc.env() {
        cmd.env(k, v);
    }
    for k in [
        "HC_TERMINAL_ID",
        "HC_TERMINAL_SOCKET",
        "HC_SOCKET",
        "HC_SCOPE",
    ] {
        cmd.env_remove(k);
    }
    cmd.spawn().unwrap()
}

/// Waits for the person to finish typing and detach.
fn finish(mut child: Child) {
    let _ = child.wait();
}

#[tokio::test]
async fn a_message_waits_while_a_person_types_then_goes_in() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status idle\nwait\nstatus working\n");
    wait_state(&hc, &agent.address, "idle");
    let data = tempfile::tempdir().unwrap();
    let conn = connect(&hc, &data).await;

    let person = person_typing(&hc, &agent.address, "abcd");
    std::thread::sleep(Duration::from_millis(600));
    let held = Arc::new(Held::default());
    let t0 = Instant::now();
    let outcome = conn
        .send_message(agent.address.clone(), "after you".into(), held.clone())
        .await;
    let took = t0.elapsed();
    finish(person);

    assert_eq!(outcome.unwrap(), SendOutcome::Sent);
    assert_eq!(held.count(), 1, "the app is told the message is held");
    assert!(
        took > Duration::from_secs(2),
        "held until the typing paused: {took:?}"
    );
    // The person's keys come first, then the message.
    assert_eq!(inputs_after(&agent, 1), vec!["abcdafter you"]);
}
