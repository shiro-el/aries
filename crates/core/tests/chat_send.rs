//! Seam 1: sending a Chat message into an Agent's Terminal
//! (`HostConnection::send_message`), against a real herdr-core and the fake
//! Agent's input log.

mod common;

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use aries_core::{
    AttachOptions, BlockedReason, HostConnection, SendListener, SendOutcome, TerminalUpdate,
    TerminalView,
};
use aries_test_support::{FakeAgent, HcSession};
use common::{core_in, local_endpoint};

#[derive(Default)]
struct Held {
    calls: AtomicUsize,
    notified: tokio::sync::Notify,
}

impl SendListener for Held {
    fn on_held(&self) {
        self.calls.fetch_add(1, Ordering::SeqCst);
        self.notified.notify_one();
    }
}

impl Held {
    fn count(&self) -> usize {
        self.calls.load(Ordering::SeqCst)
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

/// Types through a separate interactive viewer and waits until its input is
/// echoed, so a send cannot race the person's attach/typing startup.
async fn person_typing(conn: &HostConnection, address: &str, text: &str) -> Arc<TerminalView> {
    let person = conn
        .attach_terminal(
            address.to_owned(),
            AttachOptions {
                observe: false,
                cols: 80,
                rows: 24,
                history: 0,
                resumed: false,
            },
        )
        .await
        .unwrap();
    person.send_text(text.into()).await.unwrap();
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            match person.next().await.unwrap() {
                TerminalUpdate::Screen { frame } => {
                    if frame.lines.iter().any(|line| {
                        line.runs
                            .iter()
                            .map(|run| run.text.as_str())
                            .collect::<String>()
                            .contains(text)
                    }) {
                        break;
                    }
                }
                update => panic!("person's Terminal ended before echoing input: {update:?}"),
            }
        }
    })
    .await
    .expect("person's input never reached the Terminal");
    person
}

#[tokio::test]
async fn a_message_waits_while_a_person_types_then_goes_in() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status idle\nwait\nstatus working\n");
    wait_state(&hc, &agent.address, "idle");
    let data = tempfile::tempdir().unwrap();
    let conn = connect(&hc, &data).await;

    // Another interactive client uses the same human-input channel as hc
    // attach. Wait for its echo instead of assuming a subprocess is ready
    // after 600 ms (attach itself can spend 500 ms querying its outer tty).
    let person_data = tempfile::tempdir().unwrap();
    let person_conn = connect(&hc, &person_data).await;
    let person = person_typing(&person_conn, &agent.address, "ab").await;
    let held = Arc::new(Held::default());
    let send = conn.send_message(agent.address.clone(), "after you".into(), held.clone());
    tokio::pin!(send);
    let outcome = tokio::time::timeout(Duration::from_secs(15), async {
        tokio::select! {
            result = &mut send => panic!("message completed before being held: {result:?}"),
            _ = held.notified.notified() => {}
        }
        assert!(
            agent.inputs().is_empty(),
            "a held message must not be submitted"
        );
        // The person can keep typing while Chat waits, then pause. The
        // final input assertion checks that the draft precedes the send.
        person.send_text("cd".into()).await.unwrap();
        send.await
    })
    .await
    .expect("held message never completed after the person paused");
    person.detach().await;

    assert_eq!(outcome.unwrap(), SendOutcome::Sent);
    assert_eq!(held.count(), 1, "the app is told the message is held");
    // The person's keys come first, then the message.
    assert_eq!(inputs_after(&agent, 1), vec!["abcdafter you"]);
}

/// The supported TUIs enable bracketed paste. Capture the bytes at a real
/// Terminal boundary: LF must stay inside one paste, with exactly one final
/// Enter. A second send also catches stray submissions from the first draft.
#[tokio::test]
async fn multiline_messages_are_one_paste_and_one_submit_for_each_agent() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let script = data.path().join("capture.py");
    std::fs::write(
        &script,
        r#"import json, os, sys, tty

tty.setraw(0)
os.write(1, b'\x1b[?2004h\x1b]7501;state=idle:app=' + sys.argv[2].encode() + b'\x1b\\')
raw = b''
while True:
    byte = os.read(0, 1)
    if not byte:
        break
    raw += byte
    if byte == b'\r':
        with open(sys.argv[1], 'a') as log:
            log.write(json.dumps(list(raw)) + '\n')
        raw = b''
"#,
    )
    .unwrap();
    let conn = connect(&hc, &data).await;
    let message = "첫 줄\n\nsecond line\n";
    for app in ["claude-code", "codex", "opencode", "pi"] {
        let log = data.path().join(format!("{app}.jsonl"));
        let address = hc.run_bg(
            app,
            &[
                "python3",
                &script.to_string_lossy(),
                &log.to_string_lossy(),
                app,
            ],
        );
        wait_state(&hc, &address, "idle");
        for text in [message, "next"] {
            assert_eq!(
                conn.send_message(address.clone(), text.into(), Arc::new(Held::default()))
                    .await
                    .unwrap(),
                SendOutcome::Sent,
            );
        }
        let deadline = Instant::now() + Duration::from_secs(10);
        let received = loop {
            let lines: Vec<Vec<u8>> = std::fs::read_to_string(&log)
                .unwrap_or_default()
                .lines()
                .map(|l| serde_json::from_str(l).unwrap())
                .collect();
            if lines.len() >= 2 {
                break lines;
            }
            assert!(
                Instant::now() < deadline,
                "{app}: no submitted messages: {lines:?}"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        };
        let expected: Vec<Vec<u8>> = [message, "next"]
            .into_iter()
            .map(|text| format!("\x1b[200~{text}\x1b[201~\r").into_bytes())
            .collect();
        assert_eq!(
            received, expected,
            "{app}: LF stays in the paste until one final Enter"
        );
    }
}

#[tokio::test]
async fn a_notification_reply_reaches_the_terminal_without_a_foreground_connection() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status idle\nwait\nstatus working\n");
    wait_state(&hc, &agent.address, "idle");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let outcome = core
        .send_message_background(
            host.id,
            agent.address.clone(),
            "run the tests too".into(),
            Arc::new(Held::default()),
        )
        .await
        .unwrap();
    assert_eq!(outcome, SendOutcome::Sent);
    assert_eq!(inputs_after(&agent, 1), vec!["run the tests too"]);
}

#[tokio::test]
async fn background_seen_never_replaces_a_newer_desktop_confirmation() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status working\nstatus done\n");
    wait_state(&hc, &agent.address, "done");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let after = core
        .mark_seen_background(host.id.clone(), agent.address.clone(), 1)
        .await
        .unwrap();
    assert_eq!(after.agent_status, Some(aries_core::AgentStatus::Idle));
    hc.hc(&["label", &agent.address, "herdr.seen=9"]);
    core.mark_seen_background(host.id, agent.address.clone(), 1)
        .await
        .unwrap();
    let record: serde_json::Value = serde_json::from_str(&hc.hc(&["get", &agent.address])).unwrap();
    assert_eq!(record["labels"]["herdr.seen"], "9");
}

#[tokio::test]
async fn a_background_reply_never_answers_a_blocked_dialog() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status blocked permission Allow Write?\n");
    wait_state(&hc, &agent.address, "blocked");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let outcome = core
        .send_message_background(
            host.id,
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
            message: Some("Allow Write?".into())
        }
    );
    assert!(agent.inputs().is_empty());
}

#[tokio::test]
async fn a_background_reply_reports_when_typing_holds_it() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status idle\nwait\nstatus working\n");
    wait_state(&hc, &agent.address, "idle");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let person_data = tempfile::tempdir().unwrap();
    let person_conn = connect(&hc, &person_data).await;
    let person = person_typing(&person_conn, &agent.address, "abcd").await;
    let held = Arc::new(Held::default());
    let outcome = core
        .send_message_background(
            host.id,
            agent.address.clone(),
            "after you".into(),
            held.clone(),
        )
        .await;
    person.detach().await;
    assert_eq!(outcome.unwrap(), SendOutcome::Sent);
    assert_eq!(held.count(), 1);
    assert_eq!(inputs_after(&agent, 1), vec!["abcdafter you"]);
}

#[tokio::test]
async fn a_background_connection_has_a_deadline_even_during_the_handshake() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host("silent".into(), common::script_endpoint("exec sleep 60"))
        .unwrap();
    let start = Instant::now();
    let result = core
        .send_message_background(
            host.id,
            "t1".into(),
            "hello".into(),
            Arc::new(Held::default()),
        )
        .await;
    assert!(matches!(
        result,
        Err(aries_core::CoreError::Unreachable { .. })
    ));
    assert!(start.elapsed() < Duration::from_secs(18));
}

#[tokio::test]
async fn background_seen_writes_turn_zero_when_the_shared_label_is_absent() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status idle\n");
    wait_state(&hc, &agent.address, "idle");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    core.mark_seen_background(host.id, agent.address.clone(), 0)
        .await
        .unwrap();
    let record: serde_json::Value = serde_json::from_str(&hc.hc(&["get", &agent.address])).unwrap();
    assert_eq!(record["labels"]["herdr.seen"], "0");
}
