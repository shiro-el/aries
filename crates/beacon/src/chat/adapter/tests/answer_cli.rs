//! `aries-beacon answer` as a black box: a fake Claude Code shows an
//! AskUserQuestion (OSC 7501 `blocked`/`question` plus the ticket-02
//! transcript up to the question) in a real, isolated herdr-core Terminal,
//! and records the raw keys that reach it.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::answer::{AnswerOutcome, AnswerReply};
use aries_test_support::{FakeAgent, HcSession};

use super::support::BEACON;
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

    fn command(&self, command: &str, terminal: &str, args: &[&str]) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.arg(command).arg(terminal).args(args).env_clear();
        cmd.envs(self.hc.env());
        let bin_dir = self.hc.bin().parent().unwrap();
        cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
        cmd.env("HOME", self.config.parent().unwrap());
        cmd.env("CLAUDE_CONFIG_DIR", &self.config);
        cmd.stdin(Stdio::null());
        cmd
    }

    fn answer(&self, terminal: &str, args: &[&str]) -> (AnswerReply, bool) {
        let out = self.command("answer", terminal, args).output().unwrap();
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

/// Without this, overlapping phone/watch commands can both send while the
/// Agent has not yet updated its transcript or Blocked status.
#[test]
fn overlapping_answers_claim_the_question_before_the_agent_updates() {
    for permission in [false, true] {
        let host = Host::new();
        let screen = include_str!("../../../../../../fixtures/agents/permissions/claude-write.txt")
            .lines()
            .map(|line| format!("say {line}\n"))
            .collect::<String>();
        let steps = if permission {
            format!("{screen}status blocked permission\nkeys 1\nkeys 1\n")
        } else {
            "lines claude-code/main-session.jsonl 1 65 $T\nstatus blocked question answer: Which color do you prefer?\nkeys 1\nkeys 1\n".into()
        };
        let agent = host.claude(&steps);
        host.wait_status(&agent, "blocked");
        let question = if permission {
            use aries_protocol::chat::{ChatEvent, ChatLine};
            let out = host.command("chat", &agent.address, &[]).output().unwrap();
            String::from_utf8(out.stdout)
                .unwrap()
                .lines()
                .find_map(|line| match ChatLine::parse(line).unwrap().event {
                    ChatEvent::Item { item } if item.id.starts_with("permission:") => Some(item.id),
                    _ => None,
                })
                .expect("live permission question")
        } else {
            COLOR.into()
        };
        let replies = std::thread::scope(|scope| {
            let first = scope.spawn(|| host.answer(&agent.address, &[&question, "1"]));
            let second = scope.spawn(|| host.answer(&agent.address, &[&question, "2"]));
            [first.join().unwrap(), second.join().unwrap()]
        });
        assert_eq!(
            replies
                .iter()
                .filter(|(r, _)| r.outcome == AnswerOutcome::Sent)
                .count(),
            1,
            "{replies:?}"
        );
        assert_eq!(
            replies
                .iter()
                .filter(|(r, _)| r.outcome == AnswerOutcome::Failed)
                .count(),
            1,
            "an attempted answer with stale Host state has uncertain delivery: {replies:?}"
        );
        input(&agent, 1);
        // A later process must also honor the durable claim, even with different picks.
        assert_eq!(
            host.answer(&agent.address, &[&question, "0"]).0.outcome,
            AnswerOutcome::Failed
        );
        std::thread::sleep(Duration::from_millis(200));
        assert_eq!(agent.inputs().len(), 1);
    }
}

/// A durable request claim alone does not prevent another answer from reading
/// the Terminal while an earlier multipart input is only partially delivered.
#[test]
fn terminal_validation_waits_for_input_and_failed_delivery_is_never_repeated() {
    use super::support::{TerminalFacts, TerminalInput, TerminalLookup, claude::ClaudeCode};
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
        mpsc,
    };

    struct Link {
        facts: TerminalFacts,
        read: Option<mpsc::Sender<()>>,
        entered: Option<mpsc::Sender<()>>,
        release: Option<mpsc::Receiver<()>>,
        inputs: Arc<AtomicUsize>,
    }
    impl TerminalLookup for Link {
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            if let Some(read) = &self.read {
                read.send(()).unwrap();
            }
            Ok(Some(self.facts.clone()))
        }
    }
    impl TerminalInput for Link {
        fn keys(&mut self, _: &str, _: &[String]) -> Result<(), String> {
            self.inputs.fetch_add(1, Ordering::SeqCst);
            if let Some(entered) = self.entered.take() {
                entered.send(()).unwrap();
            }
            if let Some(release) = self.release.take() {
                release.recv_timeout(Duration::from_secs(10)).unwrap();
            }
            Err("connection lost after input may have landed".into())
        }
    }

    let host = Host::new();
    let agent = host
        .claude("lines claude-code/main-session.jsonl 1 65 $T\nstatus blocked question\nwait\n");
    host.wait_status(&agent, "blocked");
    let facts = TerminalFacts::from_json(&host.hc.rpc(
        "terminal.get",
        serde_json::json!({"terminal": agent.address}),
    ));
    let state = host._home.path().join("claims");
    let inputs = Arc::new(AtomicUsize::new(0));
    let (entered, wait_entered) = mpsc::channel();
    let (release, wait_release) = mpsc::channel();
    let (read, wait_read) = mpsc::channel();
    let (starting, wait_starting) = mpsc::channel();
    let mut first = Link {
        facts: facts.clone(),
        read: None,
        entered: Some(entered),
        release: Some(wait_release),
        inputs: inputs.clone(),
    };
    let mut second = Link {
        facts,
        read: Some(read),
        entered: None,
        release: None,
        inputs: inputs.clone(),
    };
    let run = |link: &mut Link| {
        let agents: Vec<Box<dyn super::support::Agent>> = vec![Box::new(ClaudeCode {
            config_dir: host.config.clone(),
            runtime_dir: None,
        })];
        super::support::answer::answer(
            &state,
            &agent.address,
            &aries_protocol::answer::Answer::from_args(&[COLOR.into(), "1".into()]).unwrap(),
            link,
            &agents,
        )
        .unwrap()
    };
    let (during_input, replies) = std::thread::scope(|scope| {
        let first = scope.spawn(|| run(&mut first));
        wait_entered.recv_timeout(Duration::from_secs(10)).unwrap();
        let second = scope.spawn(|| {
            starting.send(()).unwrap();
            run(&mut second)
        });
        wait_starting.recv_timeout(Duration::from_secs(10)).unwrap();
        let during_input = wait_read.recv_timeout(Duration::from_millis(200));
        // Always release the first caller before asserting, even on regression.
        release.send(()).unwrap();
        (
            during_input,
            [first.join().unwrap(), second.join().unwrap()],
        )
    });
    assert!(
        matches!(during_input, Err(mpsc::RecvTimeoutError::Timeout)),
        "another answer read a partially changed Terminal"
    );
    assert_eq!(
        inputs.load(Ordering::SeqCst),
        1,
        "an uncertain input must retain its claim"
    );
    assert!(
        replies.iter().all(|r| r.outcome == AnswerOutcome::Failed),
        "{replies:?}"
    );
}
