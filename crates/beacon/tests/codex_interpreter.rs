//! The Codex interpreter on the ticket-03 fixtures (real Codex 0.165
//! rollouts), the shared follow loop reading them, and pairing a process
//! with its open rollout.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;

use aries_beacon::chat::codex::{Codex, CodexInterpreter, exec_title, locate_in, unified_hunks};
use aries_beacon::chat::{
    Agent, Interpreter, KeysError, Located, Options, TerminalFacts, TerminalLookup, run,
};
use aries_protocol::chat::{
    ChatBody, ChatEvent, ChatItem, ChatLine, ChatQuestion, Question, QuestionOption, ToolStatus,
};

const FIXTURES: [&str; 7] = [
    "simple-reply",
    "permission-exec",
    "rotation-new-diff-image",
    "question-then-resume",
    "thinking-user-image",
    "error-model",
    "daemon-mode",
];
const QUESTION: &str = "call_f2e4624a40ee4bd7ba6cc92a8b052c06";

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../fixtures/agents/codex/sessions")
        .join(format!("{name}.rollout.jsonl"));
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// The reader's view: items after applying the events, in order.
fn items(events: impl IntoIterator<Item = ChatEvent>) -> Vec<ChatItem> {
    let mut items: Vec<ChatItem> = Vec::new();
    for e in events {
        match e {
            ChatEvent::Reset { .. } => items.clear(),
            ChatEvent::Item { item } => match items.iter_mut().find(|i| i.id == item.id) {
                Some(slot) => *slot = item,
                None => items.push(item),
            },
            ChatEvent::Remove { id } => items.retain(|i| i.id != id),
            _ => {}
        }
    }
    items
}

fn feed_lines(lines: &[&str]) -> Vec<ChatItem> {
    let mut interp = CodexInterpreter::default();
    items(lines.iter().flat_map(|l| interp.line(l)))
}

fn feed(name: &str) -> Vec<ChatItem> {
    let text = fixture(name);
    feed_lines(&text.lines().collect::<Vec<_>>())
}

fn short(body: &ChatBody) -> String {
    let cut = |s: &str| s.chars().take(40).collect::<String>();
    match body {
        ChatBody::User { text, images } => format!("user: {} [{}]", cut(text), images.len()),
        ChatBody::Agent { text, .. } => format!("agent: {}", cut(text)),
        ChatBody::Thinking { text } => format!("thinking: {text:?}"),
        ChatBody::Tool {
            name,
            title,
            status,
            ..
        } => format!("tool {name} {status:?}: {title}"),
        ChatBody::Error { message } => format!("error: {}", cut(message)),
        ChatBody::Unsupported => "unsupported".into(),
    }
}

fn summary(items: &[ChatItem]) -> Vec<String> {
    items.iter().map(|i| short(&i.body)).collect()
}

fn tool<'a>(items: &'a [ChatItem], name: &str) -> &'a ChatBody {
    &items
        .iter()
        .find(|i| matches!(&i.body, ChatBody::Tool { name: n, .. } if n == name))
        .unwrap_or_else(|| panic!("no {name} in {:#?}", summary(items)))
        .body
}

#[test]
fn a_plain_reply_reads_as_the_user_message_and_the_answer() {
    assert_eq!(
        summary(&feed("simple-reply")),
        ["user: Reply with exactly the word: pong [0]", "agent: pong"]
    );
    // The daemon writes the same rollout.
    assert_eq!(summary(&feed("daemon-mode")).len(), 2);
}

#[test]
fn a_failed_command_and_its_approved_retry_show_as_tools() {
    let chat = feed("permission-exec");
    assert_eq!(
        summary(&chat),
        [
            "user: Create an empty file named x.txt in the  [0]",
            // Read-only sandbox: the first try fails inside the exec script.
            "tool exec Error: touch x.txt",
            // The approved retry shows as the command it ran.
            "tool command Done: touch x.txt",
        ]
    );
    let ChatBody::Tool { output, .. } = &chat[1].body else {
        panic!()
    };
    assert_eq!(
        output.as_deref(),
        Some(
            "Script completed\nWall time 0.1 seconds\nOutput:\ntouch: cannot touch 'x.txt': Read-only file system"
        )
    );
}

#[test]
fn a_tool_call_runs_until_its_output() {
    let text = fixture("permission-exec");
    let lines: Vec<&str> = text.lines().collect();
    // Up to the approved retry (ordinal 17), waiting for approval.
    let chat = feed_lines(&lines[..=17]);
    let last = chat.last().unwrap();
    assert_eq!(last.id, "call_0c04f61ed3c14811a9796cb257e167e7");
    assert_eq!(short(&last.body), "tool exec Running: touch x.txt");
}

#[test]
fn a_patch_shows_its_diff_and_a_viewed_image_its_picture() {
    let chat = feed("rotation-new-diff-image");
    assert_eq!(
        summary(&chat),
        [
            "user: Do two things. 1) Use apply_patch to cre [0]",
            "agent: I’ll create `hello.txt` and inspect `red",
            "tool apply_patch Done: /home/user/work/a/hello.txt",
            "tool view_image Done: /home/user/work/a/red.png",
            "agent: Created `hello.txt` containing `hi`.\n\nRe",
        ]
    );
    let ChatBody::Tool { diff, output, .. } = tool(&chat, "apply_patch") else {
        panic!()
    };
    let diff = diff.as_ref().unwrap();
    assert_eq!(diff.path, "/home/user/work/a/hello.txt");
    assert_eq!(diff.hunks.len(), 1);
    assert_eq!(diff.hunks[0].lines, ["+hi"]);
    assert!(output.as_deref().unwrap().starts_with("Success."));

    let ChatBody::Tool { images, .. } = tool(&chat, "view_image") else {
        panic!()
    };
    assert_eq!(images.len(), 1);
    assert_eq!(images[0].media_type, "image/png");
    assert_eq!(images[0].bytes, 75);
    assert!(
        images[0]
            .data
            .as_deref()
            .unwrap()
            .starts_with("iVBORw0KGgo")
    );
}

#[test]
fn a_question_is_open_until_its_answer_then_the_resumed_turn_follows() {
    let text = fixture("question-then-resume");
    let lines: Vec<&str> = text.lines().collect();
    let open = feed_lines(&lines[..=13]);
    let ChatBody::Tool {
        status,
        question,
        title,
        ..
    } = &open.last().unwrap().body
    else {
        panic!()
    };
    assert_eq!(open.last().unwrap().id, QUESTION);
    assert_eq!(*status, ToolStatus::Running);
    assert_eq!(title, "Which color do you prefer?");
    assert_eq!(
        question.as_ref().unwrap(),
        &ChatQuestion {
            questions: vec![Question {
                text: "Which color do you prefer?".into(),
                header: Some("Color".into()),
                multi: false,
                options: vec![
                    QuestionOption {
                        label: "Red".into(),
                        description: Some("Choose Red.".into()),
                    },
                    QuestionOption {
                        label: "Blue".into(),
                        description: Some("Choose Blue.".into()),
                    },
                ],
            }],
        }
    );

    let chat = feed_lines(&lines);
    let s = summary(&chat);
    assert_eq!(
        s[1],
        "tool request_user_input Done: Which color do you prefer?"
    );
    let ChatBody::Tool { output, .. } = &chat[1].body else {
        panic!()
    };
    assert_eq!(output.as_deref(), Some("Blue"));
    assert_eq!(s.len(), 5, "{s:#?}");
    assert!(s[3].starts_with("user: "), "{s:#?}");
}

#[test]
fn thinking_and_an_attached_image_show() {
    let chat = feed("thinking-user-image");
    assert_eq!(
        summary(&chat),
        [
            "user:  [1]",
            "thinking: Some(\"**Interpreting image color**\")",
            "agent: The main color is red, and 17 × 23 = 391",
        ]
    );
}

#[test]
fn a_failed_turn_is_an_error_item() {
    assert_eq!(
        summary(&feed("error-model")),
        [
            "user: hi [0]",
            "error: The 'does-not-exist-model' model is not ",
        ]
    );
}

#[test]
fn unknown_and_broken_lines_are_ignored() {
    let mut interp = CodexInterpreter::default();
    assert!(interp.line("not json").is_empty());
    assert!(
        interp
            .line(r#"{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"Future","id":"x"}}}"#)
            .is_empty()
    );
    assert!(
        interp
            .line(r#"{"type":"brand_new","payload":{}}"#)
            .is_empty()
    );
}

#[test]
fn exec_titles_name_what_the_script_does() {
    assert_eq!(
        exec_title(
            "text(await tools.exec_command({cmd:\"echo \\\"hi\\\"\",max_output_tokens:1000}));\n"
        ),
        "echo \"hi\""
    );
    assert_eq!(
        exec_title(
            "text(await tools.apply_patch(\"*** Begin Patch\\n*** Update File: /a/b.rs\\n@@\\n-x\\n+y\\n*** End Patch\"));"
        ),
        "/a/b.rs"
    );
    assert_eq!(
        exec_title("await tools.view_image({path:\"/a.png\"})"),
        "/a.png"
    );
}

#[test]
fn an_updated_file_shows_its_unified_diff() {
    let hunks =
        unified_hunks("--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n@@ -10 +10,0 @@\n-z\n");
    assert_eq!(hunks.len(), 2);
    assert_eq!(
        (
            hunks[0].old_start,
            hunks[0].old_lines,
            hunks[0].new_start,
            hunks[0].new_lines
        ),
        (1, 2, 1, 2)
    );
    assert_eq!(hunks[0].lines, [" a", "-b", "+c"]);
    assert_eq!((hunks[1].old_lines, hunks[1].new_lines), (1, 0));
    assert_eq!(hunks[1].lines, ["-z"]);
}

#[test]
fn single_choice_questions_advance_in_order_with_down_and_enter() {
    let q = |n: usize, multi: bool| ChatQuestion {
        questions: (0..n)
            .map(|_| Question {
                text: "Q".into(),
                header: None,
                multi,
                options: ["a", "b", "c"]
                    .map(|l| QuestionOption {
                        label: l.into(),
                        description: None,
                    })
                    .into(),
            })
            .collect(),
    };
    assert_eq!(
        Codex.answer_keys(&q(1, false), &[vec![0]]).unwrap(),
        ["enter"]
    );
    assert_eq!(
        Codex.answer_keys(&q(1, false), &[vec![2]]).unwrap(),
        ["down", "down", "enter"]
    );
    assert_eq!(
        Codex
            .answer_keys(&q(2, false), &[vec![0], vec![1]])
            .unwrap(),
        ["enter", "down", "enter"]
    );
    assert!(matches!(
        Codex.answer_keys(&q(1, true), &[vec![0, 1]]),
        Err(KeysError::Unsupported(_))
    ));
}

// Pairing.

const OLD: &str = "01a1a011-99e0-7ce0-9897-761972ab3825";
const NEW: &str = "01a1a012-3af0-7c01-97e4-7e2adbc2ae4f";

fn touch(path: &Path) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    append(path, "x\n");
    // mtime granularity: keep files distinguishable by write order.
    std::thread::sleep(Duration::from_millis(20));
}

#[test]
fn the_most_recently_written_open_thread_is_the_current_one() {
    let home = tempfile::tempdir().unwrap();
    let day = home.path().join("sessions/2026/11/03");
    let old = day.join(format!("rollout-2026-11-03T15-21-10-{OLD}.jsonl"));
    let old_lock = home.path().join(format!("thread-writer-locks/{OLD}.lock"));
    let new = day.join(format!("rollout-2026-11-03T15-21-51-{NEW}.jsonl"));
    let new_lock = home.path().join(format!("thread-writer-locks/{NEW}.lock"));
    let other = home.path().join("notes.txt");

    touch(&old_lock);
    touch(&old);
    touch(&other);
    assert_eq!(
        locate_in([other.clone(), old_lock.clone(), old.clone()]),
        Some(Located {
            session: OLD.into(),
            path: old.clone()
        })
    );

    // `/new`: a lock for the new thread, no rollout until the first message.
    touch(&new_lock);
    let waiting = locate_in([old.clone(), old_lock.clone(), new_lock.clone()]).unwrap();
    assert_eq!(waiting.session, NEW);
    assert!(!waiting.path.exists());

    touch(&new);
    assert_eq!(
        locate_in([old.clone(), old_lock.clone(), new_lock.clone(), new.clone()]),
        Some(Located {
            session: NEW.into(),
            path: new.clone()
        })
    );
    // The old thread written again (e.g. a late line) wins back.
    touch(&old);
    assert_eq!(locate_in([old.clone(), new.clone()]).unwrap().session, OLD);

    assert_eq!(locate_in([other.clone()]), None);
    assert_eq!(locate_in([home.path().join("rollout-x.jsonl")]), None);
}

#[test]
fn a_process_holding_a_rollout_open_is_paired() {
    let home = tempfile::tempdir().unwrap();
    let path = home.path().join(format!(
        "sessions/2026/11/03/rollout-2026-11-03T15-21-51-{NEW}.jsonl"
    ));
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let _held = std::fs::File::create(&path).unwrap();
    let facts = TerminalFacts {
        pid: Some(std::process::id()),
        ..Default::default()
    };
    let found = Codex.locate(&facts).unwrap();
    assert_eq!(found.session, NEW);
    assert_eq!(
        found.path.canonicalize().unwrap(),
        path.canonicalize().unwrap()
    );
}

// The shared follow loop with this interpreter.

struct TestAgent(Rc<RefCell<Option<Located>>>);

impl Agent for TestAgent {
    fn name(&self) -> &'static str {
        "codex"
    }
    fn locate(&self, _: &TerminalFacts) -> Option<Located> {
        self.0.borrow().clone()
    }
    fn interpreter(&self) -> Box<dyn Interpreter> {
        Box::<CodexInterpreter>::default()
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

fn append(path: &Path, text: &str) {
    std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .unwrap()
        .write_all(text.as_bytes())
        .unwrap();
}

fn follow(path: &Path, cursor: Option<String>, steps: Vec<Box<dyn FnMut()>>) -> Vec<ChatLine> {
    let located = Rc::new(RefCell::new(Some(Located {
        session: NEW.into(),
        path: path.to_path_buf(),
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

fn line_items(lines: &[ChatLine]) -> Vec<ChatItem> {
    items(lines.iter().map(|l| l.event.clone()))
}

#[test]
fn every_rollout_reads_the_same_however_it_is_written() {
    let mut seed: u64 = 0xc0de;
    let mut next = move |n: usize| {
        seed = seed
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        (seed >> 33) as usize % n
    };
    for name in FIXTURES {
        let text = fixture(name);
        let expected = feed(name);
        assert!(!expected.is_empty(), "{name}");
        for round in 0..20 {
            let dir = tempfile::tempdir().unwrap();
            let path = dir.path().join("rollout.jsonl");
            let bytes = text.as_bytes().to_vec();
            let mut steps: Vec<Box<dyn FnMut()>> = Vec::new();
            let mut at = 0;
            while at < bytes.len() {
                let len = 1 + next(if round % 2 == 0 { 512 } else { 16384 });
                let end = (at + len).min(bytes.len());
                let chunk = bytes[at..end].to_vec();
                let path = path.clone();
                steps.push(Box::new(move || {
                    std::fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .open(&path)
                        .unwrap()
                        .write_all(&chunk)
                        .unwrap();
                }));
                at = end;
            }
            steps.push(Box::new(|| {}));
            let lines = follow(&path, None, steps);
            assert_eq!(line_items(&lines), expected, "{name} round {round}");
        }
    }
}

#[test]
fn resuming_from_a_cursor_neither_repeats_nor_skips() {
    for name in [
        "permission-exec",
        "rotation-new-diff-image",
        "question-then-resume",
    ] {
        let text = fixture(name);
        let lines: Vec<&str> = text.lines().collect();
        let expected = feed(name);
        for cut in 1..lines.len() {
            let dir = tempfile::tempdir().unwrap();
            let path = dir.path().join("rollout.jsonl");
            append(&path, &(lines[..cut].join("\n") + "\n"));
            let first = follow(&path, None, vec![Box::new(|| {})]);
            let cursor = first.iter().rev().find_map(|l| l.cursor.clone()).unwrap();
            append(&path, &(lines[cut..].join("\n") + "\n"));
            let second = follow(&path, Some(cursor), vec![Box::new(|| {})]);
            assert!(
                !second
                    .iter()
                    .any(|l| matches!(l.event, ChatEvent::Reset { .. })),
                "{name} cut {cut}"
            );
            let mut all = first;
            all.extend(second);
            assert_eq!(line_items(&all), expected, "{name} cut {cut}");
        }
    }
}

#[test]
fn a_rewritten_rollout_is_read_again_from_the_start() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("rollout.jsonl");
    let first = fixture("simple-reply");
    let second = fixture("error-model");
    append(&path, &first);
    let p = path.clone();
    let lines = follow(
        &path,
        None,
        vec![
            Box::new(|| {}),
            Box::new(move || std::fs::write(&p, &second).unwrap()),
            Box::new(|| {}),
        ],
    );
    assert_eq!(line_items(&lines), feed("error-model"));
}
