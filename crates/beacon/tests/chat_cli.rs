//! `aries-beacon chat` as a black box: a fake Claude Code in a real
//! (isolated) herdr-core Terminal, paired through the session registry.

use std::io::{BufRead, BufReader};
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::time::Duration;

use aries_protocol::chat::{ChatBody, ChatEvent, ChatLine, ChatState};
use aries_test_support::HcSession;

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

fn beacon(hc: &HcSession, config: &Path, args: &[&str]) -> Command {
    let mut cmd = Command::new(BEACON);
    cmd.arg("chat").args(args).env_clear();
    cmd.envs(hc.env());
    let bin_dir = hc.bin().parent().unwrap();
    cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
    cmd.env("HOME", config.parent().unwrap());
    cmd.env("CLAUDE_CONFIG_DIR", config);
    cmd.stdin(Stdio::null());
    cmd
}

struct Follow {
    child: Child,
    lines: mpsc::Receiver<ChatLine>,
}

impl Follow {
    fn start(mut cmd: Command) -> Self {
        let mut child = cmd.stdout(Stdio::piped()).spawn().unwrap();
        let stdout = child.stdout.take().unwrap();
        let (tx, lines) = mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                let Ok(line) = line else { break };
                let parsed = ChatLine::parse(&line).unwrap_or_else(|e| panic!("{line}: {e:?}"));
                if tx.send(parsed).is_err() {
                    break;
                }
            }
        });
        Self { child, lines }
    }

    /// Lines up to and including the first `state` line equal to `want`.
    fn until(&self, want: ChatState) -> Vec<ChatLine> {
        let mut seen = Vec::new();
        loop {
            let line = self
                .lines
                .recv_timeout(Duration::from_secs(15))
                .unwrap_or_else(|_| panic!("no {want:?} state; got {seen:#?}"));
            let done = matches!(line.event, ChatEvent::State { state } if state == want);
            seen.push(line);
            if done {
                return seen;
            }
        }
    }
}

impl Drop for Follow {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn user_texts(lines: &[ChatLine]) -> Vec<String> {
    lines
        .iter()
        .filter_map(|l| match &l.event {
            ChatEvent::Item { item } => match &item.body {
                ChatBody::User { text } => Some(text.clone()),
                _ => None,
            },
            _ => None,
        })
        .collect()
}

fn reset_session(lines: &[ChatLine]) -> Option<String> {
    lines.iter().find_map(|l| match &l.event {
        ChatEvent::Reset { agent, session } => {
            assert_eq!(agent, "claude-code");
            Some(session.clone())
        }
        _ => None,
    })
}

#[test]
fn follows_a_claude_code_terminal_across_clear() {
    let hc = HcSession::start();
    let home = tempfile::tempdir().unwrap();
    let config = home.path().join(".claude");
    let projects = config.join("projects/-work-a");
    std::fs::create_dir_all(&projects).unwrap();
    let c = config.display();
    let p = projects.display();
    let agent = hc.run_fake_agent(
        "claude",
        &format!(
            "claude-session {c} S1 /work/a\n\
             wait\n\
             transcript claude-code/main-session.jsonl {p}/S1.jsonl\n\
             wait\n\
             claude-session {c} S2 /work/a\n\
             wait\n\
             transcript claude-code/after-clear-then-resumed.jsonl {p}/S2.jsonl\n"
        ),
    );
    let registry = config.join("sessions");
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while std::fs::read_dir(&registry).map_or(true, |mut d| d.next().is_none()) {
        assert!(std::time::Instant::now() < deadline, "no registry entry");
        std::thread::sleep(Duration::from_millis(50));
    }

    let follow = Follow::start(beacon(&hc, &config, &[&agent.address, "--follow"]));
    let first = follow.until(ChatState::Waiting);
    assert_eq!(reset_session(&first).as_deref(), Some("S1"));

    hc.hc(&["send", &agent.address, "go"]);
    let chat = follow.until(ChatState::Live);
    let users = user_texts(&chat);
    assert_eq!(users.first().map(String::as_str), Some("Reply with exactly: pong"));
    assert_eq!(users.len(), 5, "{users:#?}");
    let cursor = chat.iter().rev().find_map(|l| l.cursor.clone()).unwrap();
    assert!(cursor.starts_with("claude-code:S1:"), "{cursor}");

    // /clear: a new session id, its file only with the first message.
    hc.hc(&["send", &agent.address, "clear"]);
    let cleared = follow.until(ChatState::Waiting);
    assert_eq!(reset_session(&cleared).as_deref(), Some("S2"));
    hc.hc(&["send", &agent.address, "go"]);
    let after = follow.until(ChatState::Live);
    assert_eq!(
        user_texts(&after)[..2],
        ["/clear".to_string(), "Reply with exactly: after-clear".to_string()]
    );

    // The Agent exits: its Chat stays, marked ended.
    hc.hc(&["kill", &agent.address]);
    follow.until(ChatState::Ended);
}

#[test]
fn resumes_from_a_cursor_without_repeating() {
    let hc = HcSession::start();
    let home = tempfile::tempdir().unwrap();
    let config = home.path().join(".claude");
    let projects = config.join("projects/-work-b");
    std::fs::create_dir_all(&projects).unwrap();
    let agent = hc.run_fake_agent(
        "claude",
        &format!(
            "claude-session {} S1 /work/b\n\
             transcript claude-code/api-error-bad-model.jsonl {}/S1.jsonl\n",
            config.display(),
            projects.display()
        ),
    );
    let once = |cursor: Option<&str>| {
        let mut args = vec![agent.address.as_str()];
        if let Some(c) = cursor {
            args.extend(["--cursor", c]);
        }
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        loop {
            let out = beacon(&hc, &config, &args).output().unwrap();
            assert!(out.status.success(), "{out:?}");
            let lines: Vec<ChatLine> = String::from_utf8(out.stdout)
                .unwrap()
                .lines()
                .map(|l| ChatLine::parse(l).unwrap())
                .collect();
            let live = lines
                .iter()
                .any(|l| matches!(l.event, ChatEvent::State { state: ChatState::Live }));
            if live {
                return lines;
            }
            assert!(std::time::Instant::now() < deadline, "never live: {lines:#?}");
            std::thread::sleep(Duration::from_millis(100));
        }
    };
    let first = once(None);
    assert_eq!(user_texts(&first), ["hi"]);
    let cursor = first.iter().rev().find_map(|l| l.cursor.clone()).unwrap();
    let again = once(Some(&cursor));
    assert!(
        !again
            .iter()
            .any(|l| matches!(l.event, ChatEvent::Item { .. } | ChatEvent::Reset { .. })),
        "{again:#?}"
    );
}

#[test]
fn an_unknown_terminal_is_reported_and_ends_the_run() {
    let hc = HcSession::start();
    let home = tempfile::tempdir().unwrap();
    let out = beacon(&hc, &home.path().join(".claude"), &["nope", "--follow"])
        .output()
        .unwrap();
    assert!(out.status.success(), "{out:?}");
    let text = String::from_utf8(out.stdout).unwrap();
    assert_eq!(
        text.lines().map(|l| ChatLine::parse(l).unwrap().event).collect::<Vec<_>>(),
        [ChatEvent::State {
            state: ChatState::NoTerminal
        }]
    );
}

#[test]
fn a_plain_terminal_has_no_agent() {
    let hc = HcSession::start();
    let home = tempfile::tempdir().unwrap();
    let t = hc.run_bg("plain", &["/bin/sh", "-c", "sleep 30"]);
    let out = beacon(&hc, &home.path().join(".claude"), &[&t]).output().unwrap();
    let text = String::from_utf8(out.stdout).unwrap();
    assert_eq!(
        text.lines().map(|l| ChatLine::parse(l).unwrap().event).collect::<Vec<_>>(),
        [ChatEvent::State {
            state: ChatState::NoAgent
        }]
    );
}
