//! Reading back in a program on the alternate screen (a fullscreen agent
//! TUI), which keeps its own history: the phone's drag reaches the program,
//! against a real herdr-core.

mod common;

use std::time::Duration;

use aries_core::{AttachOptions, TerminalUpdate, TerminalView};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

/// Enters the alternate screen with SGR mouse reports, as Claude Code's
/// fullscreen renderer does, then prints every input it reads.
const ECHO_INPUT: &[&str] = &[
    "python3",
    "-u",
    "-c",
    "import os, sys, tty\n\
     sys.stdout.write('\\x1b[?1049h\\x1b[?1000h\\x1b[?1006hREADY\\r\\n')\n\
     tty.setraw(0)\n\
     while True:\n\
     \x20   sys.stdout.write(repr(os.read(0, 256)) + '\\r\\n')\n",
];

/// Waits for a frame whose screen holds `needle`; returns that frame's
/// `alt_screen` flag.
async fn until_shown(view: &TerminalView, rows: &mut Vec<String>, needle: &str) -> bool {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
    loop {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        let frame = match tokio::time::timeout(left, view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { frame })) => frame,
            other => panic!("waiting for {needle:?}: {other:?}; screen: {rows:#?}"),
        };
        if frame.full || rows.len() != frame.rows as usize {
            *rows = vec![String::new(); frame.rows as usize];
        }
        for line in &frame.lines {
            rows[line.row as usize] = line.runs.iter().map(|r| r.text.as_str()).collect();
        }
        if rows.iter().any(|r| r.contains(needle)) {
            return frame.alt_screen;
        }
    }
}

fn grid_of(hc: &HcSession, address: &str) -> (u64, u64) {
    let out = hc.hc(&["get", "--json", address]);
    let t: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
    (t["cols"].as_u64().unwrap(), t["rows"].as_u64().unwrap())
}

#[tokio::test]
async fn an_observer_pages_the_program_without_resizing_it() {
    let hc = HcSession::start();
    let address = hc.run_bg("echo-input", ECHO_INPUT);
    std::thread::sleep(Duration::from_millis(300));
    let desktop = grid_of(&hc, &address);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let options = AttachOptions {
        observe: true,
        cols: 80,
        rows: 24,
        history: 1000,
        resumed: false,
    };
    let view = conn
        .attach_terminal(address.clone(), options)
        .await
        .unwrap();
    let mut rows = Vec::new();
    assert!(
        until_shown(&view, &mut rows, "READY").await,
        "alt screen not reported"
    );

    // Half a screen dragged back is one Page Up; the same back down, Page Down.
    let half = (desktop.1 / 2) as i32;
    view.scroll_program(half - 1, 0, 0).await.unwrap();
    view.scroll_program(1, 0, 0).await.unwrap();
    until_shown(&view, &mut rows, r"\x1b[5~").await;
    view.scroll_program(-half, 0, 0).await.unwrap();
    until_shown(&view, &mut rows, r"\x1b[6~").await;

    assert_eq!(grid_of(&hc, &address), desktop, "observing must not resize");
    conn.disconnect().await;
}

#[tokio::test]
async fn a_typing_view_sends_wheel_reports_where_the_finger_is() {
    let hc = HcSession::start();
    let address = hc.run_bg("echo-input", ECHO_INPUT);
    std::thread::sleep(Duration::from_millis(300));

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let options = AttachOptions {
        observe: false,
        cols: 60,
        rows: 20,
        history: 1000,
        resumed: false,
    };
    let view = conn
        .attach_terminal(address.clone(), options)
        .await
        .unwrap();
    let mut rows = Vec::new();
    assert!(
        until_shown(&view, &mut rows, "READY").await,
        "alt screen not reported"
    );

    // The mouse modes came with the attach's paint: one notch per three lines.
    view.scroll_program(3, 9, 4).await.unwrap();
    until_shown(&view, &mut rows, r"\x1b[<64;10;5M").await;
    view.scroll_program(-3, 0, 0).await.unwrap();
    until_shown(&view, &mut rows, r"\x1b[<65;1;1M").await;
    conn.disconnect().await;
}
