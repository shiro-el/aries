//! Chat interruption through the core API and a real isolated herdr-core.
mod common;

use std::time::Duration;

use aries_core::InterruptOutcome;
use aries_test_support::HcSession;
use common::{core_in, local_endpoint, wait_state};

#[tokio::test]
async fn interrupt_delivers_escape_without_submitting_or_killing_each_reporting_agent() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let script = data.path().join("interrupt.py");
    std::fs::write(
        &script,
        r#"import os, sys, tty

tty.setraw(0)
os.write(1, b'\x1b]7501;state=working:app=' + sys.argv[2].encode() + b'\x1b\\')
while True:
    byte = os.read(0, 1)
    with open(sys.argv[1], 'ab') as log:
        log.write(byte)
    if byte == b'\x1b':
        os.write(1, b'\x1b]7501;state=idle\x1b\\')
"#,
    )
    .unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    for app in ["claude-code", "codex", "opencode", "pi"] {
        let log = data.path().join(format!("{app}.raw"));
        let address = hc.run_bg(
            app,
            &[
                "python3",
                script.to_str().unwrap(),
                log.to_str().unwrap(),
                app,
            ],
        );
        wait_state(&hc, &address, "working");
        assert_eq!(
            conn.interrupt_agent(address.clone()).await.unwrap(),
            InterruptOutcome::Stopped
        );
        assert_eq!(
            std::fs::read(&log).unwrap(),
            b"\x1b",
            "{app}: only the stop key, no Enter or text"
        );
        let terminal = conn
            .terminals()
            .await
            .unwrap()
            .into_iter()
            .find(|t| t.address == address)
            .unwrap();
        assert_eq!(
            terminal.state, "live",
            "{app}: interrupt must leave the Agent alive"
        );
        // A duplicate request after completion cannot send another key.
        assert_eq!(
            conn.interrupt_agent(address).await.unwrap(),
            InterruptOutcome::NotWorking
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert_eq!(std::fs::read(&log).unwrap(), b"\x1b");
    }
}

#[tokio::test]
async fn stale_working_status_never_sends_a_key_to_a_blocked_agent() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let script = data.path().join("blocked.py");
    let log = data.path().join("blocked.raw");
    std::fs::write(
        &script,
        r#"import os, sys, tty
tty.setraw(0)
os.write(1, b'\x1b]7501;state=blocked:reason=permission:app=claude-code\x1b\\')
while True:
    byte = os.read(0, 1)
    with open(sys.argv[1], 'ab') as log:
        log.write(byte)
"#,
    )
    .unwrap();
    let address = hc.run_bg(
        "blocked",
        &["python3", script.to_str().unwrap(), log.to_str().unwrap()],
    );
    wait_state(&hc, &address, "blocked");
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    assert_eq!(
        conn.interrupt_agent(address).await.unwrap(),
        InterruptOutcome::NotWorking
    );
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert!(
        !log.exists(),
        "a stale interrupt must not dismiss a permission dialog"
    );
}
