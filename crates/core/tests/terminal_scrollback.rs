//! Seam 1: reading a Terminal's scrollback and links in observe mode,
//! against a real herdr-core.

mod common;

use std::time::Duration;

use aries_core::{AttachOptions, CellRun, ScreenFrame, TerminalUpdate, TerminalView};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

/// What a UI would draw: the grid built from the frames received so far.
#[derive(Default)]
struct Screen {
    cols: u16,
    rows: Vec<String>,
    runs: Vec<Vec<CellRun>>,
    scrolled: u32,
    history: u32,
}

impl Screen {
    fn apply(&mut self, f: &ScreenFrame) {
        if f.full || f.cols != self.cols || f.rows as usize != self.rows.len() {
            self.cols = f.cols;
            self.rows = vec![String::new(); f.rows as usize];
            self.runs = vec![Vec::new(); f.rows as usize];
        }
        self.scrolled = f.scrolled;
        self.history = f.history;
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
            self.runs[line.row as usize] = line.runs.clone();
        }
    }

    fn contains(&self, needle: &str) -> bool {
        self.rows.iter().any(|r| r.contains(needle))
    }
}

async fn until(view: &TerminalView, screen: &mut Screen, what: &str, pred: impl Fn(&Screen) -> bool) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
    while !pred(screen) {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(left, view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { frame })) => screen.apply(&frame),
            Ok(Ok(other)) => panic!("{other:?} while waiting for {what}"),
            Ok(Err(e)) => panic!("view ended while waiting for {what}: {e:?}"),
            Err(_) => panic!(
                "timed out waiting for {what}; scrolled {} of {}; screen: {:#?}",
                screen.scrolled, screen.history, screen.rows
            ),
        }
    }
}

fn observe(history: u32) -> AttachOptions {
    AttachOptions {
        observe: true,
        cols: 40,
        rows: 10,
        history,
    }
}

/// The oldest output line is at the top (below the paint's blank line).
fn shows_the_first_line(s: &Screen) -> bool {
    s.rows.iter().take(2).any(|r| r == "1")
}

fn grid_of(hc: &HcSession, address: &str) -> (u64, u64) {
    let out = hc.hc(&["get", "--json", address]);
    let t: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
    (t["cols"].as_u64().unwrap(), t["rows"].as_u64().unwrap())
}

/// Prints 400 numbered lines (more than the 120-row screen), then 50 more
/// when sent a line.
const NUMBERS: &[&str] = &[
    "sh",
    "-c",
    "seq 1 400; echo END; read go; seq 401 450; echo MORE; sleep 300",
];

#[tokio::test]
async fn attaching_with_history_lets_you_scroll_back_to_the_first_line() {
    let hc = HcSession::start();
    let address = hc.run_bg("numbers", NUMBERS);
    std::thread::sleep(Duration::from_millis(300));
    let desktop = grid_of(&hc, &address);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();

    // Without history the first paint is only the screen.
    let bare = conn.attach_terminal(address.clone(), observe(0)).await.unwrap();
    let mut screen = Screen::default();
    until(&bare, &mut screen, "the first paint", |s| s.contains("END")).await;
    // (herdr-core's paint may push a blank line or so into the scrollback.)
    assert!(screen.history < 10, "no scrollback was asked for: {}", screen.history);
    bare.detach().await;

    let view = conn
        .attach_terminal(address.clone(), observe(1000))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| s.contains("END")).await;
    assert!(screen.history >= 250, "scrollback came with the paint: {}", screen.history);
    assert_eq!(screen.scrolled, 0, "starts at the live bottom");

    // Asking for more than there is stops at the oldest line.
    let top = view.scroll_to(100_000);
    assert_eq!(top, screen.history);
    until(&view, &mut screen, "the oldest line on top", |s| {
        shows_the_first_line(s) && s.scrolled == top
    })
    .await;
    assert!(!screen.contains("END"));

    // Back to live.
    assert_eq!(view.scroll_to(0), 0);
    until(&view, &mut screen, "the live screen", |s| {
        s.contains("END") && s.scrolled == 0
    })
    .await;

    assert_eq!(grid_of(&hc, &address), desktop, "scrolling must not resize");
    conn.disconnect().await;
}

#[tokio::test]
async fn a_scrolled_back_view_stays_on_what_you_read_while_output_arrives() {
    let hc = HcSession::start();
    let address = hc.run_bg("numbers", NUMBERS);
    std::thread::sleep(Duration::from_millis(300));
    let desktop = grid_of(&hc, &address);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(address.clone(), observe(1000))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| s.contains("END")).await;

    let at = view.scroll_to(100_000);
    until(&view, &mut screen, "the oldest line on top", |s| {
        shows_the_first_line(s)
    })
    .await;

    hc.hc(&["send", &address, "go"]);
    until(&view, &mut screen, "the view to account for new lines", |s| {
        s.history > at
    })
    .await;
    assert!(shows_the_first_line(&screen), "the view did not jump to the new output");
    assert_eq!(screen.scrolled, screen.history, "still at the oldest line");

    view.scroll_to(0);
    until(&view, &mut screen, "the new output", |s| s.contains("MORE")).await;
    assert_eq!(grid_of(&hc, &address), desktop);
    conn.disconnect().await;
}

#[tokio::test]
async fn hyperlinks_carry_their_target() {
    let hc = HcSession::start();
    let address = hc.run_bg(
        "links",
        &[
            "sh",
            "-c",
            "echo ready; read go; printf 'see \\033]8;;https://example.com/run/1\\033\\\\the run\\033]8;;\\033\\\\ now\\n'; sleep 300",
        ],
    );
    std::thread::sleep(Duration::from_millis(300));

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn.attach_terminal(address.clone(), observe(0)).await.unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "the first paint", |s| s.contains("ready")).await;
    hc.hc(&["send", &address, "go"]);
    until(&view, &mut screen, "the link", |s| s.contains("see the run now")).await;

    let row = screen.rows.iter().position(|r| r.contains("see the run")).unwrap();
    let links: Vec<(&str, Option<&str>)> = screen.runs[row]
        .iter()
        .map(|r| (r.text.as_str(), r.link.as_deref()))
        .collect();
    assert_eq!(
        links,
        vec![
            ("see", None),
            ("the run", Some("https://example.com/run/1")),
            (" now", None)
        ]
    );
    conn.disconnect().await;
}
