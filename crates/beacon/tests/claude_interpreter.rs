//! The Claude Code interpreter on the ticket-02 fixtures: transcript lines
//! in, Agent-neutral Chat items out.

use std::collections::HashMap;
use std::path::PathBuf;

use aries_beacon::chat::Interpreter;
use aries_beacon::chat::claude::ClaudeInterpreter;
use aries_protocol::chat::{
    ChatBody, ChatDiff, ChatEvent, ChatImage, ChatItem, ChatQuestion, DIFF_MAX_LINE_CHARS,
    DIFF_MAX_LINES, DiffHunk, IMAGE_MAX_BYTES, ToolStatus,
};
use serde_json::{Value, json};

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
                ChatEvent::Remove { id } => {
                    self.items.retain(|item| item.id != id);
                    self.index = self
                        .items
                        .iter()
                        .enumerate()
                        .map(|(i, item)| (item.id.clone(), i))
                        .collect();
                }
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
        ChatBody::User { text, .. } => format!("user: {}", cut(text)),
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
    assert_eq!(input["file_path"], "/tmp/aries-fixture/work-a/hello.txt");
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
        Some(
            "File does not exist. Note: your current working directory is /tmp/aries-fixture/work-a."
        )
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
    assert!(
        summary.contains(&"agent: resumed".to_string()),
        "{summary:#?}"
    );
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
            ChatBody::Tool {
                name, title, input, ..
            } if name == "AskUserQuestion" => {
                Some((title.clone(), input["questions"].as_array().unwrap().len()))
            }
            _ => None,
        })
        .unwrap();
    assert_eq!(tool.1, 2);
    assert!(!tool.0.is_empty());
}

fn question_of(chat: &Chat) -> (String, ToolStatus, ChatQuestion) {
    chat.items
        .iter()
        .find_map(|i| match &i.body {
            ChatBody::Tool {
                status,
                question: Some(q),
                ..
            } => Some((i.id.clone(), *status, q.clone())),
            _ => None,
        })
        .expect("a question tool")
}

#[test]
fn an_ask_user_question_is_an_open_question_until_answered() {
    let text = fixture("main-session.jsonl");
    let lines: Vec<&str> = text.lines().collect();
    let mut interp = ClaudeInterpreter::default();
    let mut chat = Chat::default();
    // Up to the AskUserQuestion tool_use (line 65), before its answer.
    for line in &lines[..65] {
        chat.apply(interp.line(line));
    }
    let (id, status, q) = question_of(&chat);
    assert_eq!(id, "toolu_012hzhteG39obz5TwRGEb26o");
    assert_eq!(status, ToolStatus::Running);
    assert_eq!(q.questions.len(), 1);
    let first = &q.questions[0];
    assert_eq!(first.text, "Which color do you prefer?");
    assert_eq!(first.header.as_deref(), Some("Color"));
    assert!(!first.multi);
    let labels: Vec<&str> = first.options.iter().map(|o| o.label.as_str()).collect();
    assert_eq!(labels, ["Red", "Blue", "Green"]);
    assert_eq!(first.options[1].description.as_deref(), Some("Choose Blue"));

    for line in &lines[65..] {
        chat.apply(interp.line(line));
    }
    let (_, status, _) = question_of(&chat);
    assert_eq!(status, ToolStatus::Done);
}

#[test]
fn a_multi_select_question_is_marked() {
    let chat = Chat::feed(&fixture("concurrent-same-cwd-multi-question.jsonl"));
    let (_, _, q) = question_of(&chat);
    let multi: Vec<(&str, bool, usize)> = q
        .questions
        .iter()
        .map(|q| (q.text.as_str(), q.multi, q.options.len()))
        .collect();
    assert_eq!(
        multi,
        [("Which size?", false, 2), ("Which toppings?", true, 3)]
    );
}

#[test]
fn other_tools_ask_nothing() {
    let chat = Chat::feed(&fixture("main-session.jsonl"));
    let asking: Vec<&str> = chat
        .items
        .iter()
        .filter_map(|i| match &i.body {
            ChatBody::Tool {
                name,
                question: Some(_),
                ..
            } => Some(name.as_str()),
            _ => None,
        })
        .collect();
    assert_eq!(asking, ["AskUserQuestion"]);
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
        for secret in [
            "redacted-hook-command",
            "session-owner",
            "signature",
            "api-request",
            "00000000-0000-4000-8000-000000000000",
        ] {
            assert!(!all.contains(secret), "{name} leaks {secret}");
        }
    }
}

fn tool<'a>(chat: &'a Chat, id: &str) -> (&'a Option<ChatDiff>, &'a Vec<ChatImage>) {
    match &chat.items[chat.index[id]].body {
        ChatBody::Tool { diff, images, .. } => (diff, images),
        other => panic!("{id} is {other:?}"),
    }
}

/// A Claude Code tool call line and its result line.
fn call_and_result(name: &str, input: Value, content: Value, result: Value) -> String {
    let call = json!({"type": "assistant", "uuid": "a1", "message": {"content": [
        {"type": "tool_use", "id": "t1", "name": name, "input": input}]}});
    let result = json!({"type": "user", "uuid": "u2", "message": {"content": [
        {"type": "tool_result", "tool_use_id": "t1", "content": content}]},
        "toolUseResult": result});
    format!("{call}\n{result}\n")
}

#[test]
fn an_edit_shows_as_its_diff() {
    let chat = Chat::feed(&fixture("main-session.jsonl"));
    let (diff, _) = tool(&chat, "toolu_01FrSiNjJuh1XEFmbmqwWPFR");
    assert_eq!(
        diff.as_ref().unwrap(),
        &ChatDiff {
            path: "/tmp/aries-fixture/work-a/hello.txt".into(),
            hunks: vec![DiffHunk {
                old_start: 1,
                old_lines: 1,
                new_start: 1,
                new_lines: 1,
                lines: vec!["-hi".into(), "+hello".into()],
            }],
            truncated: false,
        }
    );
}

#[test]
fn a_new_file_shows_as_all_added_lines() {
    let chat = Chat::feed(&fixture("main-session.jsonl"));
    let (diff, _) = tool(&chat, "toolu_01Tff5bVucbZw1bfv3n5KVhk");
    let diff = diff.as_ref().unwrap();
    assert_eq!(diff.path, "/tmp/aries-fixture/work-a/hello.txt");
    assert_eq!(
        diff.hunks,
        [DiffHunk {
            old_start: 0,
            old_lines: 0,
            new_start: 1,
            new_lines: 1,
            lines: vec!["+hi".into()],
        }]
    );
}

#[test]
fn a_read_picture_comes_with_its_image() {
    let chat = Chat::feed(&fixture("main-session.jsonl"));
    let (diff, images) = tool(&chat, "toolu_015qGcCimQaWpgMd6JgFmZEq");
    assert_eq!(*diff, None);
    assert_eq!(
        images,
        &[ChatImage {
            media_type: "image/png".into(),
            data: Some(
                "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQSACj/P8Fu7N9hAAAAAElFTkSuQmCC".into()
            ),
            bytes: 75,
            width: Some(8),
            height: Some(8),
        }]
    );
    // Reads of text files and failed tools carry neither.
    for id in [
        "toolu_01P6V5KH2PV6rekhK1PPh9eu",
        "toolu_012kC6jnHAabF5tLjG4fNc7b",
    ] {
        let (diff, images) = tool(&chat, id);
        assert!(diff.is_none() && images.is_empty(), "{id}");
    }
}

#[test]
fn an_image_over_the_cap_is_described_not_carried() {
    let size = (IMAGE_MAX_BYTES + 3) as usize;
    let data = "A".repeat(size.div_ceil(3) * 4);
    let text = call_and_result(
        "Read",
        json!({"file_path": "big.png"}),
        json!([{"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": data}}]),
        json!({"type": "image"}),
    );
    let chat = Chat::feed(&text);
    let (_, images) = tool(&chat, "t1");
    assert_eq!(images.len(), 1);
    assert_eq!(images[0].data, None);
    assert!(images[0].bytes > IMAGE_MAX_BYTES, "{}", images[0].bytes);
    let line = serde_json::to_string(&chat.items).unwrap();
    assert!(line.len() < 10_000, "the image leaked into the line");
}

#[test]
fn a_huge_edit_is_cut_to_the_line_cap() {
    let lines: Vec<String> = (0..DIFF_MAX_LINES + 500)
        .map(|i| format!("+line {i}"))
        .collect();
    let long = format!("+{}", "x".repeat(DIFF_MAX_LINE_CHARS * 3));
    let mut first = vec![long];
    first.extend(lines);
    let text = call_and_result(
        "Edit",
        json!({"file_path": "big.txt"}),
        json!("ok"),
        json!({"filePath": "big.txt", "structuredPatch": [
            {"oldStart": 1, "oldLines": 0, "newStart": 1, "newLines": first.len(), "lines": first},
            {"oldStart": 9000, "oldLines": 1, "newStart": 9000, "newLines": 1, "lines": ["-a", "+b"]}
        ]}),
    );
    let chat = Chat::feed(&text);
    let (diff, _) = tool(&chat, "t1");
    let diff = diff.as_ref().unwrap();
    assert!(diff.truncated);
    let total: usize = diff.hunks.iter().map(|h| h.lines.len()).sum();
    assert_eq!(total, DIFF_MAX_LINES);
    let first = &diff.hunks[0].lines[0];
    assert_eq!(first.chars().count(), DIFF_MAX_LINE_CHARS);
    assert!(first.ends_with('…'));
}

#[test]
fn a_pasted_image_shows_on_the_user_message() {
    let line = json!({"type": "user", "uuid": "u1", "message": {"content": [
        {"type": "text", "text": "what is this?"},
        {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "/9j/4AAQ"}}
    ]}});
    let chat = Chat::feed(&line.to_string());
    let ChatBody::User { text, images, .. } = &chat.items[0].body else {
        panic!("{:?}", chat.items)
    };
    assert_eq!(text, "what is this?");
    assert_eq!(images[0].media_type, "image/jpeg");
    assert_eq!(images[0].data.as_deref(), Some("/9j/4AAQ"));
    assert_eq!(images[0].bytes, 6);
    // An image alone is still a user message.
    let alone = json!({"type": "user", "uuid": "u1", "message": {"content": [
        {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "iVBO"}}
    ]}});
    let chat = Chat::feed(&alone.to_string());
    assert!(
        matches!(&chat.items[0].body, ChatBody::User { text, images, .. } if text.is_empty() && images.len() == 1)
    );
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

#[test]
fn answered_questions_keep_multiselect_other_and_notes_on_their_own_prompt() {
    // Regression: flattening tool_result hid selections and lost Other/notes
    // when reading an answer made on the Host (no phone-side picks exist).
    let mut interpreter = ClaudeInterpreter::default();
    let mut chat = Chat::default();
    chat.apply(interpreter.line(&json!({"type":"assistant","uuid":"a","message":{"content":[{
        "type":"tool_use","id":"q","name":"AskUserQuestion","input":{"questions":[
            {"question":"Scope?","header":"Scope","multiSelect":true,"options":[{"label":"Chat"},{"label":"Terminal"},{"label":"Calls"}]},
            {"question":"Color?","header":"Color","options":[{"label":"Blue"},{"label":"Red"}]}
        ]}
    }]}}).to_string()));
    chat.apply(interpreter.line(&json!({"type":"user","uuid":"u","message":{"content":[{
        "type":"tool_result","tool_use_id":"q","content":"User has answered your questions: \"Scope?\"=\"Chat, Calls\", \"Color?\"=\"Purple\". You can now continue."
    }]},"toolUseResult":{"answers":{"Color?":"Purple","Scope?":"Chat, Calls"},"annotations":{"Scope?":{"notes":"Keep it compact"}}}}).to_string()));
    assert_eq!(
        chat.items.len(),
        1,
        "a tool answer must never become a user message"
    );
    let item = serde_json::to_value(&chat.items[0]).unwrap();
    let questions = &item["question"]["questions"];
    assert_eq!(
        questions[0]["answer"],
        json!({"choices":[0,2],"notes":"Keep it compact"})
    );
    assert_eq!(
        questions[1]["answer"],
        json!({"choices":[],"text":"Purple"})
    );
}

#[test]
fn mid_turn_messages_appear_once_at_delivery_instead_of_disappearing() {
    let chat = Chat::feed(&fixture("mid-turn-messages.jsonl"));
    assert_eq!(
        chat.summary(),
        [
            "agent: I will inspect the view.",
            "agent: The file is ready.",
            "user: Please check the picture inside the tool",
        ]
    );
    let delivered = chat.items.last().unwrap();
    assert_eq!(delivered.id, "delivered-one");
    assert_eq!(delivered.ts.as_deref(), Some("2026-11-04T10:00:04.000Z"));
}

#[test]
fn an_undelivered_mid_turn_message_is_visible_as_queued() {
    let text = fixture("mid-turn-messages.jsonl");
    let prefix = text.lines().take(2).collect::<Vec<_>>().join("\n");
    let chat = Chat::feed(&prefix);
    assert_eq!(
        chat.summary(),
        [
            "agent: I will inspect the view.",
            "user: Please check the picture inside the tool",
        ]
    );
    assert_eq!(
        serde_json::to_value(&chat.items[1]).unwrap()["queued"],
        true
    );
}

#[test]
fn delivering_one_of_two_identical_messages_keeps_the_other_waiting() {
    let chat = Chat::feed(&fixture("repeated-queued-messages.jsonl"));
    assert_eq!(chat.items.len(), 2);
    assert_eq!(
        serde_json::to_value(&chat.items[0]).unwrap()["queued"],
        true
    );
    assert_eq!(chat.items[1].id, "delivered-one");
}

#[test]
fn queued_pasted_text_and_images_survive_delivery_before_the_queue_removal() {
    let chat = Chat::feed(&fixture("queued-media.jsonl"));
    assert_eq!(
        chat.items.len(),
        2,
        "queue entry, duplicate delivery and internal notification must not add rows"
    );
    let ChatBody::User {
        text,
        images,
        queued,
    } = &chat.items[0].body
    else {
        panic!()
    };
    assert!(!queued);
    assert_eq!(text, "Check this pasted note.\nKeep the second line.");
    assert_eq!(images[0].data.as_deref(), Some("iVBO"));
    assert_eq!(images[0].media_type, "image/png");
    let ChatBody::User { text, images, .. } = &chat.items[1].body else {
        panic!()
    };
    assert!(text.is_empty());
    assert_eq!(images.len(), 1);
}

#[test]
fn waiting_input_does_not_make_the_beacon_refuse_the_open_question() {
    let transcript = fixture("main-session.jsonl");
    let mut interp = ClaudeInterpreter::default();
    let mut chat = Chat::default();
    for line in transcript.lines().take(65) {
        chat.apply(interp.line(line));
    }
    let question = aries_beacon::chat::answer::open_question(&chat.items)
        .unwrap()
        .0
        .to_owned();
    let queued = fixture("mid-turn-messages.jsonl");
    chat.apply(interp.line(queued.lines().nth(1).unwrap()));
    assert_eq!(
        aries_beacon::chat::answer::open_question(&chat.items)
            .unwrap()
            .0,
        question
    );
    chat.apply(interp.line(queued.lines().nth(2).unwrap()));
    chat.apply(interp.line(queued.lines().nth(4).unwrap()));
    assert!(aries_beacon::chat::answer::open_question(&chat.items).is_none());
}

#[test]
fn a_rewritten_delivery_is_reconciled_by_the_removals_original_content() {
    let chat = Chat::feed(&fixture("queued-rewritten-prompt.jsonl"));
    assert_eq!(chat.items.len(), 1);
    assert_eq!(chat.items[0].id, "delivered-media");
}
