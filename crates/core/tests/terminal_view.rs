//! Seam 1: watching a Terminal in observe mode, against a real herdr-core.

mod common;

use std::time::Duration;

use aries_core::{AttachOptions, ScreenFrame, TerminalExit, TerminalUpdate, TerminalView};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

/// What a UI would draw: the grid built from the frames received so far.
#[derive(Default)]
struct Screen {
    cols: u16,
    rows: Vec<String>,
    frames: usize,
}

impl Screen {
    fn apply(&mut self, f: &ScreenFrame) {
        self.frames += 1;
        if f.full || f.cols != self.cols || f.rows as usize != self.rows.len() {
            self.cols = f.cols;
            self.rows = vec![String::new(); f.rows as usize];
        }
        for line in &f.lines {
            let mut text = String::new();
            let mut col = 0u16;
            for run in &line.runs {
                while col < run.column {
                    text.push(' ');
                    col += 1;
                }
                text.push_str(&run.text);
                col += run.width;
            }
            self.rows[line.row as usize] = text.trim_end().to_owned();
        }
    }

    fn contains(&self, needle: &str) -> bool {
        self.rows.iter().any(|r| r.contains(needle))
    }
}

/// Applies updates until `pred` holds; panics on exit, close or timeout.
async fn until(
    view: &TerminalView,
    screen: &mut Screen,
    what: &str,
    pred: impl Fn(&Screen) -> bool,
) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
    while !pred(screen) {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(left, view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { frame })) => screen.apply(&frame),
            Ok(Ok(TerminalUpdate::Exited { exit })) => {
                panic!(
                    "exited ({exit:?}) while waiting for {what}; screen: {:#?}",
                    screen.rows
                )
            }
            Ok(Err(e)) => panic!("view ended while waiting for {what}: {e:?}"),
            Err(_) => panic!("timed out waiting for {what}; screen: {:#?}", screen.rows),
        }
    }
}

/// Drains updates until the Terminal exits.
async fn exit_of(view: &TerminalView, screen: &mut Screen) -> TerminalExit {
    loop {
        match tokio::time::timeout(Duration::from_secs(30), view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { frame })) => screen.apply(&frame),
            Ok(Ok(TerminalUpdate::Exited { exit })) => return exit,
            Ok(Err(e)) => panic!("view ended without an exit: {e:?}"),
            Err(_) => panic!("timed out waiting for the exit"),
        }
    }
}

fn observe() -> AttachOptions {
    AttachOptions {
        observe: true,
        cols: 40,
        rows: 10,
        history: 0,
        resumed: false,
    }
}

#[tokio::test]
async fn shows_the_current_screen_then_live_output_then_the_exit_code() {
    let hc = HcSession::start();
    let address = hc.run_bg(
        "job",
        &[
            "sh",
            "-c",
            "printf 'first \\033[31mred\\033[0m line\\n'; read go; echo second line; exit 3",
        ],
    );
    std::thread::sleep(Duration::from_millis(300));

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(address.clone(), observe())
        .await
        .unwrap();

    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| {
        s.contains("first red line")
    })
    .await;
    // Observe mode takes the Terminal's own grid, not the size we asked for.
    let size = hc.hc(&["get", "--json", &address]);
    let t: serde_json::Value = serde_json::from_str(size.trim()).unwrap();
    assert_eq!(screen.cols as u64, t["cols"].as_u64().unwrap());
    assert_eq!(screen.rows.len() as u64, t["rows"].as_u64().unwrap());

    hc.hc(&["send", &address, "go"]);
    until(&view, &mut screen, "live output", |s| {
        s.contains("second line")
    })
    .await;

    let exit = exit_of(&view, &mut screen).await;
    assert_eq!(exit.exit_code, Some(3));
    assert_eq!(exit.reason, "exited");
    assert!(view.next().await.is_err(), "nothing follows the exit");
    conn.disconnect().await;
}

fn grid_of(hc: &HcSession, address: &str) -> (u64, u64) {
    let out = hc.hc(&["get", "--json", address]);
    let t: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
    (t["cols"].as_u64().unwrap(), t["rows"].as_u64().unwrap())
}

#[tokio::test]
async fn observing_leaves_the_desktop_size_and_follows_its_resizes() {
    let hc = HcSession::start();
    let address = hc.run_bg("shell", &["sh", "-c", "echo ready; sleep 300"]);
    let before = grid_of(&hc, &address);
    assert_ne!(before, (40, 10));

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let phone = core.connect(host.id.clone()).await.unwrap();
    let view = phone
        .attach_terminal(address.clone(), observe())
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| {
        s.contains("ready")
    })
    .await;
    assert_eq!(grid_of(&hc, &address), before, "observing must not resize");

    // The desktop: an ordinary viewer of 100x30 takes the size.
    let desktop = core.connect(host.id).await.unwrap();
    let typing = AttachOptions {
        observe: false,
        cols: 100,
        rows: 30,
        history: 0,
        resumed: false,
    };
    let _desk = desktop
        .attach_terminal(address.clone(), typing)
        .await
        .unwrap();
    until(&view, &mut screen, "the phone to follow 100x30", |s| {
        s.cols == 100 && s.rows.len() == 30
    })
    .await;
    until(&view, &mut screen, "the repaint after the resize", |s| {
        s.contains("ready")
    })
    .await;
    view.detach().await;
    assert!(view.next().await.is_err());
    phone.disconnect().await;
    desktop.disconnect().await;
}

/// A Terminal that floods about 6.9 MB when sent a line, then prints a marker.
const FLOOD: &[&str] = &[
    "sh",
    "-c",
    "read go; seq 1 1000000; echo FLOOD-DONE; sleep 300",
];

fn ends_the_flood(s: &Screen) -> bool {
    s.rows
        .iter()
        .position(|r| r == "FLOOD-DONE")
        .is_some_and(|n| n > 0 && s.rows[n - 1] == "1000000")
}

#[tokio::test]
async fn keeps_up_with_a_flood_of_output() {
    let hc = HcSession::start();
    let address = hc.run_bg("flood", FLOOD);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(address.clone(), observe())
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| s.frames > 0).await;

    let started = std::time::Instant::now();
    hc.hc(&["send", &address, "go"]);
    until(&view, &mut screen, "the end of the flood", ends_the_flood).await;
    let stats = view.stats();
    eprintln!("flood: {:?} in {:?}", stats, started.elapsed());
    assert!(
        stats.frames < 2000,
        "frames are batched, not one per write: {stats:?}"
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn recovers_with_a_repaint_after_falling_far_behind() {
    let hc = HcSession::start();
    let address = hc.run_bg("flood", FLOOD);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(address.clone(), observe())
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| s.frames > 0).await;

    // A phone that stops crediting falls behind by the whole flood.
    view.hold_credit(true);
    hc.hc(&["send", &address, "go"]);
    let deadline = std::time::Instant::now() + Duration::from_secs(30);
    while !hc
        .hc(&["read", &address, "--lines", "5"])
        .contains("FLOOD-DONE")
    {
        assert!(
            std::time::Instant::now() < deadline,
            "the flood never ended"
        );
        std::thread::sleep(Duration::from_millis(100));
    }
    view.hold_credit(false);
    until(&view, &mut screen, "the repainted end", ends_the_flood).await;
    let stats = view.stats();
    eprintln!("behind: {stats:?}");
    assert!(
        stats.bytes < 4 << 20,
        "got a repaint, not the 6.9 MB backlog: {stats:?}"
    );
    conn.disconnect().await;
}

fn percentile(sorted: &[f64], p: f64) -> f64 {
    sorted[((sorted.len() - 1) as f64 * p).round() as usize]
}

/// Latency baseline (docs/research/terminal-latency.md). Run with
/// `cargo test --release -p aries-core --test terminal_view -- --ignored --nocapture`.
#[tokio::test]
#[ignore = "measurement, not a check"]
async fn measure_output_latency_and_flood_throughput() {
    let hc = HcSession::start();
    // Prints wall-clock nanoseconds every 50 ms, 200 times.
    let ticker = hc.run_bg(
        "ticker",
        &["sh", "-c", "read go; i=0; while [ $i -lt 200 ]; do date +%s%N; sleep 0.05; i=$((i+1)); done; sleep 300"],
    );
    let flood = hc.run_bg("flood", FLOOD);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();

    let view = conn
        .attach_terminal(ticker.clone(), observe())
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| s.frames > 0).await;
    hc.hc(&["send", &ticker, "go"]);
    let mut seen = std::collections::HashSet::new();
    let mut lat = Vec::new();
    while lat.len() < 200 {
        let Ok(Ok(TerminalUpdate::Screen { frame })) =
            tokio::time::timeout(Duration::from_secs(10), view.next()).await
        else {
            break;
        };
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos() as i128;
        screen.apply(&frame);
        for row in &screen.rows {
            if let Ok(ts) = row.trim().parse::<i128>()
                && seen.insert(ts)
                && ts > 1_000_000_000_000_000_000
            {
                lat.push((now - ts) as f64 / 1e6);
            }
        }
    }
    view.detach().await;
    lat.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let mean = lat.iter().sum::<f64>() / lat.len() as f64;
    eprintln!(
        "output->frame latency over {} lines: mean {mean:.2} ms, p50 {:.2}, p95 {:.2}, max {:.2}",
        lat.len(),
        percentile(&lat, 0.5),
        percentile(&lat, 0.95),
        lat[lat.len() - 1]
    );

    let view = conn
        .attach_terminal(flood.clone(), observe())
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| s.frames > 0).await;
    let started = std::time::Instant::now();
    hc.hc(&["send", &flood, "go"]);
    until(&view, &mut screen, "the end of the flood", ends_the_flood).await;
    let took = started.elapsed();
    let stats = view.stats();
    eprintln!(
        "flood of 6.89 MB: shown in {took:?}; {} bytes reached the VT ({:.1} MB/s), {} frames",
        stats.bytes,
        stats.bytes as f64 / 1e6 / took.as_secs_f64(),
        stats.frames
    );
    conn.disconnect().await;
}
