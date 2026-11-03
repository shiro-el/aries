//! Seam 1: the line to a Host drops (network change), and a new connection
//! restores the Agent list and the open Terminal's screen, against a real
//! herdr-core.

mod common;

use std::collections::HashMap;
use std::path::Path;
use std::time::Duration;

use aries_core::{
    AgentStatus, AttachOptions, CoreError, Endpoint, ScreenFrame, TerminalInfo, TerminalUpdate,
    TerminalView, TerminalWatch,
};
use aries_test_support::HcSession;
use common::core_in;

/// `herdr-core connect` for `hc`, run through a shell that writes its pid to
/// `pidfile`, so the test can cut the line the way a dead network does.
fn droppable_endpoint(hc: &HcSession, pidfile: &Path) -> Endpoint {
    let script = format!("echo $$ > '{}'; exec \"$0\" \"$@\"", pidfile.display());
    let mut argv = vec!["/bin/sh".to_owned(), "-c".to_owned(), script];
    argv.extend(hc.connect_argv());
    Endpoint::Local {
        argv,
        env: hc.env().into_iter().collect::<HashMap<_, _>>(),
    }
}

fn cut_line(pidfile: &Path) {
    let pid = std::fs::read_to_string(pidfile).expect("pid file");
    let status = std::process::Command::new("kill")
        .args(["-9", pid.trim()])
        .status()
        .expect("run kill");
    assert!(status.success(), "kill {pid}");
}

/// Rows as text, from the frames received so far.
#[derive(Default)]
struct Screen {
    rows: Vec<String>,
}

impl Screen {
    fn apply(&mut self, f: &ScreenFrame) {
        if f.full || f.rows as usize != self.rows.len() {
            self.rows = vec![String::new(); f.rows as usize];
        }
        for line in &f.lines {
            let mut text = String::new();
            for run in &line.runs {
                while text.chars().count() < run.column as usize {
                    text.push(' ');
                }
                text.push_str(&run.text);
            }
            self.rows[line.row as usize] = text.trim_end().to_owned();
        }
    }

    fn contains(&self, needle: &str) -> bool {
        self.rows.iter().any(|r| r.contains(needle))
    }
}

/// The first update must be a full frame: the screen as it is now.
async fn first_paint(view: &TerminalView) -> Screen {
    let update = tokio::time::timeout(Duration::from_secs(20), view.next())
        .await
        .expect("first paint in time")
        .expect("first paint");
    let TerminalUpdate::Screen { frame } = update else {
        panic!("first update is not a screen: {update:?}");
    };
    assert!(frame.full, "the first paint is a whole screen");
    let mut screen = Screen::default();
    screen.apply(&frame);
    screen
}

async fn screen_until(view: &TerminalView, screen: &mut Screen, needle: &str) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    while !screen.contains(needle) {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(left, view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { frame })) => screen.apply(&frame),
            other => panic!(
                "waiting for {needle:?}: {other:?}; screen {:#?}",
                screen.rows
            ),
        }
    }
}

async fn watch_until(
    watch: &TerminalWatch,
    pred: impl Fn(&[TerminalInfo]) -> bool,
) -> Vec<TerminalInfo> {
    loop {
        let list = tokio::time::timeout(Duration::from_secs(20), watch.next())
            .await
            .expect("list in time")
            .expect("list");
        if pred(&list) {
            return list;
        }
    }
}

/// Reads `view` until it ends; returns how.
async fn view_end(view: &TerminalView) -> CoreError {
    loop {
        match tokio::time::timeout(Duration::from_secs(10), view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { .. })) => {}
            Ok(Ok(TerminalUpdate::Exited { exit })) => panic!("not an exit: {exit:?}"),
            Ok(Err(e)) => return e,
            Err(_) => panic!("the view did not notice the dropped line"),
        }
    }
}

fn status_of(list: &[TerminalInfo], address: &str) -> Option<AgentStatus> {
    list.iter()
        .find(|t| t.address == address)
        .and_then(|t| t.agent_status.clone())
}

fn observe(resumed: bool) -> AttachOptions {
    AttachOptions {
        observe: true,
        cols: 80,
        rows: 24,
        history: 0,
        resumed,
    }
}

#[tokio::test]
async fn a_new_connection_after_a_dropped_line_restores_the_list_and_the_screen() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status working\nwait\nstatus done\nwait\n");
    let job = hc.run_bg(
        "job",
        &[
            "sh",
            "-c",
            "echo before the drop; read x; echo while away: $x; exec sleep 600",
        ],
    );
    std::thread::sleep(Duration::from_millis(300));

    let data = tempfile::tempdir().unwrap();
    let pidfile = data.path().join("connect.pid");
    let core = core_in(data.path());
    let host = core
        .add_host("dev".into(), droppable_endpoint(&hc, &pidfile))
        .unwrap();

    let conn = core.connect(host.id.clone()).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();
    watch_until(&watch, |l| {
        status_of(l, &agent.address) == Some(AgentStatus::Working)
    })
    .await;
    let view = conn
        .attach_terminal(job.clone(), observe(false))
        .await
        .unwrap();
    let mut screen = first_paint(&view).await;
    screen_until(&view, &mut screen, "before the drop").await;

    cut_line(&pidfile);
    assert!(matches!(view_end(&view).await, CoreError::Closed));
    let ended = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let Err(e) = watch.next().await {
                return e;
            }
        }
    })
    .await
    .expect("the watch notices the dropped line");
    assert!(matches!(ended, CoreError::Closed));

    // Things move on while the phone is away.
    hc.hc(&["send", &job, "news"]);
    hc.hc(&["send", &agent.address, "go"]);
    std::thread::sleep(Duration::from_millis(500));

    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();
    let snapshot = tokio::time::timeout(Duration::from_secs(20), watch.next())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        status_of(&snapshot, &agent.address),
        Some(AgentStatus::Done),
        "the fresh snapshot already has what changed while away"
    );

    let view = conn.attach_terminal(job, observe(true)).await.unwrap();
    let screen = first_paint(&view).await;
    assert!(
        screen.contains("before the drop") && screen.contains("while away: news"),
        "the first paint after a resumed attach is the whole screen as it is now: {:#?}",
        screen.rows
    );
    view.detach().await;
    conn.disconnect().await;
}

#[tokio::test]
async fn a_resumed_input_viewer_attaches_without_taking_the_size_first() {
    let hc = HcSession::start();
    let job = hc.run_bg("job", &["sh", "-c", "echo ready; exec sleep 600"]);
    std::thread::sleep(Duration::from_millis(300));
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host("dev".into(), common::local_endpoint(&hc))
        .unwrap();
    let typing = |cols, rows, resumed| AttachOptions {
        observe: false,
        cols,
        rows,
        history: 0,
        resumed,
    };
    // The desktop types at 100x30.
    let desktop = core.connect(host.id.clone()).await.unwrap();
    let desk = desktop
        .attach_terminal(job.clone(), typing(100, 30, false))
        .await
        .unwrap();
    first_paint(&desk).await;
    wait_size(&hc, &job, (100, 30)).await;

    // The phone comes back as an input viewer after a dropped line.
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(job.clone(), typing(50, 12, true))
        .await
        .unwrap();
    let mut screen = first_paint(&view).await;
    screen_until(&view, &mut screen, "ready").await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    // herdr-core: "Back after a dropped line: sets the size only once active
    // again", so coming back does not resize the desktop by itself.
    assert_eq!(size_of(&hc, &job), (100, 30));

    // Without `resumed`, attaching to type takes the size right away.
    let fresh = conn
        .attach_terminal(job.clone(), typing(60, 14, false))
        .await
        .unwrap();
    first_paint(&fresh).await;
    wait_size(&hc, &job, (60, 14)).await;
    for v in [view, fresh, desk] {
        v.detach().await;
    }
    conn.disconnect().await;
    desktop.disconnect().await;
}

async fn wait_size(hc: &HcSession, address: &str, want: (u64, u64)) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    while size_of(hc, address) != want {
        assert!(
            tokio::time::Instant::now() < deadline,
            "size stays {:?}, want {want:?}",
            size_of(hc, address)
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

fn size_of(hc: &HcSession, address: &str) -> (u64, u64) {
    let out = hc.hc(&["get", "--json", address]);
    let t: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
    (t["cols"].as_u64().unwrap(), t["rows"].as_u64().unwrap())
}
