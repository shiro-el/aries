//! OpenCode Chat: the ticket-04 database rows read through the Beacon's
//! OpenCode source, streamed rows rewritten in place, cursors, `/new`, and
//! answering its question dock.

mod common;

use std::cell::RefCell;
use std::collections::VecDeque;
use std::rc::Rc;
use std::time::Duration;

use aries_beacon::chat::opencode::{OpenCode, OpenCodeSource, SESSION_LABEL};
use aries_beacon::chat::{
    Agent, Batch, KeysError, Options, Source, TerminalFacts, TerminalInput, TerminalLookup,
    answer, run,
};
use aries_protocol::agent_status::StatusRecord;
use aries_protocol::answer::{Answer, AnswerOutcome};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ChatLine, ChatState, ToolStatus};
use common::opencode_db::{Db, db_path, fixture, parts_of, row};
use serde_json::{Value, json};

const MAIN: &str = "ses_e5fed9273ffe3XexHeUg20WxR5";

/// Items in order of first appearance, upserted by id.
#[derive(Default, Debug)]
struct Chat {
    items: Vec<ChatItem>,
    resets: usize,
}

impl Chat {
    fn apply(&mut self, event: ChatEvent) {
        match event {
            ChatEvent::Item { item } => match self.items.iter_mut().find(|i| i.id == item.id) {
                Some(slot) => *slot = item,
                None => self.items.push(item),
            },
            ChatEvent::Reset { .. } => {
                self.items.clear();
                self.resets += 1;
            }
            _ => {}
        }
    }

    fn drain<S: Source + ?Sized>(&mut self, source: &mut S) -> Vec<Option<String>> {
        match source.poll().unwrap() {
            Batch::Events { events, .. } => events
                .into_iter()
                .map(|(e, pos)| {
                    self.apply(e);
                    pos
                })
                .collect(),
            Batch::Missing => panic!("missing"),
            Batch::Replaced => panic!("replaced"),
        }
    }

    fn summary(&self) -> Vec<String> {
        self.items.iter().map(|i| short(&i.body)).collect()
    }

    fn get(&self, suffix: &str) -> &ChatItem {
        self.items
            .iter()
            .find(|i| i.id.ends_with(suffix))
            .unwrap_or_else(|| panic!("no item *{suffix} in {:#?}", self.summary()))
    }
}

fn short(body: &ChatBody) -> String {
    match body {
        ChatBody::User { text, images } => format!("user {text} +{}img", images.len()),
        ChatBody::Agent { text } => format!("agent {text}"),
        ChatBody::Thinking { text } => format!("thinking {}", text.as_deref().unwrap_or("")),
        ChatBody::Tool {
            name,
            title,
            status,
            ..
        } => format!("tool {name} {title} {status:?}"),
        ChatBody::Error { message } => format!("error {message}"),
        other => format!("{other:?}"),
    }
}

fn read_all(f: &Value) -> Chat {
    let dir = tempfile::tempdir().unwrap();
    let db = Db::create(&db_path(dir.path()));
    db.load(f);
    let id = f["session"]["id"].as_str().unwrap().to_owned();
    let mut source = OpenCodeSource::new(db.path.clone(), id);
    let mut chat = Chat::default();
    chat.drain(&mut source);
    chat
}

#[test]
fn a_tui_session_reads_as_the_conversation_the_user_had() {
    let chat = read_all(&fixture("tui-permission-question-diff"));
    let summary = chat.summary();
    assert_eq!(
        summary,
        [
            "user Use the bash tool to run: date > d.txt. Then say done. +0img",
            "thinking The user wants me to run a bash command. Simple.",
            "tool bash date > d.txt Done",
            "agent Done.",
            "user Use the question tool to ask me which color I prefer, with options Red, Green, Blue. Do nothing else. +0img",
            "tool question Which color do you prefer? Done",
            "thinking The user asked me to do nothing else, so I should just acknowledge briefly? They said \"Do nothing else.\" The question was asked and answered. I'll keep it minimal.",
            "agent Green it is.",
            "user Use the edit tool to change 'line two' to 'line 2' in notes.txt. +0img",
            "tool read /tmp/aries-mvp-run/oc04/work/notes.txt Done",
            "tool edit /tmp/aries-mvp-run/oc04/work/notes.txt Error",
            "user Try again: use the edit tool to change 'line two' to 'line 2' in notes.txt. +0img",
            "thinking The user rejected permission, now asks to try again. Same edit.",
            "tool edit /tmp/aries-mvp-run/oc04/work/notes.txt Done",
            "agent Changed.",
            "user Say only: resumed. +0img",
            "agent resumed.",
        ],
        "{summary:#?}"
    );
    // Timestamps are the rows' own times.
    assert_eq!(
        chat.items[0].ts.as_deref(),
        Some("2026-11-03T06:22:04.695Z")
    );
}

#[test]
fn a_tool_carries_its_input_result_and_diff() {
    let chat = read_all(&fixture("tui-permission-question-diff"));
    let ChatBody::Tool { input, output, .. } = &chat.get("fzvkJr").body else {
        panic!()
    };
    assert_eq!(input, &json!({"command": "date > d.txt"}));
    assert_eq!(output.as_deref(), Some("(no output)"));

    let ChatBody::Tool { output, diff, .. } = &chat.get("xfRgyh").body else {
        panic!()
    };
    assert_eq!(
        output.as_deref(),
        Some("The user rejected permission to use this specific tool call.")
    );
    assert!(diff.is_none(), "a rejected edit changed nothing");

    let ChatBody::Tool { diff, .. } = &chat.get("bAKkGf").body else {
        panic!()
    };
    let diff = diff.as_ref().expect("the edit's diff");
    assert_eq!(diff.path, "/tmp/aries-mvp-run/oc04/work/notes.txt");
    assert_eq!(
        diff.hunks[0].lines,
        [" line one", "-line two", "+line 2", " line three"]
    );
    assert!(!diff.truncated);
}

#[test]
fn the_question_tool_is_a_question_with_its_options() {
    let chat = read_all(&fixture("tui-permission-question-diff"));
    let ChatBody::Tool {
        question, output, ..
    } = &chat.get("b4enhf").body
    else {
        panic!()
    };
    let q = question.as_ref().expect("a question");
    assert_eq!(q.questions.len(), 1);
    let q0 = &q.questions[0];
    assert_eq!(q0.text, "Which color do you prefer?");
    assert_eq!(q0.header.as_deref(), Some("Color preference"));
    assert!(!q0.multi);
    let labels: Vec<&str> = q0.options.iter().map(|o| o.label.as_str()).collect();
    assert_eq!(labels, ["Red", "Green", "Blue"]);
    assert_eq!(q0.options[1].description.as_deref(), Some("The color green"));
    assert!(output.as_deref().unwrap().contains("\"Green\""));
}

#[test]
fn an_attached_image_shows_on_the_user_message() {
    let chat = read_all(&fixture("run-image"));
    assert_eq!(
        chat.summary(),
        [
            "user \"What color is this image? One word.\" +1img",
            "thinking The user is asking about the color of an image they've provided via a Read tool result. The image is clearly red - it's a solid red image. The user wants a one-word answer.\n\nThe answer is simply \"Red\".",
            "agent Red",
        ]
    );
    let ChatBody::User { images, .. } = &chat.items[0].body else {
        panic!()
    };
    assert_eq!(images[0].media_type, "image/png");
    assert!(images[0].data.as_deref().unwrap().starts_with("iVBORw0KGgo"));
    assert!(images[0].bytes > 0);
}

#[test]
fn a_failed_turn_is_an_error_item() {
    let chat = read_all(&fixture("tui-api-error"));
    assert_eq!(
        chat.summary(),
        [
            "user hello +0img",
            "error Cannot connect to API: ECONNREFUSED: Unable to connect. Is the computer able to access the url?",
        ]
    );
}

/// A row rewritten in place: same item id, the newest content.
fn bump(row: &Value, t: i64, data: Value) -> Value {
    let mut r = row.clone();
    r["time_updated"] = json!(t);
    r["data"] = data;
    r
}

#[test]
fn rows_rewritten_while_streaming_resend_the_same_item() {
    let f = fixture("tui-permission-question-diff");
    let dir = tempfile::tempdir().unwrap();
    let db = Db::create(&db_path(dir.path()));
    db.session(&f["session"]);
    let mut user = row(&f, "messages", "md1Dbt");
    user["time_updated"] = user["time_created"].clone();
    // Message rows are stamped again when their turn ends: so far, only created.
    let mut asst = row(&f, "messages", "AVI2rO");
    asst["time_updated"] = asst["time_created"].clone();
    db.message(&user);
    for p in parts_of(&f, &user) {
        db.part(&p);
    }
    db.message(&asst);
    let mut source = OpenCodeSource::new(db.path.clone(), MAIN.into());
    let mut chat = Chat::default();
    chat.drain(&mut source);
    assert_eq!(chat.items.len(), 1);

    // The question appears running (the dock is up), then is answered.
    let q = row(&f, "parts", "b4enhf");
    let mut running = q["data"].clone();
    running["state"] = json!({
        "status": "running",
        "input": q["data"]["state"]["input"],
        "time": {"start": 1793686948991i64}
    });
    db.part(&bump(&q, 1793686948992, running));
    chat.drain(&mut source);
    let ChatBody::Tool {
        status, question, ..
    } = &chat.get("b4enhf").body
    else {
        panic!()
    };
    assert_eq!(*status, ToolStatus::Running);
    assert!(question.is_some());

    db.part(&q);
    chat.drain(&mut source);
    assert_eq!(chat.items.len(), 2, "{:#?}", chat.summary());
    let ChatBody::Tool { status, .. } = &chat.get("b4enhf").body else {
        panic!()
    };
    assert_eq!(*status, ToolStatus::Done);

    // Text streams into one part.
    let reply = row(&f, "messages", "d5rBVR");
    db.message(&reply);
    let text = row(&f, "parts", "D2NCQq");
    db.part(&bump(&text, 1793686954110, json!({"type": "text", "text": "Gre"})));
    chat.drain(&mut source);
    assert_eq!(chat.summary().last().unwrap(), "agent Gre");
    db.part(&text);
    chat.drain(&mut source);
    assert_eq!(chat.summary().last().unwrap(), "agent Green it is.");
    assert_eq!(chat.items.len(), 3);

    // Nothing new: nothing sent.
    assert!(chat.drain(&mut source).is_empty());
}

#[test]
fn a_part_stamped_older_than_one_already_read_is_not_missed() {
    let f = fixture("tui-permission-question-diff");
    let dir = tempfile::tempdir().unwrap();
    let db = Db::create(&db_path(dir.path()));
    db.session(&f["session"]);
    let mut user = row(&f, "messages", "C52pks");
    user["time_updated"] = user["time_created"].clone();
    let mut asst = row(&f, "messages", "U9LtML");
    asst["time_updated"] = asst["time_created"].clone();
    db.message(&user);
    db.message(&asst);
    db.part(&row(&f, "parts", "fzvkJr")); // stamped 932231
    let mut source = OpenCodeSource::new(db.path.clone(), MAIN.into());
    let mut chat = Chat::default();
    chat.drain(&mut source);
    // Another writer's part, stamped a moment earlier, commits after that
    // read.
    db.part(&bump(
        &row(&f, "parts", "QpfZTc"),
        1793686931500,
        row(&f, "parts", "QpfZTc")["data"].clone(),
    ));
    chat.drain(&mut source);
    assert!(chat.summary().iter().any(|s| s.starts_with("thinking")));
}

#[test]
fn a_cursor_resumes_without_repeating_old_rows() {
    let f = fixture("tui-permission-question-diff");
    let dir = tempfile::tempdir().unwrap();
    let db = Db::create(&db_path(dir.path()));
    db.load(&f);
    let agent = OpenCode {
        data_home: Some(dir.path().to_owned()),
    };
    let located = agent.locate(&facts(MAIN)).unwrap();

    let opened = agent.open(&located, None);
    assert!(!opened.resumed);
    let mut source: Box<dyn Source> = opened.source;
    let mut chat = Chat::default();
    let positions = chat.drain(source.as_mut());
    assert_eq!(chat.items.len(), 17);
    let cursor = positions.last().unwrap().clone().expect("a resume point");
    assert_eq!(cursor, source.position());

    // An app that saw all that reconnects; one more turn arrives.
    let opened = agent.open(&located, Some(&cursor));
    assert!(opened.resumed);
    let mut source: Box<dyn Source> = opened.source;
    let mut later = Chat::default();
    later.drain(source.as_mut());
    let rotation = fixture("tui-new-session-rotation");
    let mut m = rotation["messages"][0].clone();
    m["session_id"] = json!(MAIN);
    db.message(&m);
    for p in parts_of(&rotation, &rotation["messages"][0]) {
        let mut p = p.clone();
        p["session_id"] = json!(MAIN);
        db.part(&p);
    }
    later.drain(source.as_mut());
    let users: Vec<String> = later
        .summary()
        .into_iter()
        .filter(|s| s.starts_with("user"))
        .collect();
    assert_eq!(users.last().unwrap(), "user Say only: fresh. +0img");
    assert!(
        !later.summary().iter().any(|s| s.contains("date > d.txt")),
        "old rows came back: {:#?}",
        later.summary()
    );

    // A cursor into a session the database does not have: start over.
    let gone = aries_beacon::chat::Located {
        session: "ses_gone".into(),
        path: located.path.clone(),
    };
    assert!(!agent.open(&gone, Some(&cursor)).resumed);
}

fn facts(session: &str) -> TerminalFacts {
    let mut labels = serde_json::Map::new();
    labels.insert(SESSION_LABEL.into(), json!(session));
    TerminalFacts {
        foreground_argv: vec!["opencode".into()],
        labels,
        ..Default::default()
    }
}

#[test]
fn pairs_by_label_or_resume_argument_only_while_opencode_runs() {
    let agent = OpenCode {
        data_home: Some("/data".into()),
    };
    let located = agent.locate(&facts("ses_1")).unwrap();
    assert_eq!(located.session, "ses_1");
    assert_eq!(
        located.path,
        std::path::Path::new("/data/opencode/opencode.db")
    );

    // Resumed, before its first prompt: no label yet.
    let resumed = TerminalFacts {
        foreground_argv: vec!["opencode".into(), "-s".into(), "ses_2".into()],
        ..Default::default()
    };
    assert_eq!(agent.locate(&resumed).unwrap().session, "ses_2");

    // A fresh OpenCode before its first prompt has no session yet.
    let fresh = TerminalFacts {
        foreground_argv: vec!["opencode".into()],
        ..Default::default()
    };
    assert!(agent.locate(&fresh).is_none());

    // OpenCode exited; its label stays on the Terminal.
    let mut exited = facts("ses_1");
    exited.foreground_argv = vec!["bash".into()];
    assert!(agent.locate(&exited).is_none());
}

/// Follows through the shared loop: one step per poll.
struct Script(VecDeque<Box<dyn FnMut() -> TerminalFacts>>);

impl TerminalLookup for Script {
    fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
        Ok(self.0.pop_front().map(|mut step| step()))
    }
}

fn lines(out: &[u8]) -> Vec<ChatLine> {
    String::from_utf8_lossy(out)
        .lines()
        .map(|l| ChatLine::parse(l).unwrap())
        .collect()
}

#[test]
fn new_starts_a_new_chat_and_the_old_one_ends_with_the_program() {
    let dir = tempfile::tempdir().unwrap();
    let db = Rc::new(Db::create(&db_path(dir.path())));
    let main = fixture("tui-permission-question-diff");
    let fresh = fixture("tui-new-session-rotation");
    db.load(&main);
    let fresh_id = fresh["session"]["id"].as_str().unwrap().to_owned();
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(OpenCode {
        data_home: Some(dir.path().to_owned()),
    })];

    let label = Rc::new(RefCell::new(MAIN.to_owned()));
    let mut steps: VecDeque<Box<dyn FnMut() -> TerminalFacts>> = VecDeque::new();
    let l = label.clone();
    steps.push_back(Box::new(move || facts(&l.borrow())));
    // `/new`: the plugin relabels at the first prompt; the session row
    // comes a moment before its messages.
    let (l, d, s) = (label.clone(), db.clone(), fresh["session"].clone());
    steps.push_back(Box::new(move || {
        d.session(&s);
        *l.borrow_mut() = s["id"].as_str().unwrap().to_owned();
        facts(&l.borrow())
    }));
    let (l, d, f) = (label.clone(), db.clone(), fresh.clone());
    steps.push_back(Box::new(move || {
        d.load(&f);
        facts(&l.borrow())
    }));
    let l = label.clone();
    steps.push_back(Box::new(move || {
        let mut gone = facts(&l.borrow());
        gone.foreground_argv = vec!["bash".into()];
        gone
    }));

    let mut out = Vec::new();
    let mut opts = Options::new("t1");
    opts.follow = true;
    opts.poll = Duration::from_millis(1);
    run(opts, &mut Script(steps), &agents, &mut out).unwrap();
    let lines = lines(&out);

    let resets: Vec<String> = lines
        .iter()
        .filter_map(|l| match &l.event {
            ChatEvent::Reset { agent, session } => {
                assert_eq!(agent, "opencode");
                Some(session.clone())
            }
            _ => None,
        })
        .collect();
    assert_eq!(resets, [MAIN.to_string(), fresh_id.clone()]);
    let states: Vec<ChatState> = lines
        .iter()
        .filter_map(|l| match l.event {
            ChatEvent::State { state } => Some(state),
            _ => None,
        })
        .collect();
    assert_eq!(
        states,
        [
            ChatState::Live,
            ChatState::Live,
            ChatState::Ended,
            ChatState::NoTerminal
        ]
    );
    let mut chat = Chat::default();
    for l in &lines {
        chat.apply(l.event.clone());
    }
    assert_eq!(chat.resets, 2);
    assert_eq!(
        chat.summary(),
        ["user Say only: fresh. +0img", "agent fresh"]
    );
    let cursor = lines.iter().rev().find_map(|l| l.cursor.clone()).unwrap();
    assert!(cursor.starts_with(&format!("opencode:{fresh_id}:")), "{cursor}");
}

#[test]
fn a_missing_database_waits() {
    let dir = tempfile::tempdir().unwrap();
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(OpenCode {
        data_home: Some(dir.path().to_owned()),
    })];
    let steps: VecDeque<Box<dyn FnMut() -> TerminalFacts>> =
        VecDeque::from([Box::new(|| facts(MAIN)) as Box<dyn FnMut() -> TerminalFacts>]);
    let mut out = Vec::new();
    run(Options::new("t1"), &mut Script(steps), &agents, &mut out).unwrap();
    let lines = lines(&out);
    assert!(matches!(lines[0].event, ChatEvent::Reset { .. }));
    assert!(matches!(
        lines[1].event,
        ChatEvent::State {
            state: ChatState::Waiting
        }
    ));
}

/// A Terminal blocked on a question, recording the keys it is sent.
struct Link {
    facts: TerminalFacts,
    pressed: Vec<Vec<String>>,
}

impl TerminalLookup for Link {
    fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
        Ok(Some(self.facts.clone()))
    }
}

impl TerminalInput for Link {
    fn keys(&mut self, _: &str, keys: &[String]) -> Result<(), String> {
        self.pressed.push(keys.to_vec());
        Ok(())
    }
}

#[test]
fn answers_the_open_question_with_its_number() {
    let f = fixture("tui-permission-question-diff");
    let dir = tempfile::tempdir().unwrap();
    let db = Db::create(&db_path(dir.path()));
    db.session(&f["session"]);
    for id in ["md1Dbt", "AVI2rO"] {
        let m = row(&f, "messages", id);
        db.message(&m);
        for p in parts_of(&f, &m) {
            db.part(&p);
        }
    }
    let q = row(&f, "parts", "b4enhf");
    let mut running = q["data"].clone();
    running["state"]["status"] = json!("running");
    db.part(&bump(&q, 1793686948992, running));

    let agents: Vec<Box<dyn Agent>> = vec![Box::new(OpenCode {
        data_home: Some(dir.path().to_owned()),
    })];
    let mut facts = facts(MAIN);
    facts.status = Some(StatusRecord {
        state: "blocked".into(),
        kind: Some("question".into()),
        ..serde_json::from_value(json!({"state": "blocked"})).unwrap()
    });
    let mut link = Link {
        facts,
        pressed: Vec::new(),
    };
    let qid = q["id"].as_str().unwrap();
    let pick = |n: usize| Answer {
        question: qid.into(),
        picks: vec![vec![n]],
    };

    let reply = answer::answer("t1", &pick(5), &mut link, &agents).unwrap();
    assert_eq!(reply.outcome, AnswerOutcome::Invalid);
    let reply = answer::answer("t1", &pick(1), &mut link, &agents).unwrap();
    assert_eq!(reply.outcome, AnswerOutcome::Sent, "{reply:?}");
    assert_eq!(link.pressed, [vec!["2".to_string()]]);

    // Answered: the part completes; a second answer presses nothing.
    db.part(&q);
    let reply = answer::answer("t1", &pick(1), &mut link, &agents).unwrap();
    assert_eq!(reply.outcome, AnswerOutcome::Answered);
    assert_eq!(link.pressed.len(), 1);
}

#[test]
fn only_one_single_choice_question_maps_to_keys() {
    let agent = OpenCode { data_home: None };
    let q = |multi: bool, n: usize, count: usize| aries_protocol::chat::ChatQuestion {
        questions: (0..count)
            .map(|_| aries_protocol::chat::Question {
                text: "q".into(),
                header: None,
                multi,
                options: (0..n)
                    .map(|i| aries_protocol::chat::QuestionOption {
                        label: format!("o{i}"),
                        description: None,
                    })
                    .collect(),
            })
            .collect(),
    };
    assert_eq!(
        agent.answer_keys(&q(false, 3, 1), &[vec![2]]),
        Ok(vec!["3".to_string()])
    );
    assert!(matches!(
        agent.answer_keys(&q(true, 3, 1), &[vec![0, 1]]),
        Err(KeysError::Unsupported(_))
    ));
    assert!(matches!(
        agent.answer_keys(&q(false, 3, 2), &[vec![0], vec![1]]),
        Err(KeysError::Unsupported(_))
    ));
    assert!(matches!(
        agent.answer_keys(&q(false, 12, 1), &[vec![0]]),
        Err(KeysError::Unsupported(_))
    ));
}
