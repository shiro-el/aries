//! `aries-beacon answer` as a black box: a fake Claude Code shows an
//! AskUserQuestion (OSC 7501 `blocked`/`question` plus the ticket-02
//! transcript up to the question) in a real, isolated herdr-core Terminal,
//! and records the raw keys that reach it.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::answer::{AnswerOutcome, AnswerReply};
use aries_test_support::{FakeAgent, HcSession};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");
const COLOR: &str = "toolu_012hzhteG39obz5TwRGEb26o";
const SIZE_TOPPINGS: &str = "toolu_01RAFRWAjh4oGAZDkHeY7V9E";

struct Host {
    hc: HcSession,
    _home: tempfile::TempDir,
    config: PathBuf,
    projects: PathBuf,
}

impl Host {
    fn new() -> Self {
        let hc = HcSession::start();
        let home = tempfile::tempdir().unwrap();
        let config = home.path().join(".claude");
        let projects = config.join("projects/-work-a");
        std::fs::create_dir_all(&projects).unwrap();
        Self {
            hc,
            _home: home,
            config,
            projects,
        }
    }

    /// A fake Claude Code in /work/a, session S1, running `steps` after
    /// registering itself.
    fn claude(&self, steps: &str) -> FakeAgent {
        let script = format!(
            "claude-session {} S1 /work/a\n{}",
            self.config.display(),
            steps.replace("$T", &self.projects.join("S1.jsonl").to_string_lossy())
        );
        let agent = self.hc.run_fake_agent("claude", &script);
        let registry = self.config.join("sessions");
        let deadline = Instant::now() + Duration::from_secs(10);
        while std::fs::read_dir(&registry).map_or(true, |mut d| d.next().is_none()) {
            assert!(Instant::now() < deadline, "no registry entry");
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

    fn answer(&self, terminal: &str, args: &[&str]) -> (AnswerReply, bool) {
        let mut cmd = Command::new(BEACON);
        cmd.arg("answer").arg(terminal).args(args).env_clear();
        cmd.envs(self.hc.env());
        let bin_dir = self.hc.bin().parent().unwrap();
        cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
        cmd.env("HOME", self.config.parent().unwrap());
        cmd.env("CLAUDE_CONFIG_DIR", &self.config);
        let out = cmd.stdin(Stdio::null()).output().unwrap();
        let text = String::from_utf8(out.stdout).unwrap();
        let line = text.lines().last().unwrap_or_default();
        let reply = AnswerReply::parse(line).unwrap_or_else(|e| panic!("{text:?}: {e}"));
        (reply, out.status.success())
    }
}

/// The Agent's input lines, once it has `n`.
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
fn a_single_question_is_answered_with_its_option_key_and_only_once() {
    let host = Host::new();
    let agent = host.claude(
        "lines claude-code/main-session.jsonl 1 65 $T\n\
         status blocked question answer: Which color do you prefer? (Red · Blue · Green)\n\
         keys 1\n\
         lines claude-code/main-session.jsonl 66 125 $T\n\
         status working\n",
    );
    host.wait_status(&agent, "blocked");

    // Blue is option 1 (0-based): Claude Code selects and submits on "2".
    let (reply, ok) = host.answer(&agent.address, &[COLOR, "1"]);
    assert_eq!(reply.outcome, AnswerOutcome::Sent, "{reply:?}");
    assert!(ok);
    assert_eq!(input(&agent, 1), ["keys 32"]);

    // The Agent took it: the transcript has the answer.
    host.wait_status(&agent, "working");
    let deadline = Instant::now() + Duration::from_secs(10);
    let again = loop {
        let (reply, ok) = host.answer(&agent.address, &[COLOR, "2"]);
        if reply.outcome != AnswerOutcome::Gone || Instant::now() > deadline {
            break (reply, ok);
        }
        std::thread::sleep(Duration::from_millis(50));
    };
    assert_eq!(again.0.outcome, AnswerOutcome::Answered, "{again:?}");
    assert!(!again.1);
    std::thread::sleep(Duration::from_millis(300));
    assert_eq!(agent.inputs(), ["keys 32"]);
}

#[test]
fn several_questions_with_multi_select_press_each_answer_then_submit() {
    let host = Host::new();
    let agent = host.claude(
        "lines claude-code/concurrent-same-cwd-multi-question.jsonl 1 48 $T\n\
         status blocked question answer: Which size? (S · M)\n\
         keys 5\n",
    );
    host.wait_status(&agent, "blocked");

    // Size: M (auto-advances). Toppings: toggle Cheese and Olive, tab to
    // the review screen. Then "1" = Submit answers.
    let (reply, ok) = host.answer(&agent.address, &[SIZE_TOPPINGS, "1", "0,2"]);
    assert_eq!(reply.outcome, AnswerOutcome::Sent, "{reply:?}");
    assert!(ok);
    assert_eq!(input(&agent, 1), ["keys 32 31 33 09 31"]);
}

#[test]
fn nothing_is_pressed_for_a_question_that_is_not_on_screen_or_bad_picks() {
    let host = Host::new();
    let agent = host.claude(
        "lines claude-code/main-session.jsonl 1 65 $T\n\
         status working\n\
         wait\n\
         status blocked question answer: Which color do you prefer?\n",
    );
    host.wait_status(&agent, "working");

    // In the transcript but the Agent is not asking (interrupted, or the
    // Terminal moved on): not answerable.
    let (reply, ok) = host.answer(&agent.address, &[COLOR, "1"]);
    assert_eq!(reply.outcome, AnswerOutcome::Gone, "{reply:?}");
    assert!(!ok);

    host.hc.hc(&["send", &agent.address, "go"]);
    host.wait_status(&agent, "blocked");
    for (args, want) in [
        (&["toolu_unknown", "0"][..], AnswerOutcome::Gone),
        (&[COLOR, "3"][..], AnswerOutcome::Invalid),
        (&[COLOR, "0,1"][..], AnswerOutcome::Invalid),
        (&[COLOR, "0", "1"][..], AnswerOutcome::Invalid),
    ] {
        let (reply, ok) = host.answer(&agent.address, args);
        assert_eq!(reply.outcome, want, "{args:?}: {reply:?}");
        assert!(!ok);
    }
    let (reply, _) = host.answer("t999", &[COLOR, "0"]);
    assert_eq!(reply.outcome, AnswerOutcome::NoTerminal);
    let plain = host.hc.run_bg("plain", &["/bin/sh", "-c", "sleep 30"]);
    let (reply, _) = host.answer(&plain, &[COLOR, "0"]);
    assert_eq!(reply.outcome, AnswerOutcome::NoAgent);

    std::thread::sleep(Duration::from_millis(300));
    assert_eq!(agent.inputs(), ["go"]);
}
