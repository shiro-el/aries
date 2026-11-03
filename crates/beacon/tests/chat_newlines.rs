//! Multiline drafts stay one user item through each supported Chat reader.
use aries_beacon::chat::claude::ClaudeInterpreter;
use aries_beacon::chat::codex::CodexInterpreter;
use aries_beacon::chat::opencode::OpenCodeSource;
use aries_beacon::chat::pi::PiInterpreter;
use aries_beacon::chat::{Batch, Interpreter, Source};
use aries_protocol::chat::{ChatBody, ChatEvent};
use aries_test_support::opencode::{Db, db_path, fixture};
use serde_json::json;

const MESSAGE: &str = "첫 줄\n\nsecond line\n마지막 줄";

fn assert_one_message(events: Vec<ChatEvent>) {
    let users: Vec<_> = events
        .into_iter()
        .filter_map(|e| match e {
            ChatEvent::Item { item } => match item.body {
                ChatBody::User { text, .. } => Some(text),
                _ => None,
            },
            _ => None,
        })
        .collect();
    assert_eq!(users, vec![MESSAGE]);
}

#[test]
fn claude_keeps_newlines_in_one_message() {
    let line = json!({"type":"user", "uuid":"u1", "message":{"content":[
        {"type":"text", "text":MESSAGE}
    ]}});
    assert_one_message(ClaudeInterpreter::default().line(&line.to_string()));
}

#[test]
fn codex_keeps_newlines_in_one_message() {
    let line = json!({"type":"event_msg", "payload":{"type":"item_completed", "item":{
        "type":"UserMessage", "id":"u1", "content":[{"type":"text", "text":MESSAGE}]
    }}});
    assert_one_message(CodexInterpreter::default().line(&line.to_string()));
}

#[test]
fn pi_keeps_newlines_in_one_message() {
    let line = json!({"type":"message", "id":"u1", "parentId":null, "message":{
        "role":"user", "content":[{"type":"text", "text":MESSAGE}]
    }});
    assert_one_message(PiInterpreter::default().line(&line.to_string()));
}

#[test]
fn opencode_keeps_newlines_in_one_message() {
    let mut f = fixture("tui-permission-question-diff");
    let message = f["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["data"]["role"] == "user")
        .unwrap()
        .clone();
    let mut part = f["parts"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["message_id"] == message["id"] && p["data"]["type"] == "text")
        .unwrap()
        .clone();
    part["data"]["text"] = json!(MESSAGE);
    f["messages"] = json!([message]);
    f["parts"] = json!([part]);
    let dir = tempfile::tempdir().unwrap();
    let db = Db::create(&db_path(dir.path()));
    db.load(&f);
    let mut source =
        OpenCodeSource::new(db.path.clone(), f["session"]["id"].as_str().unwrap().into());
    let Batch::Events { events, .. } = source.poll().unwrap() else {
        panic!("missing transcript")
    };
    assert_one_message(events.into_iter().map(|(e, _)| e).collect());
}
