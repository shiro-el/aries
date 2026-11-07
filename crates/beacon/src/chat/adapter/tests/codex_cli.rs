//! `aries-beacon chat` and `answer` as a black box with a fake Codex: a
//! process in a real, isolated herdr-core Terminal that holds a ticket-03
//! rollout open and reports OSC 7501 status the way Codex does.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::answer::{AnswerOutcome, AnswerReply};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatLine, ToolStatus};
use aries_test_support::{FakeAgent, HcSession};

use super::support::BEACON;
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
    // Metadata arrives as upserts of the same reply, not new messages.
    let agents: std::collections::BTreeMap<&str, &str> = lines
        .iter()
        .filter_map(|l| match &l.event {
            ChatEvent::Item { item } => match &item.body {
                ChatBody::Agent { text, .. } => Some((item.id.as_str(), text.as_str())),
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
fn backtracking_restores_the_retained_history_before_the_replacement_turn() {
    let mut host = Host::new();
    let fixtures = aries_test_support::repo_root().join("fixtures/agents/codex/history-base");
    let day = host.home.path().join(".codex/sessions/2026/10/29");
    std::fs::create_dir_all(&day).unwrap();
    for entry in std::fs::read_dir(fixtures).unwrap().flatten() {
        let name = entry.file_name();
        if name.to_string_lossy().ends_with(".jsonl") {
            std::fs::copy(entry.path(), day.join(&name)).unwrap();
            if name.to_string_lossy().contains('_') {
                host.rollout = day.join(name);
            }
        }
    }
    let agent = host.codex("status done\n");
    host.wait_status(&agent, "done");
    let (text, ok) = host.beacon(&["chat", &agent.address]);
    assert!(ok, "{text}");
    let lines: Vec<ChatLine> = text.lines().map(|l| ChatLine::parse(l).unwrap()).collect();
    let prompts: Vec<&str> = lines
        .iter()
        .filter_map(|line| match &line.event {
            ChatEvent::Item { item } => match &item.body {
                ChatBody::User { text, .. } => Some(text.as_str()),
                _ => None,
            },
            _ => None,
        })
        .collect();
    assert_eq!(
        prompts,
        [
            "Retained prompt 1",
            "Retained prompt 2",
            "Retained prompt 3",
            "Retained prompt 4",
            "Replacement prompt 1"
        ]
    );
    assert!(matches!(&lines[0].event, ChatEvent::Reset { session, .. }
        if session == "00000000-0000-4000-8000-000000000100"));
    let cursor = lines.last().unwrap().cursor.as_deref().unwrap();
    let (resumed, ok) = host.beacon(&["chat", &agent.address, "--cursor", cursor]);
    assert!(ok, "{resumed}");
    assert!(
        !resumed
            .lines()
            .map(|l| ChatLine::parse(l).unwrap())
            .any(|l| matches!(l.event, ChatEvent::Item { .. } | ChatEvent::Reset { .. })),
        "{resumed}"
    );
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

// D27: opening a collapsed menu must not consume the one durable answer attempt.
#[test]
fn a_delayed_collapsed_menu_can_be_retried_without_claim_rejection() {
    use super::support::{TerminalFacts, TerminalInput, TerminalLookup, codex::Codex};
    struct TestCodex(PathBuf);
    impl super::support::Agent for TestCodex {
        fn name(&self) -> &'static str {
            "codex"
        }
        fn locate(&self, _: &TerminalFacts) -> Option<super::support::Located> {
            Some(super::support::Located {
                session: THREAD.into(),
                path: self.0.clone(),
            })
        }
        fn interpreter(&self) -> Box<dyn super::support::Interpreter> {
            super::support::Agent::interpreter(&Codex)
        }
        fn current_question<'a>(
            &self,
            items: &'a [aries_protocol::chat::ChatItem],
        ) -> Option<(&'a str, &'a aries_protocol::chat::ChatQuestion)> {
            super::support::Agent::current_question(&Codex, items)
        }
        fn prepare(
            &self,
            nav: &mut super::super::delivery::Navigation<'_>,
            question: &aries_protocol::chat::ChatQuestion,
            has_text: bool,
        ) -> Result<(), aries_protocol::answer::AnswerReply> {
            super::support::Agent::prepare(&Codex, nav, question, has_text)
        }
        fn answer_input(
            &self,
            q: &aries_protocol::chat::ChatQuestion,
            picks: &[Vec<usize>],
            texts: &[Option<String>],
        ) -> Result<Vec<super::support::answer::AnswerInput>, super::support::KeysError> {
            super::support::Agent::answer_input(&Codex, q, picks, texts)
        }
    }
    struct Link {
        facts: TerminalFacts,
        opened: bool,
        inputs: Vec<Vec<String>>,
    }
    impl TerminalLookup for Link {
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            Ok(Some(self.facts.clone()))
        }
        fn screen(&mut self, _: &str) -> Result<String, String> {
            Ok(if self.opened {
                "Which color do you prefer?\n› 1. Red\n  2. Blue"
            } else {
                "shift+left to answer"
            }
            .into())
        }
    }
    impl TerminalInput for Link {
        fn keys(&mut self, _: &str, keys: &[String]) -> Result<(), String> {
            self.inputs.push(keys.to_vec());
            Ok(())
        }
    }
    let host = Host::new();
    let agent = host.codex(&format!(
        "lines {FIXTURE} 1 14 $T\nstatus blocked question\nwait\n"
    ));
    host.wait_status(&agent, "blocked");
    let mut link = Link {
        facts: TerminalFacts::from_json(&host.hc.rpc(
            "terminal.get",
            serde_json::json!({"terminal": agent.address}),
        )),
        opened: false,
        inputs: vec![],
    };
    let agents: Vec<Box<dyn super::support::Agent>> =
        vec![Box::new(TestCodex(host.rollout.clone()))];
    let state = host.home.path().join("answers-test");
    let request =
        aries_protocol::answer::Answer::from_args(&[QUESTION.into(), "1".into()]).unwrap();
    let first =
        super::support::answer::answer(&state, &agent.address, &request, &mut link, &agents)
            .unwrap();
    assert_eq!(first.outcome, AnswerOutcome::Gone, "{first:?}");
    link.opened = true;
    let second =
        super::support::answer::answer(&state, &agent.address, &request, &mut link, &agents)
            .unwrap();
    assert_eq!(second.outcome, AnswerOutcome::Sent, "{second:?}");
    assert_eq!(link.inputs, vec![vec!["shift+left"], vec!["down", "enter"]]);
    let third =
        super::support::answer::answer(&state, &agent.address, &request, &mut link, &agents)
            .unwrap();
    assert_eq!(third.outcome, AnswerOutcome::Failed);
    assert_eq!(link.inputs.len(), 2);
}
