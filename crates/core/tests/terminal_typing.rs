//! Seam 1: typing into a Terminal (input mode), against a real herdr-core
//! and the fake Agent's input record.

mod common;

use std::time::{Duration, Instant};

use aries_core::{
    AgentView, AttachMode, AttachOptions, HostConnection, KeyMods, ScreenFrame, TerminalExit,
    TerminalKey, TerminalUpdate, TerminalView,
};
use aries_test_support::{FakeAgent, HcSession};
use common::{core_in, local_endpoint};

fn typing(cols: u16, rows: u16) -> AttachOptions {
    AttachOptions {
        observe: false,
        cols,
        rows,
        history: 0,
        resumed: false,
    }
}

const NONE: KeyMods = KeyMods {
    ctrl: false,
    alt: false,
    shift: false,
};
const CTRL: KeyMods = KeyMods {
    ctrl: true,
    alt: false,
    shift: false,
};

/// Waits until the fake Agent has recorded `n` input lines.
fn inputs(agent: &FakeAgent, n: usize) -> Vec<String> {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let got = agent.inputs();
        if got.len() >= n {
            return got;
        }
        assert!(
            Instant::now() < deadline,
            "fake Agent recorded only {got:?}, wanted {n} lines"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn grid_of(hc: &HcSession, address: &str) -> (u64, u64) {
    let out = hc.hc(&["get", "--json", address]);
    let t: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
    (t["cols"].as_u64().unwrap(), t["rows"].as_u64().unwrap())
}

fn wait_grid(hc: &HcSession, address: &str, want: (u64, u64)) {
    let deadline = Instant::now() + Duration::from_secs(10);
    while grid_of(hc, address) != want {
        assert!(
            Instant::now() < deadline,
            "grid stayed {:?}, wanted {want:?}",
            grid_of(hc, address)
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// Rows of the grid built from the frames so far.
#[derive(Default)]
struct Screen {
    cols: u16,
    rows: Vec<String>,
}

impl Screen {
    fn apply(&mut self, f: &ScreenFrame) {
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

async fn until(
    view: &TerminalView,
    screen: &mut Screen,
    what: &str,
    pred: impl Fn(&Screen) -> bool,
) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    while !pred(screen) {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(left, view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { frame })) => screen.apply(&frame),
            Ok(Ok(TerminalUpdate::Exited { exit })) => {
                panic!(
                    "exited ({exit:?}) waiting for {what}; screen {:#?}",
                    screen.rows
                )
            }
            Ok(Err(e)) => panic!("view ended waiting for {what}: {e:?}"),
            Err(_) => panic!("timed out waiting for {what}; screen {:#?}", screen.rows),
        }
    }
}

async fn exit_of(view: &TerminalView, screen: &mut Screen) -> TerminalExit {
    loop {
        match tokio::time::timeout(Duration::from_secs(20), view.next()).await {
            Ok(Ok(TerminalUpdate::Screen { frame })) => screen.apply(&frame),
            Ok(Ok(TerminalUpdate::Exited { exit })) => return exit,
            Ok(Err(e)) => panic!("view ended without an exit: {e:?}"),
            Err(_) => panic!("timed out waiting for the exit; screen {:#?}", screen.rows),
        }
    }
}

struct Setup {
    _data: tempfile::TempDir,
    conn: std::sync::Arc<HostConnection>,
}

async fn connect(hc: &HcSession) -> Setup {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    Setup { _data: data, conn }
}

#[tokio::test]
async fn typed_text_and_enter_reach_the_agent() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "say ready\nwait\nwait\n");
    let s = connect(&hc).await;
    let view = s
        .conn
        .attach_terminal(agent.address.clone(), typing(60, 15))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "ready", |s| s.contains("ready")).await;

    view.send_text("hello world".into()).await.unwrap();
    view.send_key(TerminalKey::Enter, NONE).await.unwrap();
    // Unicode text goes through as UTF-8; backspace erases in the line editor.
    view.send_text("héllo ✓x".into()).await.unwrap();
    view.send_key(TerminalKey::Backspace, NONE).await.unwrap();
    view.send_key(TerminalKey::Enter, NONE).await.unwrap();

    assert_eq!(inputs(&agent, 2), vec!["hello world", "héllo ✓"]);
    // The Terminal echoes what was typed back to the screen.
    until(&view, &mut screen, "the echo", |s| {
        s.contains("hello world")
    })
    .await;
}

#[tokio::test]
async fn edited_multiline_paste_is_one_bracketed_block_then_follows_mode_off() {
    let hc = HcSession::start();
    // Raw input records the bytes without line discipline changing CR/LF.
    let agent = hc.run_fake_agent(
        "paste",
        "run stty raw -echo; printf '\\033[?2004hbracket-ready'\nkeys 24\nrun printf '\\033[?2004lplain-ready'\nkeys 5\n",
    );
    let s = connect(&hc).await;
    let view = s
        .conn
        .attach_terminal(agent.address.clone(), typing(60, 15))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "bracket-ready", |s| {
        s.contains("bracket-ready")
    })
    .await;

    view.paste_text("one\ntwo\r\n세\x1b[201~".into())
        .await
        .unwrap();
    assert_eq!(
        inputs(&agent, 1)[0],
        "keys 1b 5b 32 30 30 7e 6f 6e 65 0a 74 77 6f 0d 0a ec 84 b8 1b 5b 32 30 31 7e"
    );
    until(&view, &mut screen, "plain-ready", |s| {
        s.contains("plain-ready")
    })
    .await;
    view.paste_text("a\r\nb\nc".into()).await.unwrap();
    assert_eq!(inputs(&agent, 2)[1], "keys 61 0d 62 0d 63");
}

// Regression: the first paste must wait for the mode at the end of a split
// initial repaint, even when the caller has not pulled a screen frame yet.
#[tokio::test]
async fn first_paste_waits_for_the_complete_initial_modes() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent(
        "initial-paste",
        "run stty raw -echo; printf '\\033[?2004hbracket-ready'\nkeys 15\n",
    );
    // Establish that the program enabled paste mode before attaching.
    let setup = connect(&hc).await;
    let observer = setup
        .conn
        .attach_terminal(agent.address.clone(), observe_options())
        .await
        .unwrap();
    until(&observer, &mut Screen::default(), "bracket-ready", |s| {
        s.contains("bracket-ready")
    })
    .await;
    observer.detach().await;

    let data = tempfile::tempdir().unwrap();
    let held = data.path().join("held");
    let release = data.path().join("release");
    // A transparent bridge delays the real snapshot immediately before its
    // bracketed-paste mode, and splits the trailing mode marker across frames.
    let bridge = r#"
import os, struct, subprocess, sys, threading, time
p = subprocess.Popen(sys.argv[3:], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
def exact(stream, n):
    result = b''
    while len(result) < n:
        part = stream.read(n - len(result))
        if not part: raise EOFError()
        result += part
    return result
def output(channel, kind, data):
    sys.stdout.buffer.write(struct.pack('<IBI', channel, kind, len(data)) + data)
    sys.stdout.buffer.flush()
def incoming():
    try:
        while True:
            data = os.read(0, 65536)
            if not data: break
            p.stdin.write(data); p.stdin.flush()
    finally:
        p.terminate()
try:
    p.stdin.write(sys.stdin.buffer.readline()); p.stdin.flush()
    sys.stdout.buffer.write(p.stdout.readline()); sys.stdout.buffer.flush()
    threading.Thread(target=incoming, daemon=True).start()
    delayed = False
    while True:
        channel, kind, n = struct.unpack('<IBI', exact(p.stdout, 9))
        payload = exact(p.stdout, n)
        at = payload.rfind(b'\x1b[?2004h')
        if channel and kind == 0 and not delayed and at >= 0:
            delayed = True
            output(channel, kind, payload[:at])
            open(sys.argv[1], 'w').close()
            while not os.path.exists(sys.argv[2]): time.sleep(.005)
            marker = payload.index(b'\x1b[?9001', at)
            output(channel, kind, payload[at:marker + 7])
            output(channel, kind, payload[marker + 7:])
        else: output(channel, kind, payload)
except (EOFError, BrokenPipeError):
    pass
finally:
    p.terminate(); p.wait()
"#;
    let mut argv = vec![
        "python3".into(),
        "-c".into(),
        bridge.into(),
        held.to_string_lossy().into_owned(),
        release.to_string_lossy().into_owned(),
    ];
    argv.extend(hc.connect_argv());
    let core = core_in(data.path());
    let host = core
        .add_host(
            "delayed".into(),
            aries_core::Endpoint::Local {
                argv,
                env: hc.env().into_iter().collect(),
            },
        )
        .unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let view = conn
        .attach_terminal(agent.address.clone(), typing(60, 15))
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(10), async {
        while !held.exists() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("bridge withheld the initial modes");
    let mut paste = Box::pin(view.paste_text("a\nb".into()));
    assert!(
        tokio::time::timeout(Duration::from_millis(150), &mut paste)
            .await
            .is_err(),
        "paste must wait for the initial snapshot's modes"
    );
    std::fs::write(release, []).unwrap();
    tokio::time::timeout(Duration::from_secs(5), paste)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        inputs(&agent, 1),
        vec!["keys 1b 5b 32 30 30 7e 61 0a 62 1b 5b 32 30 31 7e"]
    );
    view.detach().await;
    conn.disconnect().await;
}

fn observe_options() -> AttachOptions {
    AttachOptions {
        observe: true,
        ..typing(60, 15)
    }
}

#[test]
fn the_default_attach_mode_is_observe_and_the_choice_is_kept() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    assert_eq!(core.attach_mode().unwrap(), AttachMode::Observe);
    core.set_attach_mode(AttachMode::Input).unwrap();
    drop(core);
    assert_eq!(
        core_in(data.path()).attach_mode().unwrap(),
        AttachMode::Input
    );
}

#[test]
fn the_default_agent_view_is_terminal_and_the_choice_is_kept() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    assert_eq!(core.agent_view().unwrap(), AgentView::Terminal);
    core.set_agent_view(AgentView::Chat).unwrap();
    core.set_attach_mode(AttachMode::Input).unwrap();
    drop(core);
    let core = core_in(data.path());
    assert_eq!(core.agent_view().unwrap(), AgentView::Chat);
    assert_eq!(core.attach_mode().unwrap(), AttachMode::Input);
}

#[tokio::test]
async fn key_bar_keys_reach_the_agent_as_terminal_sequences() {
    let hc = HcSession::start();
    // Line 1 in normal cursor mode; then the program asks for application
    // cursor keys (DECCKM) and line 2 must use SS3 arrows.
    let agent = hc.run_fake_agent("agent", "say ready\nwait\nappcursor\nsay app-mode\nwait\n");
    let s = connect(&hc).await;
    let view = s
        .conn
        .attach_terminal(agent.address.clone(), typing(60, 15))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "ready", |s| s.contains("ready")).await;

    for key in [
        TerminalKey::Escape,
        TerminalKey::Tab,
        TerminalKey::Up,
        TerminalKey::Down,
        TerminalKey::Right,
        TerminalKey::Left,
    ] {
        view.send_key(key, NONE).await.unwrap();
    }
    view.send_key(TerminalKey::Char { text: "a".into() }, CTRL)
        .await
        .unwrap();
    view.send_key(TerminalKey::Enter, NONE).await.unwrap();
    assert_eq!(inputs(&agent, 1)[0], "\x1b\t\x1b[A\x1b[B\x1b[C\x1b[D\x01");

    until(&view, &mut screen, "app-mode", |s| s.contains("app-mode")).await;
    view.send_key(TerminalKey::Up, NONE).await.unwrap();
    view.send_key(TerminalKey::Left, NONE).await.unwrap();
    view.send_key(TerminalKey::Enter, NONE).await.unwrap();
    assert_eq!(inputs(&agent, 2)[1], "\x1bOA\x1bOD");
}

#[tokio::test]
async fn extra_key_bar_keys_use_xterm_sequences_in_both_cursor_modes() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent(
        "extra-keys",
        "run stty raw -echo\nsay ready\nkeys 48\nappcursor\nsay app-mode\nkeys 14\n",
    );
    let s = connect(&hc).await;
    let view = s
        .conn
        .attach_terminal(agent.address.clone(), typing(60, 15))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "ready", |s| s.contains("ready")).await;

    for key in [
        TerminalKey::Home,
        TerminalKey::End,
        TerminalKey::PageUp,
        TerminalKey::PageDown,
    ] {
        view.send_key(key, NONE).await.unwrap();
    }
    let alt = KeyMods { alt: true, ..NONE };
    view.send_key(TerminalKey::Char { text: "x".into() }, alt)
        .await
        .unwrap();
    view.send_key(
        TerminalKey::Char {
            text: "😀".into()
        },
        alt,
    )
    .await
    .unwrap();
    view.send_key(
        TerminalKey::Char { text: "c".into() },
        KeyMods { ctrl: true, ..alt },
    )
    .await
    .unwrap();
    for key in [
        TerminalKey::Home,
        TerminalKey::End,
        TerminalKey::PageUp,
        TerminalKey::PageDown,
    ] {
        view.send_key(key, alt).await.unwrap();
    }
    view.send_key(TerminalKey::Enter, NONE).await.unwrap();
    assert_eq!(
        inputs(&agent, 1)[0],
        "keys 1b 5b 48 1b 5b 46 1b 5b 35 7e 1b 5b 36 7e 1b 78 1b f0 9f 98 80 1b 03 1b 5b 31 3b 33 48 1b 5b 31 3b 33 46 1b 5b 35 3b 33 7e 1b 5b 36 3b 33 7e 0d"
    );

    until(&view, &mut screen, "app-mode", |s| s.contains("app-mode")).await;
    for key in [
        TerminalKey::Home,
        TerminalKey::End,
        TerminalKey::PageUp,
        TerminalKey::PageDown,
    ] {
        view.send_key(key, NONE).await.unwrap();
    }
    assert_eq!(
        inputs(&agent, 2)[1],
        "keys 1b 4f 48 1b 4f 46 1b 5b 35 7e 1b 5b 36 7e"
    );
}

#[tokio::test]
async fn ctrl_c_interrupts_the_program() {
    let hc = HcSession::start();
    // Background test runners can pass down an ignored SIGINT, which a
    // noninteractive sh's `trap` cannot reset. Reset and block it before
    // announcing readiness, so sigwait receives even an already pending Ctrl-C.
    let address = hc.run_bg(
        "runaway",
        &[
            "python3",
            "-c",
            r#"import os, signal, sys
signal.signal(signal.SIGINT, signal.SIG_DFL)
signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGINT})
os.write(1, b'running\n')
signal.sigwait({signal.SIGINT})
os.write(1, b'interrupted\n')
sys.exit(7)
"#,
        ],
    );
    let s = connect(&hc).await;
    let view = s
        .conn
        .attach_terminal(address, typing(60, 15))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "running", |s| s.contains("running")).await;

    view.send_key(TerminalKey::Char { text: "c".into() }, CTRL)
        .await
        .unwrap();
    let exit = exit_of(&view, &mut screen).await;
    assert_eq!(exit.exit_code, Some(7), "{exit:?}");
    assert!(screen.contains("interrupted"));
}

#[tokio::test]
async fn an_input_view_takes_the_phones_size_and_follows_its_resizes() {
    let hc = HcSession::start();
    let address = hc.run_bg("shell", &["sh", "-c", "echo ready; sleep 300"]);
    std::thread::sleep(Duration::from_millis(300));
    assert_eq!(grid_of(&hc, &address), (200, 120), "hc's default grid");
    let s = connect(&hc).await;

    // Watching leaves the desktop's size alone and typing is refused.
    let watcher = s
        .conn
        .attach_terminal(
            address.clone(),
            AttachOptions {
                observe: true,
                ..typing(40, 10)
            },
        )
        .await
        .unwrap();
    assert!(watcher.observing());
    assert!(watcher.send_text("x".into()).await.is_err());
    assert!(watcher.paste_text("x\ny".into()).await.is_err());
    assert!(watcher.resize(40, 10).await.is_err());
    watcher.detach().await;
    assert_eq!(grid_of(&hc, &address), (200, 120));

    // Focus re-attaches as a typing viewer: from then on the phone decides.
    let view = s
        .conn
        .attach_terminal(address.clone(), typing(52, 20))
        .await
        .unwrap();
    assert!(!view.observing());
    wait_grid(&hc, &address, (52, 20));
    let mut screen = Screen::default();
    until(&view, &mut screen, "a 52x20 screen", |s| {
        s.cols == 52 && s.rows.len() == 20
    })
    .await;

    // The keyboard came up: fewer rows.
    view.resize(52, 11).await.unwrap();
    wait_grid(&hc, &address, (52, 11));
    until(&view, &mut screen, "a 52x11 screen", |s| {
        s.cols == 52 && s.rows.len() == 11
    })
    .await;
}

fn percentile(sorted: &[f64], p: f64) -> f64 {
    let i = ((sorted.len() as f64 - 1.0) * p).round() as usize;
    sorted[i]
}

/// Key -> echo on screen, core section only (local transport): from
/// `send_text` until the frame showing the echo comes out of `next`.
/// `cargo test --release -p aries-core --test terminal_typing -- --ignored --nocapture measure_key_to_frame`
#[tokio::test]
#[ignore]
async fn measure_key_to_frame_latency() {
    let hc = HcSession::start();
    // A cooked tty echoes each key at once, as a shell prompt does.
    let address = hc.run_bg("echo", &["sh", "-c", "echo ready; cat >/dev/null"]);
    let s = connect(&hc).await;
    let view = s
        .conn
        .attach_terminal(address, typing(80, 24))
        .await
        .unwrap();
    let mut screen = Screen::default();
    until(&view, &mut screen, "ready", |s| s.contains("ready")).await;

    let keys: Vec<char> = "abcdefghijklmnopqrstuvwxyz0123456789"
        .chars()
        .cycle()
        .take(200)
        .collect();
    let mut typed = String::new();
    let mut ms = Vec::new();
    for (i, c) in keys.iter().enumerate() {
        if i > 0 && i % 60 == 0 {
            view.send_key(TerminalKey::Enter, NONE).await.unwrap();
            typed.clear();
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        // Like a person typing: keys apart, not inside one frame interval.
        tokio::time::sleep(Duration::from_millis(30)).await;
        typed.push(*c);
        let t0 = Instant::now();
        view.send_text(c.to_string()).await.unwrap();
        let want = typed.clone();
        until(&view, &mut screen, "the echo", |s| {
            s.rows.iter().any(|r| r == &want)
        })
        .await;
        ms.push(t0.elapsed().as_secs_f64() * 1000.0);
    }
    let mean = ms.iter().sum::<f64>() / ms.len() as f64;
    ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
    println!(
        "key->frame latency over {} keys: mean {mean:.2} ms, p50 {:.2}, p95 {:.2}, max {:.2}",
        ms.len(),
        percentile(&ms, 0.5),
        percentile(&ms, 0.95),
        ms[ms.len() - 1]
    );
}
