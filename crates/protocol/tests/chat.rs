//! The Chat line format and its version rule, from the reader's side.

use aries_protocol::chat::{
    CHAT_FORMAT, ChatBody, ChatDiff, ChatEvent, ChatImage, ChatItem, ChatLine, ChatLineError,
    ChatQuestion, ChatState, DiffHunk, Question, QuestionOption, ToolStatus,
};
use serde_json::json;

#[test]
fn a_tool_item_line_reads_back_with_its_cursor() {
    let line = r#"{"v":1,"type":"item","cursor":"c:9","item":{"id":"toolu_1","ts":"2026-11-03T06:18:37Z","kind":"tool","name":"Write","title":"hello.txt","input":{"file_path":"hello.txt"},"status":"done","output":"File created"}}"#;
    assert_eq!(
        ChatLine::parse(line).unwrap(),
        ChatLine {
            v: 1,
            cursor: Some("c:9".into()),
            event: ChatEvent::Item {
                item: ChatItem {
                    id: "toolu_1".into(),
                    ts: Some("2026-11-03T06:18:37Z".into()),
                    body: ChatBody::Tool {
                        name: "Write".into(),
                        title: "hello.txt".into(),
                        input: json!({"file_path": "hello.txt"}),
                        status: ToolStatus::Done,
                        output: Some("File created".into()),
                        diff: None,
                        images: Vec::new(),
                        question: None,
                    },
                },
            },
        }
    );
}

#[test]
fn a_question_tool_carries_its_questions_and_options() {
    let line = r#"{"v":1,"type":"item","item":{"id":"toolu_q","kind":"tool","name":"AskUserQuestion","title":"Which?","status":"running","question":{"questions":[{"text":"Which?","header":"Pick","multi":false,"options":[{"label":"Red","description":"Choose Red"},{"label":"Blue"}]}]}}}"#;
    let ChatEvent::Item { item } = ChatLine::parse(line).unwrap().event else {
        panic!("not an item");
    };
    let ChatBody::Tool {
        question: Some(q),
        status: ToolStatus::Running,
        ..
    } = item.body
    else {
        panic!("no question: {item:?}");
    };
    assert_eq!(
        q,
        ChatQuestion {
            questions: vec![Question {
                text: "Which?".into(),
                header: Some("Pick".into()),
                multi: false,
                options: vec![
                    QuestionOption {
                        label: "Red".into(),
                        description: Some("Choose Red".into()),
                    },
                    QuestionOption {
                        label: "Blue".into(),
                        description: None,
                    },
                ],
            }],
        }
    );
    // A plain tool line has no question and writes none.
    let plain = ChatBody::Tool {
        name: "Bash".into(),
        title: "ls".into(),
        input: json!({}),
        status: ToolStatus::Done,
        output: None,
        diff: None,
        images: Vec::new(),
        question: None,
    };
    assert!(!serde_json::to_string(&plain).unwrap().contains("question"));
}

#[test]
fn written_lines_parse_back_unchanged() {
    let lines = [
        ChatLine::new(ChatEvent::Reset {
            agent: "claude-code".into(),
            session: "s1".into(),
        }),
        ChatLine::new(ChatEvent::Item {
            item: ChatItem {
                id: "u1".into(),
                ts: None,
                body: ChatBody::Thinking { text: None },
            },
        })
        .with_cursor("x"),
        ChatLine::new(ChatEvent::State {
            state: ChatState::Waiting,
        }),
        ChatLine::new(ChatEvent::Remove { id: "u1".into() }),
        ChatLine::new(ChatEvent::Heartbeat),
    ];
    for line in lines {
        let text = line.to_json();
        assert!(text.starts_with(r#"{"v":1,"#), "{text}");
        assert_eq!(ChatLine::parse(&text).unwrap(), line);
    }
}

#[test]
fn additions_within_the_same_version_are_tolerated() {
    // A newer Beacon of the same format: extra fields, a new item kind,
    // a new event type, a new state, a new tool status.
    let extra = r#"{"v":1,"type":"item","later":true,"item":{"id":"a","kind":"agent","text":"hi","lang":"en"}}"#;
    assert!(matches!(
        ChatLine::parse(extra).unwrap().event,
        ChatEvent::Item { item: ChatItem { body: ChatBody::Agent { ref text }, .. } } if text == "hi"
    ));
    let kind = r#"{"v":1,"type":"item","item":{"id":"q","kind":"question","questions":[]}}"#;
    assert!(matches!(
        ChatLine::parse(kind).unwrap().event,
        ChatEvent::Item {
            item: ChatItem {
                body: ChatBody::Unsupported,
                ..
            }
        }
    ));
    let event = r#"{"v":1,"type":"typing","who":"agent"}"#;
    assert_eq!(ChatLine::parse(event).unwrap().event, ChatEvent::Unknown);
    let state = r#"{"v":1,"type":"state","state":"compacting"}"#;
    assert_eq!(
        ChatLine::parse(state).unwrap().event,
        ChatEvent::State {
            state: ChatState::Unknown
        }
    );
    let status =
        r#"{"v":1,"type":"item","item":{"id":"t","kind":"tool","name":"x","status":"queued"}}"#;
    assert!(matches!(
        ChatLine::parse(status).unwrap().event,
        ChatEvent::Item {
            item: ChatItem {
                body: ChatBody::Tool {
                    status: ToolStatus::Unknown,
                    ..
                },
                ..
            }
        }
    ));
}

#[test]
fn a_newer_format_version_is_refused() {
    let line = format!(r#"{{"v":{},"type":"heartbeat"}}"#, CHAT_FORMAT + 1);
    assert_eq!(
        ChatLine::parse(&line),
        Err(ChatLineError::NewerFormat(CHAT_FORMAT + 1))
    );
}

#[test]
fn non_chat_lines_are_malformed() {
    for line in ["login banner", r#"{"type":"heartbeat"}"#, "{}"] {
        assert!(
            matches!(ChatLine::parse(line), Err(ChatLineError::Malformed(_))),
            "{line}"
        );
    }
}

#[test]
fn an_edit_carries_its_diff_and_a_read_its_image() {
    let line = r#"{"v":1,"type":"item","item":{"id":"t","kind":"tool","name":"Edit","title":"a.txt","input":{},"status":"done","output":"ok","diff":{"path":"a.txt","hunks":[{"old_start":1,"old_lines":1,"new_start":1,"new_lines":1,"lines":["-hi","+hello"]}]},"images":[{"media_type":"image/png","data":"iVBO","bytes":3,"width":8,"height":8}]}}"#;
    let ChatEvent::Item { item } = ChatLine::parse(line).unwrap().event else {
        panic!()
    };
    let ChatBody::Tool { diff, images, .. } = item.body else {
        panic!()
    };
    assert_eq!(
        diff,
        Some(ChatDiff {
            path: "a.txt".into(),
            hunks: vec![DiffHunk {
                old_start: 1,
                old_lines: 1,
                new_start: 1,
                new_lines: 1,
                lines: vec!["-hi".into(), "+hello".into()],
            }],
            truncated: false,
        })
    );
    assert_eq!(
        images,
        [ChatImage {
            media_type: "image/png".into(),
            data: Some("iVBO".into()),
            bytes: 3,
            width: Some(8),
            height: Some(8),
        }]
    );
}

#[test]
fn a_user_line_may_carry_images_and_old_lines_still_read() {
    let with = r#"{"v":1,"type":"item","item":{"id":"u","kind":"user","text":"look","images":[{"media_type":"image/jpeg","bytes":9000000}]}}"#;
    let ChatEvent::Item { item } = ChatLine::parse(with).unwrap().event else {
        panic!()
    };
    let ChatBody::User { text, images } = item.body else {
        panic!()
    };
    assert_eq!(text, "look");
    // Over the size cap: described, not carried.
    assert_eq!(images[0].data, None);
    assert_eq!(images[0].bytes, 9_000_000);
    // A ticket-37 Beacon's lines have neither field.
    let old = r#"{"v":1,"type":"item","item":{"id":"u","kind":"user","text":"hi"}}"#;
    let ChatEvent::Item { item } = ChatLine::parse(old).unwrap().event else {
        panic!()
    };
    assert_eq!(
        item.body,
        ChatBody::User {
            text: "hi".into(),
            images: Vec::new()
        }
    );
    // Writing without them adds nothing a ticket-37 reader would see.
    assert_eq!(ChatLine::new(ChatEvent::Item { item }).to_json(), old);
    // A shape this reader does not understand reads as absent, not as a
    // lost message.
    let odd = r#"{"v":1,"type":"item","item":{"id":"t","kind":"tool","name":"Edit","status":"done","diff":[1],"images":{"x":1}}}"#;
    let ChatEvent::Item { item } = ChatLine::parse(odd).unwrap().event else {
        panic!()
    };
    assert!(matches!(
        item.body,
        ChatBody::Tool { diff: None, ref images, .. } if images.is_empty()
    ));
}
