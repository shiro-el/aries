//! The Claude Code interpreter on the ticket-02 fixtures: transcript lines
//! in, Agent-neutral Chat items out.

use std::collections::HashMap;
use std::path::PathBuf;

use aries_beacon::chat::Interpreter;
use aries_beacon::chat::claude::ClaudeInterpreter;
use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ToolStatus};

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../fixtures/agents/claude-code")
        .join(name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// Applies item events the way a reader does (upsert by id, in order).
#[derive(Default)]
struct Chat {
    items: Vec<ChatItem>,
    index: HashMap<String, usize>,
}

impl Chat {
    fn apply(&mut self, events: Vec<ChatEvent>) {
        for e in events {
            match e {
                ChatEvent::Item { item } => match self.index.get(&item.id) {
                    Some(&i) => self.items[i] = item,
                    None => {
                        self.index.insert(item.id.clone(), self.items.len());
                        self.items.push(item);
                    }
                },
                other => panic!("interpreter emitted {other:?}"),
            }
        }
    }

    fn feed(text: &str) -> Self {
        let mut chat = Chat::default();
        let mut interp = ClaudeInterpreter::default();
        for line in text.lines() {
            chat.apply(interp.line(line));
        }
        chat
    }

    /// One short line per item, for comparing with what the user saw.
    fn summary(&self) -> Vec<String> {
        self.items.iter().map(|i| short(&i.body)).collect()
    }
}

fn short(body: &ChatBody) -> String {
    let cut = |s: &str| s.chars().take(40).collect::<String>();
    match body {
        ChatBody::User { text } => format!("user: {}", cut(text)),
        ChatBody::Agent { text } => format!("agent: {}", cut(text)),
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

#[test]
fn main_session_reads_as_the_conversation_the_user_had() {
    let chat = Chat::feed(&fixture("main-session.jsonl"));
    assert_eq!(
        chat.summary(),
        [
            "user: Reply with exactly: pong",
            "thinking: None",
            "agent: pong",
            "user: Create a file hello.txt containing the w",
            "tool Write Done: /tmp/aries-fixture/work-a/hello.txt",
            "agent: I created `hello.txt` in the working dir",
            "user: Use the AskUserQuestion tool to ask me o",
            "tool AskUserQuestion Done: Which color do you prefer?",
            "agent: Blue",
            "user: Read red.png and tell me its color in on",
            "thinking: None",
            "tool Read Done: /tmp/aries-fixture/work-a/red.png",
            "tool Read Error: /tmp/aries-fixture/work-a/missing.txt",
            "agent: Red\n\nReading `missing.txt` failed: the f",
            "user: Think step by step briefly, then use the",
            "thinking: None",
            "agent: I need to read `hello.txt` before editin",
            "tool Read Done: /tmp/aries-fixture/work-a/hello.txt",
            "tool Edit Done: /tmp/aries-fixture/work-a/hello.txt",
            "agent: I changed `hi` to `hello` in `hello.txt`",
        ]
    );
}

#[test]
fn a_tool_item_carries_its_full_input_and_result() {
    let chat = Chat::feed(&fixture("main-session.jsonl"));
    let ChatBody::Tool {
        input,
        output,
        status,
        ..
    } = &chat.items[chat.index["toolu_01Tff5bVucbZw1bfv3n5KVhk"]].body
    else {
        panic!("not a tool");
    };
    assert_eq!(*status, ToolStatus::Done);
    assert_eq!(input["content"], "hi\n");
    assert_eq!(
        input["file_path"],
        "/tmp/aries-fixture/work-a/hello.txt"
    );
    assert!(
        output
            .as_deref()
            .unwrap()
            .starts_with("File created successfully at: /tmp/aries-fixture/work-a/hello.txt")
    );
    let missing = &chat.items[chat.index["toolu_01P6V5KH2PV6rekhK1PPh9eu"]].body;
    let ChatBody::Tool { output, .. } = missing else {
        panic!("not a tool")
    };
    assert_eq!(
        output.as_deref(),
        Some("File does not exist. Note: your current working directory is /tmp/aries-fixture/work-a.")
    );
}

#[test]
fn a_tool_call_shows_running_until_its_result_line() {
    let text = fixture("main-session.jsonl");
    let lines: Vec<&str> = text.lines().collect();
    let mut interp = ClaudeInterpreter::default();
    let mut chat = Chat::default();
    // Up to and including the Write tool_use line (index 49), not its result.
    for line in &lines[..=49] {
        chat.apply(interp.line(line));
    }
    let ChatBody::Tool { status, output, .. } = &chat.items.last().unwrap().body else {
        panic!("last item is not the tool call");
    };
    assert_eq!((*status, output.as_deref()), (ToolStatus::Running, None));
}

#[test]
fn an_api_error_is_an_error_item() {
    let chat = Chat::feed(&fixture("api-error-bad-model.jsonl"));
    assert_eq!(
        chat.summary(),
        [
            "user: hi",
            "error: There's an issue with the selected model",
        ]
    );
}

#[test]
fn slash_commands_show_as_the_command_and_meta_lines_are_hidden() {
    let chat = Chat::feed(&fixture("after-clear-then-resumed.jsonl"));
    let summary = chat.summary();
    assert_eq!(summary[0], "user: /clear");
    assert_eq!(summary[1], "user: Reply with exactly: after-clear");
    assert!(summary.contains(&"agent: resumed".to_string()), "{summary:#?}");
    assert!(
        !summary.iter().any(|s| s.contains("local-command")),
        "{summary:#?}"
    );
}

#[test]
fn several_questions_title_with_the_first() {
    let chat = Chat::feed(&fixture("concurrent-same-cwd-multi-question.jsonl"));
    let tool = chat
        .items
        .iter()
        .find_map(|i| match &i.body {
            ChatBody::Tool { name, title, input, .. } if name == "AskUserQuestion" => {
                Some((title.clone(), input["questions"].as_array().unwrap().len()))
            }
            _ => None,
        })
        .unwrap();
    assert_eq!(tool.1, 2);
    assert!(!tool.0.is_empty());
}

#[test]
fn nothing_outside_the_conversation_leaks_into_the_chat() {
    // System prompts, account ids and hook commands live in non-conversation
    // records; none of their text may reach an item.
    for name in [
        "main-session.jsonl",
        "api-error-bad-model.jsonl",
        "after-clear-then-resumed.jsonl",
        "concurrent-same-cwd-multi-question.jsonl",
    ] {
        let chat = Chat::feed(&fixture(name));
        let all = serde_json::to_string(&chat.items).unwrap();
        for secret in ["redacted-hook-command", "session-owner", "signature", "api-request", "00000000-0000-4000-8000-000000000000"] {
            assert!(!all.contains(secret), "{name} leaks {secret}");
        }
    }
}

#[test]
fn unknown_and_broken_lines_are_ignored() {
    let mut interp = ClaudeInterpreter::default();
    for line in [
        "",
        "not json",
        r#"{"type":"brand-new-record","x":1}"#,
        r#"{"type":"assistant","message":{"content":"weird"}}"#,
        r#"{"type":"user"}"#,
        r#"[1,2,3]"#,
    ] {
        assert!(interp.line(line).is_empty(), "{line}");
    }
}
