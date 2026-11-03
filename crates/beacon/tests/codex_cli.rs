//! `aries-beacon chat` and `answer` as a black box with a fake Codex: a
//! process in a real, isolated herdr-core Terminal that holds a ticket-03
//! rollout open and reports OSC 7501 status the way Codex does.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::answer::{AnswerOutcome, AnswerReply};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatLine, ToolStatus};
use aries_test_support::{FakeAgent, HcSession};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");
const THREAD: &str = "01a1a012-3af0-7c01-97e4-7e2adbc2ae4f";
const QUESTION: &str = "call_f2e4624a40ee4bd7ba6cc92a8b052c06";
const FIXTURE: &str = "codex/sessions/question-then-resume.rollout.jsonl";

struct Host {
    hc: HcSession,
    home: tempfile::TempDir,
    rollout: PathBuf,
}

impl Host {
    fn new() -> Self {
        let home = tempfile::tempdir().unwrap();
        let rollout = home.path().join(format!(
            ".codex/sessions/2026/11/03/rollout-2026-11-03T15-21-51-{THREAD}.jsonl"
        ));
        Self {
            hc: HcSession::start(),
            home,
            rollout,
        }
    }

    /// A fake Codex holding its rollout open, then running `steps`.
    fn codex(&self, steps: &str) -> FakeAgent {
        let r = self.rollout.to_string_lossy();
        let script = format!("hold {r}\n{}", steps.replace("$T", &r));
        let agent = self.hc.run_fake_agent("codex", &script);
        let deadline = Instant::now() + Duration::from_secs(10);
        while !self.rollout.exists() {
            assert!(Instant::now() < deadline, "no rollout");
            std::thread::sleep(Duration::from_millis(50));
        }
        agent
    }

    fn wait_status(&self, agent: &FakeAgent, state: &str) {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let get: serde_json::Value =
                serde_json::from_str(&self.hc.hc(&["get", &agent.address, "--json"])).unwrap();
            if get["status"]["state"] == state {
                return;
            }
            assert!(Instant::now() < deadline, "never {state}: {get}");
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    fn beacon(&self, args: &[&str]) -> (String, bool) {
        let mut cmd = Command::new(BEACON);
        cmd.args(args).env_clear();
        cmd.envs(self.hc.env());
        let bin_dir = self.hc.bin().parent().unwrap();
        cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
        cmd.env("HOME", self.home.path());
        // No Claude Code here.
        cmd.env("CLAUDE_CONFIG_DIR", self.home.path().join(".claude"));
        let out = cmd.stdin(Stdio::null()).output().unwrap();
        (String::from_utf8(out.stdout).unwrap(), out.status.success())
    }

    fn answer(&self, terminal: &str, picks: &[&str]) -> (AnswerReply, bool) {
        let mut args = vec!["answer", terminal, QUESTION];
        args.extend(picks);
        let (text, ok) = self.beacon(&args);
        let line = text.lines().last().unwrap_or_default();
        (
            AnswerReply::parse(line).unwrap_or_else(|e| panic!("{text:?}: {e}")),
            ok,
        )
    }
}

fn input(agent: &FakeAgent, n: usize) -> Vec<String> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let lines = agent.inputs();
        if lines.len() >= n {
            return lines;
        }
        assert!(Instant::now() < deadline, "input so far: {lines:?}");
        std::thread::sleep(Duration::from_millis(50));
    }
}

#[test]
fn the_chat_of_a_codex_terminal_is_its_open_rollout() {
    let host = Host::new();
    let agent = host.codex(&format!("transcript {FIXTURE} $T\nstatus done\n"));
    host.wait_status(&agent, "done");
    let (text, ok) = host.beacon(&["chat", &agent.address]);
    assert!(ok, "{text}");
    let lines: Vec<ChatLine> = text.lines().map(|l| ChatLine::parse(l).unwrap()).collect();
    assert!(
        matches!(&lines[0].event, ChatEvent::Reset { agent, session } if agent == "codex" && session == THREAD),
        "{text}"
    );
    let agents: Vec<&str> = lines
        .iter()
        .filter_map(|l| match &l.event {
            ChatEvent::Item { item } => match &item.body {
                ChatBody::Agent { text } => Some(text.as_str()),
                _ => None,
            },
            _ => None,
        })
        .collect();
    assert_eq!(agents.len(), 2, "{text}");
    let cursor = lines.last().unwrap().cursor.as_deref().unwrap();
    assert!(cursor.starts_with(&format!("codex:{THREAD}:")), "{cursor}");
}

#[test]
fn a_codex_question_is_answered_with_down_and_enter_and_only_once() {
    let host = Host::new();
    // The rollout up to the open question (ordinal 13), blocked on it;
    // after 4 key bytes (down = ESC [ B, enter = CR) the answer follows.
    let agent = host.codex(&format!(
        "lines {FIXTURE} 1 14 $T\n\
         status blocked question\n\
         keys 4\n\
         lines {FIXTURE} 15 37 $T\n\
         status done\n"
    ));
    host.wait_status(&agent, "blocked");

    // Bad picks press nothing.
    let (reply, ok) = host.answer(&agent.address, &["2"]);
    assert_eq!(reply.outcome, AnswerOutcome::Invalid, "{reply:?}");
    assert!(!ok);

    // Blue is option 1 (0-based).
    let (reply, ok) = host.answer(&agent.address, &["1"]);
    assert_eq!(reply.outcome, AnswerOutcome::Sent, "{reply:?}");
    assert!(ok);
    assert_eq!(input(&agent, 1), ["keys 1b 5b 42 0d"]);

    host.wait_status(&agent, "done");
    let (reply, ok) = host.answer(&agent.address, &["0"]);
    assert_eq!(reply.outcome, AnswerOutcome::Answered, "{reply:?}");
    assert!(!ok);
    std::thread::sleep(Duration::from_millis(300));
    assert_eq!(agent.inputs(), ["keys 1b 5b 42 0d"]);

    // The Chat shows the question answered.
    let (text, _) = host.beacon(&["chat", &agent.address]);
    let question = text
        .lines()
        .rev()
        .find_map(|l| match ChatLine::parse(l).unwrap().event {
            ChatEvent::Item { item } if item.id == QUESTION => Some(item.body),
            _ => None,
        })
        .unwrap();
    let ChatBody::Tool { status, output, .. } = question else {
        panic!()
    };
    assert_eq!(
        (status, output.as_deref()),
        (ToolStatus::Done, Some("Blue"))
    );
}
