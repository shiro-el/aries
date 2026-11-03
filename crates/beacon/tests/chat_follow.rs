//! The shared follow loop of `aries-beacon chat`: partial lines, rotation,
//! truncation and cursors, with a scripted Terminal and the real Claude
//! interpreter on the ticket-02 fixtures.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;

use aries_beacon::chat::claude::ClaudeInterpreter;
use aries_beacon::chat::{
    Agent, Interpreter, JsonlSource, Located, Options, Poll, TerminalFacts, TerminalLookup, run,
};
use aries_protocol::chat::{ChatEvent, ChatItem, ChatLine, ChatState};

const FIXTURES: [&str; 4] = [
    "main-session.jsonl",
    "api-error-bad-model.jsonl",
    "after-clear-then-resumed.jsonl",
    "concurrent-same-cwd-multi-question.jsonl",
];

fn fixture(name: &str) -> String {
    std::fs::read_to_string(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/agents/claude-code")
            .join(name),
    )
    .unwrap()
}

/// A Claude-transcript Agent whose current transcript the test sets.
struct TestAgent(Rc<RefCell<Option<Located>>>);

impl Agent for TestAgent {
    fn name(&self) -> &'static str {
        "claude-code"
    }
    fn locate(&self, _: &TerminalFacts) -> Option<Located> {
        self.0.borrow().clone()
    }
    fn interpreter(&self) -> Box<dyn Interpreter> {
        Box::<ClaudeInterpreter>::default()
    }
}

/// Runs one step per poll; the Terminal disappears when the steps run out.
struct Script(VecDeque<Box<dyn FnMut()>>);

impl TerminalLookup for Script {
    fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
        match self.0.pop_front() {
            Some(mut step) => {
                step();
                Ok(Some(TerminalFacts::default()))
            }
            None => Ok(None),
        }
    }
}

struct Env {
    dir: tempfile::TempDir,
    located: Rc<RefCell<Option<Located>>>,
}

impl Env {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            located: Rc::new(RefCell::new(None)),
        }
    }

    fn path(&self, session: &str) -> PathBuf {
        self.dir.path().join(format!("{session}.jsonl"))
    }

    fn point_at(&self, session: &str) {
        *self.located.borrow_mut() = Some(Located {
            session: session.into(),
            path: self.path(session),
        });
    }

    /// Runs the loop (following) through `steps`, returns its lines.
    fn run(&self, cursor: Option<String>, steps: Vec<Box<dyn FnMut()>>) -> Vec<ChatLine> {
        let mut opts = Options::new("t1");
        opts.follow = true;
        opts.cursor = cursor;
        opts.poll = Duration::ZERO;
        let agents: Vec<Box<dyn Agent>> = vec![Box::new(TestAgent(self.located.clone()))];
        let mut out = Vec::new();
        run(opts, &mut Script(steps.into()), &agents, &mut out).unwrap();
        String::from_utf8(out)
            .unwrap()
            .lines()
            .map(|l| ChatLine::parse(l).unwrap())
            .collect()
    }
}

fn append(path: &Path, text: &str) {
    std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .unwrap()
        .write_all(text.as_bytes())
        .unwrap();
}

fn nothing() -> Box<dyn FnMut()> {
    Box::new(|| {})
}

/// The reader's view: items after applying the events, in order.
fn items(lines: &[ChatLine]) -> Vec<ChatItem> {
    let mut items: Vec<ChatItem> = Vec::new();
    for l in lines {
        match &l.event {
            ChatEvent::Reset { .. } => items.clear(),
            ChatEvent::Item { item } => match items.iter_mut().find(|i| i.id == item.id) {
                Some(slot) => *slot = item.clone(),
                None => items.push(item.clone()),
            },
            ChatEvent::Remove { id } => items.retain(|i| &i.id != id),
            _ => {}
        }
    }
    items
}

fn whole(text: &str) -> Vec<ChatItem> {
    let mut interp = ClaudeInterpreter::default();
    let lines: Vec<ChatLine> = text
        .lines()
        .flat_map(|l| interp.line(l))
        .map(ChatLine::new)
        .collect();
    items(&lines)
}

fn states(lines: &[ChatLine]) -> Vec<ChatState> {
    lines
        .iter()
        .filter_map(|l| match l.event {
            ChatEvent::State { state } => Some(state),
            _ => None,
        })
        .collect()
}

fn last_cursor(lines: &[ChatLine]) -> String {
    lines.iter().rev().find_map(|l| l.cursor.clone()).unwrap()
}

#[test]
fn a_half_written_line_waits_for_its_newline() {
    let env = Env::new();
    let path = env.path("s");
    let text = fixture("main-session.jsonl");
    let user = text.lines().nth(8).unwrap();
    let (head, tail) = user.split_at(user.len() / 2);
    let mut source = JsonlSource::new(path.clone(), Box::<ClaudeInterpreter>::default(), 0);
    append(&path, head);
    let count = |s: &mut JsonlSource| match s.poll().unwrap() {
        Poll::Events { events, .. } => events.len(),
        _ => panic!("file is there"),
    };
    assert_eq!(count(&mut source), 0);
    append(&path, tail);
    assert_eq!(count(&mut source), 0, "still no newline");
    append(&path, "\n");
    assert_eq!(count(&mut source), 1);
}

/// Bulk run over the ticket-02 fixtures: every fixture, written in many
/// different random chunkings while the loop follows, must read exactly
/// as the whole file does.
#[test]
fn every_fixture_reads_the_same_however_it_is_written() {
    let mut seed: u64 = 0x5eed;
    let mut next = move |n: usize| {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (seed >> 33) as usize % n
    };
    for name in FIXTURES {
        let text = fixture(name);
        let expected = whole(&text);
        assert!(!expected.is_empty(), "{name}");
        for round in 0..40 {
            let env = Env::new();
            env.point_at("s");
            let path = env.path("s");
            let mut steps: Vec<Box<dyn FnMut()>> = Vec::new();
            let mut at = 0;
            let bytes = text.as_bytes().to_vec();
            while at < bytes.len() {
                let len = 1 + next(if round % 2 == 0 { 64 } else { 8192 });
                let end = (at + len).min(bytes.len());
                let chunk = bytes[at..end].to_vec();
                let path = path.clone();
                steps.push(Box::new(move || {
                    let mut f = std::fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .open(&path)
                        .unwrap();
                    f.write_all(&chunk).unwrap();
                }));
                at = end;
            }
            steps.push(nothing());
            let lines = env.run(None, steps);
            assert_eq!(items(&lines), expected, "{name} round {round}");
            assert!(
                matches!(lines[0].event, ChatEvent::Reset { ref session, .. } if session == "s"),
                "{name}: starts with a reset"
            );
            assert_eq!(states(&lines).iter().rev().nth(1), Some(&ChatState::Live), "{name}");
        }
    }
}

#[test]
fn resuming_from_a_cursor_neither_repeats_nor_skips() {
    let text = fixture("main-session.jsonl");
    let lines: Vec<&str> = text.lines().collect();
    let expected = whole(&text);
    // Cut the transcript at every line boundary that changes the Chat.
    for cut in [9, 33, 50, 52, 66, 69, 82, 84, 100, 113] {
        let env = Env::new();
        env.point_at("s");
        let path = env.path("s");
        append(&path, &(lines[..cut].join("\n") + "\n"));
        let first = env.run(None, vec![nothing()]);
        let cursor = last_cursor(&first);

        append(&path, &(lines[cut..].join("\n") + "\n"));
        let second = env.run(Some(cursor), vec![nothing()]);
        assert!(
            !second.iter().any(|l| matches!(l.event, ChatEvent::Reset { .. })),
            "cut {cut}: a valid cursor does not reset"
        );
        let mut all = first.clone();
        all.extend(second.iter().cloned());
        assert_eq!(items(&all), expected, "cut {cut}");

        // Nothing the first run already sent comes again (tool results
        // update an earlier item in place; that is not a repeat).
        let sent: Vec<&ChatItem> = first
            .iter()
            .filter_map(|l| match &l.event {
                ChatEvent::Item { item } => Some(item),
                _ => None,
            })
            .collect();
        for l in &second {
            if let ChatEvent::Item { item } = &l.event {
                assert!(!sent.contains(&item), "cut {cut}: repeated {item:?}");
            }
        }
    }
}

#[test]
fn a_new_transcript_resets_and_waits_until_its_file_appears() {
    let env = Env::new();
    env.point_at("old");
    append(&env.path("old"), &fixture("main-session.jsonl"));
    let after_clear = fixture("after-clear-then-resumed.jsonl");
    let (located, new_path) = (env.located.clone(), env.path("new"));
    let new_located = Located {
        session: "new".into(),
        path: new_path.clone(),
    };
    let lines = env.run(
        None,
        vec![
            nothing(),
            Box::new(move || *located.borrow_mut() = Some(new_located.clone())),
            nothing(),
            Box::new(move || append(&new_path, &after_clear)),
            nothing(),
        ],
    );
    let resets: Vec<&str> = lines
        .iter()
        .filter_map(|l| match &l.event {
            ChatEvent::Reset { session, .. } => Some(session.as_str()),
            _ => None,
        })
        .collect();
    assert_eq!(resets, ["old", "new"]);
    assert_eq!(
        states(&lines),
        [
            ChatState::Live,
            ChatState::Waiting,
            ChatState::Live,
            ChatState::NoTerminal
        ]
    );
    assert_eq!(items(&lines), whole(&fixture("after-clear-then-resumed.jsonl")));
}

#[test]
fn a_cursor_for_another_transcript_starts_over() {
    let env = Env::new();
    env.point_at("s");
    append(&env.path("s"), &fixture("api-error-bad-model.jsonl"));
    let lines = env.run(Some("claude-code:other:500".into()), vec![nothing()]);
    assert!(matches!(lines[0].event, ChatEvent::Reset { .. }));
    assert_eq!(items(&lines), whole(&fixture("api-error-bad-model.jsonl")));
    // A cursor beyond the end of the file is no good either.
    let lines = env.run(Some("claude-code:s:99999999".into()), vec![nothing()]);
    assert!(matches!(lines[0].event, ChatEvent::Reset { .. }));
}

#[test]
fn a_shrunk_transcript_is_read_again_from_the_start() {
    let env = Env::new();
    env.point_at("s");
    let path = env.path("s");
    append(&path, &fixture("main-session.jsonl"));
    let short = fixture("api-error-bad-model.jsonl");
    let p = path.clone();
    let lines = env.run(
        None,
        vec![
            nothing(),
            Box::new(move || std::fs::write(&p, &short).unwrap()),
            nothing(),
            nothing(),
        ],
    );
    let resets = lines
        .iter()
        .filter(|l| matches!(l.event, ChatEvent::Reset { .. }))
        .count();
    assert_eq!(resets, 2);
    assert_eq!(items(&lines), whole(&fixture("api-error-bad-model.jsonl")));
}

#[test]
fn an_exited_agent_leaves_its_chat_ended() {
    let env = Env::new();
    env.point_at("s");
    append(&env.path("s"), &fixture("api-error-bad-model.jsonl"));
    let located = env.located.clone();
    let lines = env.run(
        None,
        vec![nothing(), Box::new(move || *located.borrow_mut() = None), nothing()],
    );
    assert_eq!(
        states(&lines),
        [ChatState::Live, ChatState::Ended, ChatState::NoTerminal]
    );
    assert_eq!(items(&lines).len(), 2);
}

#[test]
fn no_agent_and_no_terminal_are_reported() {
    let env = Env::new();
    let lines = env.run(None, vec![nothing()]);
    assert_eq!(states(&lines), [ChatState::NoAgent, ChatState::NoTerminal]);
}

#[test]
fn without_follow_it_prints_the_chat_and_exits() {
    let env = Env::new();
    env.point_at("s");
    append(&env.path("s"), &fixture("main-session.jsonl"));
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(TestAgent(env.located.clone()))];
    let mut out = Vec::new();
    // Endless Terminal: only the missing --follow ends the run.
    struct Forever;
    impl TerminalLookup for Forever {
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            Ok(Some(TerminalFacts::default()))
        }
    }
    run(Options::new("t1"), &mut Forever, &agents, &mut out).unwrap();
    let lines: Vec<ChatLine> = String::from_utf8(out)
        .unwrap()
        .lines()
        .map(|l| ChatLine::parse(l).unwrap())
        .collect();
    assert_eq!(items(&lines), whole(&fixture("main-session.jsonl")));
    assert_eq!(states(&lines), [ChatState::Live]);
}
