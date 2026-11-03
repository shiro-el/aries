//! Seam 1: searching the attached VT, against an isolated real herdr-core.
mod common;

use std::time::Duration;

use aries_core::{AttachOptions, ScreenFrame, TerminalUpdate, TerminalView};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

async fn frame(view: &TerminalView) -> ScreenFrame {
    match tokio::time::timeout(Duration::from_secs(10), view.next())
        .await
        .expect("screen update")
        .unwrap()
    {
        TerminalUpdate::Screen { frame } => frame,
        other => panic!("expected screen, got {other:?}"),
    }
}

#[tokio::test]
async fn search_finds_scrollback_highlights_cells_and_wraps_without_typing_or_resizing() {
    let hc = HcSession::start();
    let address = hc.run_bg(
        "search",
        &[
            "sh",
            "-c",
            "printf 'Error[7] old\\n'; seq 1 180; printf 'error[7] new\\nREADY\\n'; sleep 300",
        ],
    );
    std::thread::sleep(Duration::from_millis(300));
    let desktop = hc.hc(&["get", "--json", &address]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(
            address.clone(),
            AttachOptions {
                observe: true,
                cols: 80,
                rows: 24,
                history: 1000,
                resumed: false,
            },
        )
        .await
        .unwrap();
    loop {
        if frame(&view)
            .await
            .lines
            .iter()
            .flat_map(|l| &l.runs)
            .any(|r| r.text.contains("READY"))
        {
            break;
        }
    }

    view.search("error[7]".into(), false, false).unwrap();
    let first = frame(&view).await;
    let found = first.search.unwrap();
    assert_eq!((found.total, found.current), (2, Some(0)));
    assert!(first.scrolled > 0, "old match is revealed in scrollback");
    let mark = found.highlights.iter().find(|h| h.current).unwrap();
    assert_eq!((mark.column, mark.width), (0, 8));
    assert!(
        first
            .lines
            .iter()
            .find(|l| l.row == mark.row)
            .unwrap()
            .runs
            .iter()
            .any(|r| r.text.contains("Error[7] old"))
    );

    view.search_next(false);
    let second = frame(&view).await;
    assert_eq!(second.search.unwrap().current, Some(1));
    assert_eq!(second.scrolled, 0);
    view.search_next(false);
    assert_eq!(frame(&view).await.search.unwrap().current, Some(0));
    view.search_next(true);
    assert_eq!(frame(&view).await.search.unwrap().current, Some(1));
    assert_eq!(
        hc.hc(&["get", "--json", &address]),
        desktop,
        "search never changes the remote Terminal"
    );

    view.search("Error[7]".into(), true, false).unwrap();
    assert_eq!(frame(&view).await.search.unwrap().total, 1);
    view.search("error\\[[0-9]\\]".into(), false, true).unwrap();
    assert_eq!(frame(&view).await.search.unwrap().total, 2);
    assert!(view.search("[".into(), false, true).is_err());
    // Invalid patterns leave the usable search intact.
    view.search_next(false);
    assert_eq!(frame(&view).await.search.unwrap().total, 2);
    view.search("not present".into(), false, false).unwrap();
    let empty = frame(&view).await.search.unwrap();
    assert_eq!(
        (empty.total, empty.current, empty.highlights.len()),
        (0, None, 0)
    );
    view.search_next(true);
    assert_eq!(frame(&view).await.search.unwrap().total, 0);
    view.search("".into(), false, false).unwrap();
    assert!(frame(&view).await.search.is_none());
    view.detach().await;
    conn.disconnect().await;
}

#[tokio::test]
async fn search_spans_soft_wraps_and_wide_cells_and_updates_with_output_and_resize() {
    let hc = HcSession::start();
    let address = hc.run_bg("wrapped", &["sh", "-c",
        "echo READY; read go; printf '\\033[2J\\033[H123456789한Z\\r\\nDONE\\r\\n'; read go; printf '한Z\\r\\nMORE\\r\\n'; sleep 300"]);
    std::thread::sleep(Duration::from_millis(300));
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(
            address.clone(),
            AttachOptions {
                observe: false,
                cols: 10,
                rows: 6,
                history: 1000,
                resumed: false,
            },
        )
        .await
        .unwrap();
    frame(&view).await;
    hc.hc(&["send", &address, "go"]);
    loop {
        if frame(&view)
            .await
            .lines
            .iter()
            .flat_map(|l| &l.runs)
            .any(|r| r.text.contains("DONE"))
        {
            break;
        }
    }
    view.search("9한Z".into(), true, false).unwrap();
    let found = frame(&view).await.search.unwrap();
    assert_eq!(found.total, 1);
    let cells: Vec<_> = found
        .highlights
        .iter()
        .map(|h| (h.row, h.column, h.width))
        .collect();
    // The wide character wraps to the next row; its leading spacer is included.
    assert_eq!(cells, vec![(0, 8, 2), (1, 0, 3)]);
    view.search("한Z".into(), false, false).unwrap();
    assert_eq!(frame(&view).await.search.unwrap().total, 1);
    hc.hc(&["send", &address, "go"]);
    loop {
        let update = frame(&view).await;
        if update
            .lines
            .iter()
            .flat_map(|l| &l.runs)
            .any(|r| r.text.contains("MORE"))
        {
            assert_eq!(
                update.search.unwrap().total,
                2,
                "matches refresh with live output"
            );
            break;
        }
    }
    view.resize(20, 6).await.unwrap();
    assert_eq!(
        frame(&view).await.search.unwrap().total,
        2,
        "search follows VT reflow"
    );
    view.detach().await;
    conn.disconnect().await;
}

#[tokio::test]
async fn finished_terminal_remains_searchable_and_scrollable_until_detach() {
    let hc = HcSession::start();
    let address = hc.run_bg(
        "finished",
        &["sh", "-c", "echo READY; read go; echo FINISHED"],
    );
    std::thread::sleep(Duration::from_millis(300));
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(
            address.clone(),
            AttachOptions {
                observe: true,
                cols: 80,
                rows: 24,
                history: 1000,
                resumed: false,
            },
        )
        .await
        .unwrap();
    frame(&view).await;
    hc.hc(&["send", &address, "go"]);
    loop {
        if matches!(
            tokio::time::timeout(Duration::from_secs(10), view.next())
                .await
                .unwrap()
                .unwrap(),
            TerminalUpdate::Exited { .. }
        ) {
            break;
        }
    }
    view.search("FINISHED".into(), true, false).unwrap();
    let found = frame(&view).await.search.unwrap();
    assert_eq!((found.total, found.current), (1, Some(0)));
    view.scroll_to(0);
    assert_eq!(frame(&view).await.search.unwrap().total, 1);
    view.detach().await;
    conn.disconnect().await;
}
