//! Core API seam: foreground snapshots and changes from an isolated core.
mod common;

use aries_core::{LaunchRequest, TerminalInfo, TerminalWatch};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};
use std::time::Duration;

async fn until(watch: &TerminalWatch, predicate: impl Fn(&TerminalInfo) -> bool) -> TerminalInfo {
    tokio::time::timeout(Duration::from_secs(15), async {
        loop {
            if let Some(t) = watch.next().await.unwrap().into_iter().find(&predicate) {
                return t;
            }
        }
    })
    .await
    .expect("foreground update")
}

#[tokio::test]
async fn foreground_tracks_the_current_program_and_folder_and_clears_on_exit() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let cwd = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let terminal = conn
        .launch(LaunchRequest {
            command: "/bin/sh".into(),
            cwd: Some("/".into()),
            space_id: None,
            tab_id: None,
            preset: None,
        })
        .await
        .unwrap();
    let watch = conn.watch_terminals().await.unwrap();
    until(&watch, |t| t.address == terminal.address).await;
    hc.hc(&[
        "send",
        &terminal.address,
        &format!("cd '{}' && /bin/sleep 300", cwd.path().display()),
    ]);
    let running = until(&watch, |t| {
        t.foreground.as_ref().is_some_and(|f| f.name == "sleep")
    })
    .await;
    let fg = running.foreground.unwrap();
    assert_eq!(fg.cwd.as_deref(), cwd.path().to_str());
    assert_eq!(fg.argv, ["/bin/sleep", "300"]);
    assert!(fg.pid > 0);
    assert_eq!(
        running.cwd.as_deref(),
        Some("/"),
        "launch cwd stays available as a fallback"
    );
    // A new subscription starts with the current foreground in its snapshot.
    let snapshot = conn.watch_terminals().await.unwrap();
    let current = until(&snapshot, |t| t.address == terminal.address).await;
    assert_eq!(current.foreground, Some(fg));
    conn.kill_terminal(terminal.address.clone()).await.unwrap();
    let finished = until(&watch, |t| {
        t.address == terminal.address && t.state == "finished"
    })
    .await;
    assert_eq!(finished.foreground, None);
    snapshot.stop();
    watch.stop();
    conn.disconnect().await;
}
