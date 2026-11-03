//! Pi in the Chat: the interpreter on the ticket-05 fixtures (real Pi
//! session files) plus a hand-written `/tree` branching file, the shared
//! follow loop over them (partial lines, cursors), and pairing.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::io::Write;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;

use aries_beacon::chat::pi::{Pi, PiInterpreter, numbered_diff};
use aries_beacon::chat::{
    Agent, Interpreter, Located, Options, TerminalFacts, TerminalLookup, run,
};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ChatLine, DiffHunk, ToolStatus};
use serde_json::json;

const FIXTURES: &[&str] = &[
    "session-full-tour.jsonl",
    "session-same-cwd-second.jsonl",
    "session-after-new.jsonl",
    "session-clone-child.jsonl",
    "session-preset-user-image.jsonl",
    "synthetic-tree-branches.jsonl",
];

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../fixtures/agents/pi")
        .join(name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// A reader's view: upsert by id, remove by id, reset clears.
#[derive(Default)]
struct Chat {
    items: Vec<ChatItem>,
}

impl Chat {
    fn apply(&mut self, event: ChatEvent) {
        match event {
            ChatEvent::Item { item } => match self.items.iter_mut().find(|i| i.id == item.id) {
                Some(slot) => *slot = item,
                None => self.items.push(item),
            },
            ChatEvent::Remove { id } => self.items.retain(|i| i.id != id),
            ChatEvent::Reset { .. } => self.items.clear(),
            _ => {}
        }
    }

    fn feed(text: &str) -> Self {
        let mut chat = Chat::default();
        let mut interp = PiInterpreter::default();
        for line in text.lines() {
            for e in interp.line(line) {
                chat.apply(e);
            }
        }
        chat
    }

    fn summary(&self) -> Vec<String> {
        self.items.iter().map(short).collect()
    }
}

fn short(item: &ChatItem) -> String {
    match &item.body {
        ChatBody::User { text, images } => format!("user {text:?} images={}", images.len()),
        ChatBody::Agent { text } => format!("agent {:?}", text.trim()),
        ChatBody::Thinking { text } => format!("thinking {:?}", text.as_deref().map(str::trim)),
        ChatBody::Tool {
            name,
            title,
            status,
            diff,
            images,
            ..
        } => format!(
            "tool {name} {title:?} {status:?} diff={} images={}",
            diff.is_some(),
            images.len()
        ),
        ChatBody::Error { message } => format!("error {message:?}"),
        other => format!("{other:?}"),
    }
}

#[test]
fn reads_the_full_tour() {
    let chat = Chat::feed(&fixture("session-full-tour.jsonl"));
    let got = chat.summary();
    let want = [
        r#"user "hello there" images=0"#,
        r#"agent "Echo: hello there""#,
        r#"user "please think" images=0"#,
        r#"thinking Some("Let me think step by step about this.")"#,
        r#"agent "Thought about it: 42.""#,
        r#"user "dobash now" images=0"#,
        r#"tool bash "echo hello-from-bash && ls" Done diff=false images=0"#,
        r#"agent "Tool finished. Result starts: hello-from-bash doomed.txt""#,
        r#"user "dowrite file" images=0"#,
        r#"tool write "notes.txt" Done diff=true images=0"#,
        r#"agent "Tool finished. Result starts: Successfully wrote 18 bytes to notes.txt""#,
        r#"user "doedit file" images=0"#,
        r#"tool edit "notes.txt" Done diff=true images=0"#,
        r#"agent "Tool finished. Result starts: Successfully replaced 1 block(s) in notes.txt.""#,
        r#"user "badbash" images=0"#,
        r#"tool bash "cat /nonexistent/file" Error diff=false images=0"#,
    ];
    assert_eq!(got[..want.len()], want, "{got:#?}");
    // The rest: image read, two failed prompts (each retried 3 times: one
    // error each), the question tool, a denied command, an interrupt.
    let rest = &got[want.len()..];
    assert!(rest[0].starts_with("agent \"Tool finished."), "{rest:#?}");
    assert_eq!(rest[1], r#"user "readimg please" images=0"#);
    assert_eq!(rest[2], r#"tool read "pic.png" Done diff=false images=1"#);
    let errors: Vec<&String> = rest.iter().filter(|s| s.starts_with("error")).collect();
    assert_eq!(errors.len(), 2, "{rest:#?}");
    assert!(rest.contains(
        &r#"tool bash "rm -f /home/user/proj/doomed.txt" Error diff=false images=0"#.to_string()
    ));
    // Interrupted while streaming: what arrived stays.
    assert_eq!(rest.last().unwrap(), r#"agent "slow reply""#);
}

fn tool<'a>(chat: &'a Chat, name: &str) -> &'a ChatBody {
    &chat
        .items
        .iter()
        .find(|i| matches!(&i.body, ChatBody::Tool { name: n, .. } if n == name))
        .unwrap_or_else(|| panic!("no {name} tool"))
        .body
}

#[test]
fn an_edit_shows_its_diff_and_a_write_its_new_file() {
    let chat = Chat::feed(&fixture("session-full-tour.jsonl"));
    let ChatBody::Tool {
        diff,
        output,
        input,
        ..
    } = tool(&chat, "edit")
    else {
        unreachable!()
    };
    assert_eq!(
        output.as_deref(),
        Some("Successfully replaced 1 block(s) in notes.txt.")
    );
    assert_eq!(input["path"], "notes.txt");
    let diff = diff.as_ref().unwrap();
    assert_eq!(diff.path, "notes.txt");
    assert_eq!(
        diff.hunks,
        [DiffHunk {
            old_start: 1,
            old_lines: 2,
            new_start: 1,
            new_lines: 2,
            lines: vec![
                " line one".into(),
                "-line two".into(),
                "+line TWO edited".into()
            ],
        }]
    );

    let ChatBody::Tool { diff, .. } = tool(&chat, "write") else {
        unreachable!()
    };
    assert_eq!(
        diff.as_ref().unwrap().hunks[0].lines,
        ["+line one", "+line two"]
    );
}

#[test]
fn numbered_diffs_split_at_skipped_lines() {
    let text = "    ...\n  9 nine\n-10 ten\n+10 TEN\n+11 ten and a half\n 11 eleven\n    ...\n 40 forty\n-41 gone\n 42 kept";
    let diff = numbered_diff("f.rs", text).unwrap();
    assert_eq!(
        diff.hunks,
        [
            DiffHunk {
                old_start: 9,
                old_lines: 3,
                new_start: 9,
                new_lines: 4,
                lines: vec![
                    " nine".into(),
                    "-ten".into(),
                    "+TEN".into(),
                    "+ten and a half".into(),
                    " eleven".into()
                ],
            },
            DiffHunk {
                old_start: 40,
                old_lines: 3,
                new_start: 41,
                new_lines: 2,
                lines: vec![" forty".into(), "-gone".into(), " kept".into()],
            },
        ]
    );
    assert!(numbered_diff("f", "garbage").is_none());
}

#[test]
fn images_from_tools_and_from_the_user_carry_their_bytes() {
    let chat = Chat::feed(&fixture("session-full-tour.jsonl"));
    let ChatBody::Tool { images, .. } = tool(&chat, "read") else {
        unreachable!()
    };
    assert_eq!(images[0].media_type, "image/png");
    assert!(images[0].data.is_some() && images[0].bytes > 0);

    let chat = Chat::feed(&fixture("session-preset-user-image.jsonl"));
    let ChatBody::User { text, images } = &chat.items[0].body else {
        panic!("{:?}", chat.items[0])
    };
    assert_eq!(images.len(), 1, "{text}");
    assert!(images[0].data.is_some());
}

#[test]
fn a_retried_failure_is_one_error() {
    let chat = Chat::feed(&fixture("session-full-tour.jsonl"));
    let errors: Vec<&ChatItem> = chat
        .items
        .iter()
        .filter(|i| matches!(i.body, ChatBody::Error { .. }))
        .collect();
    assert_eq!(errors.len(), 2);
    for e in errors {
        let ChatBody::Error { message } = &e.body else {
            unreachable!()
        };
        assert!(!message.is_empty() && message != "error", "{message}");
    }
}

#[test]
fn the_question_tool_shows_as_a_plain_tool() {
    // Pi has no question feature; answering from Chat is not offered.
    let chat = Chat::feed(&fixture("session-full-tour.jsonl"));
    let ChatBody::Tool {
        question,
        output,
        status,
        ..
    } = tool(&chat, "ask_user")
    else {
        unreachable!()
    };
    assert!(question.is_none());
    assert_eq!(*status, ToolStatus::Done);
    assert_eq!(output.as_deref(), Some("green"));
}

#[test]
fn every_fixture_reads_without_unsupported_items() {
    for name in FIXTURES {
        let chat = Chat::feed(&fixture(name));
        assert!(!chat.items.is_empty(), "{name}");
        for item in &chat.items {
            assert!(
                !matches!(item.body, ChatBody::Unsupported),
                "{name}: {item:?}"
            );
        }
        let ids: std::collections::HashSet<&str> =
            chat.items.iter().map(|i| i.id.as_str()).collect();
        assert_eq!(ids.len(), chat.items.len(), "{name}: duplicate ids");
    }
}

#[test]
fn the_chat_follows_the_current_branch_of_the_tree() {
    let text = fixture("synthetic-tree-branches.jsonl");
    let mut interp = PiInterpreter::default();
    let mut chat = Chat::default();
    let mut per_line = Vec::new();
    for line in text.lines() {
        let events = interp.line(line);
        per_line.push(events.clone());
        for e in events {
            chat.apply(e);
        }
    }
    // "branch B question" continues "first answer": branch A goes.
    let removed = |events: &[ChatEvent]| -> Vec<String> {
        events
            .iter()
            .filter_map(|e| match e {
                ChatEvent::Remove { id } => Some(id.clone()),
                _ => None,
            })
            .collect()
    };
    assert_eq!(removed(&per_line[7]), ["u0000002", "call_a"]);
    // Back on A (through the branch summary): B goes, A comes back with
    // its tool result.
    assert_eq!(removed(&per_line[9]), ["u0000003", "a0000003"]);
    assert_eq!(
        chat.summary(),
        [
            r#"user "first question" images=0"#,
            r#"agent "first answer""#,
            r#"user "branch A question" images=0"#,
            r#"tool bash "echo A" Done diff=false images=0"#,
            r#"user "back on A" images=0"#,
            r#"agent "answer A again""#,
        ]
    );
}

// The shared follow loop over Pi files.

struct TestAgent(Rc<RefCell<Option<Located>>>);

impl Agent for TestAgent {
    fn name(&self) -> &'static str {
        "pi"
    }
    fn locate(&self, _: &TerminalFacts) -> Option<Located> {
        self.0.borrow().clone()
    }
    fn interpreter(&self) -> Box<dyn Interpreter> {
        Box::<PiInterpreter>::default()
    }
}

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

fn follow(path: &Path, cursor: Option<String>, steps: Vec<Box<dyn FnMut()>>) -> Vec<ChatLine> {
    let located = Rc::new(RefCell::new(Some(Located {
        session: "s".into(),
        path: path.to_owned(),
    })));
    let mut opts = Options::new("t1");
    opts.follow = true;
    opts.cursor = cursor;
    opts.poll = Duration::ZERO;
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(TestAgent(located))];
    let mut out = Vec::new();
    run(opts, &mut Script(steps.into()), &agents, &mut out).unwrap();
    String::from_utf8(out)
        .unwrap()
        .lines()
        .map(|l| ChatLine::parse(l).unwrap())
        .collect()
}

fn append(path: &Path, bytes: &[u8]) {
    std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .unwrap()
        .write_all(bytes)
        .unwrap();
}

fn view(lines: &[ChatLine]) -> Vec<ChatItem> {
    let mut chat = Chat::default();
    for l in lines {
        chat.apply(l.event.clone());
    }
    chat.items
}

/// Tiny deterministic generator (no rand dependency).
fn chunks(len: usize, seed: u64) -> Vec<usize> {
    let mut x = seed
        .wrapping_mul(6364136223846793005)
        .wrapping_add(1442695040888963407);
    let mut cuts = Vec::new();
    let mut at = 0;
    while at < len {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        at = (at + 1 + (x % 700) as usize).min(len);
        cuts.push(at);
    }
    cuts
}

#[test]
fn every_fixture_reads_the_same_however_it_is_written() {
    for (n, name) in FIXTURES.iter().enumerate() {
        let text = fixture(name);
        let bytes = text.as_bytes().to_vec();
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        let mut steps: Vec<Box<dyn FnMut()>> = Vec::new();
        let mut from = 0;
        for to in chunks(bytes.len(), n as u64 + 7) {
            let piece = bytes[from..to].to_vec();
            let p = path.clone();
            steps.push(Box::new(move || append(&p, &piece)));
            from = to;
        }
        steps.push(Box::new(|| {}));
        let lines = follow(&path, None, steps);
        assert_eq!(view(&lines), Chat::feed(&text).items, "{name}");
    }
}

#[test]
fn resuming_from_any_cursor_neither_repeats_nor_skips() {
    for name in ["synthetic-tree-branches.jsonl", "session-full-tour.jsonl"] {
        let text = fixture(name);
        let want = Chat::feed(&text).items;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        let lines: Vec<&str> = text.split_inclusive('\n').collect();
        for cut in 1..lines.len() {
            std::fs::write(&path, lines[..cut].concat()).unwrap();
            let first = follow(&path, None, vec![Box::new(|| {})]);
            let cursor = first.iter().rev().find_map(|l| l.cursor.clone()).unwrap();
            let rest = lines[cut..].concat();
            let p = path.clone();
            let second = follow(
                &path,
                Some(cursor),
                vec![
                    Box::new(move || append(&p, rest.as_bytes())),
                    Box::new(|| {}),
                ],
            );
            assert!(
                !second
                    .iter()
                    .any(|l| matches!(l.event, ChatEvent::Reset { .. })),
                "{name} cut {cut}: resumed with a reset"
            );
            let mut all = first.clone();
            all.extend(second);
            assert_eq!(view(&all), want, "{name} cut {cut}");
        }
    }
}

// Pairing.

/// A process whose title is `pi`, like Pi's.
fn fake_pi() -> std::process::Child {
    std::process::Command::new("sleep")
        .arg0("pi")
        .arg("30")
        .spawn()
        .unwrap()
}

fn facts(pid: u32, labels: serde_json::Value) -> TerminalFacts {
    TerminalFacts::from_json(&json!({ "pid": pid, "labels": labels }))
}

#[test]
fn pairs_through_the_extension_labels_while_pi_runs() {
    let mut pi = fake_pi();
    let labels = json!({
        "aries.pi.session": "0193-abc",
        "aries.pi.file": "/home/u/.pi/agent/sessions/--proj--/2026_0193-abc.jsonl",
    });
    assert_eq!(
        Pi.locate(&facts(pi.id(), labels.clone())),
        Some(Located {
            session: "0193-abc".into(),
            path: "/home/u/.pi/agent/sessions/--proj--/2026_0193-abc.jsonl".into(),
        })
    );
    // Without the session label the file name stands in.
    let only_file = json!({ "aries.pi.file": "/s/2026_x.jsonl" });
    assert_eq!(
        Pi.locate(&facts(pi.id(), only_file)).unwrap().session,
        "2026_x"
    );
    // No label: not paired (the extension is missing).
    assert_eq!(Pi.locate(&facts(pi.id(), json!({}))), None);
    pi.kill().unwrap();
    pi.wait().unwrap();

    // The label stays after Pi exits; nothing named pi runs any more.
    let mut other = std::process::Command::new("sleep")
        .arg("30")
        .spawn()
        .unwrap();
    assert_eq!(Pi.locate(&facts(other.id(), labels)), None);
    other.kill().unwrap();
    other.wait().unwrap();
}
