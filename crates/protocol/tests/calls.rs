use aries_protocol::calls::{
    Answer, AnswerState, Call, CallsLine, Invalid, LogEntry, Urgency, fold, validate,
};

fn call(id: &str) -> Call {
    Call {
        id: id.into(),
        at_ms: 1_000,
        terminal: Some("t3".into()),
        recipient: None,
        top_level: true,
        title: "Review ready".into(),
        body: "Tests pass.".into(),
        urgency: Urgency::Check,
        choices: vec!["Merge".into(), "Wait".into()],
        answer: None,
    }
}

#[test]
fn a_log_line_reads_as_a_call() {
    let line = r#"{"type":"call","seq":4,"call":{"id":"c1","at_ms":1000,"terminal":"t3","top_level":true,"title":"Review ready","body":"Tests pass.","urgency":"check","choices":["Merge","Wait"]}}"#;
    let entry: LogEntry = serde_json::from_str(line).unwrap();
    assert_eq!(
        entry,
        LogEntry::Call {
            seq: 4,
            call: call("c1")
        }
    );
}

#[test]
fn a_calls_line_round_trips() {
    let line = r#"{"type":"synced","seq":9}"#;
    let parsed: CallsLine = serde_json::from_str(line).unwrap();
    assert_eq!(parsed, CallsLine::Synced { seq: 9 });
    assert_eq!(serde_json::to_string(&parsed).unwrap(), line);
}

#[test]
fn an_unconfirmed_caller_reads_without_a_terminal() {
    let line = r#"{"id":"c2","at_ms":5,"terminal":null,"top_level":false,"title":"x","body":"","urgency":"urgent","choices":[]}"#;
    let c: Call = serde_json::from_str(line).unwrap();
    assert_eq!(c.terminal, None);
    assert!(!c.pushes_by_default());
}

#[test]
fn folding_applies_answers_to_their_call_and_moves_its_seq() {
    let answer = Answer {
        choice: 1,
        text: "Wait".into(),
        state: AnswerState::Delivered,
        at_ms: 2_000,
        detail: None,
    };
    let calls = fold([
        LogEntry::Call {
            seq: 1,
            call: call("a"),
        },
        LogEntry::Call {
            seq: 2,
            call: call("b"),
        },
        LogEntry::Answer {
            seq: 3,
            id: "a".into(),
            answer: answer.clone(),
        },
        LogEntry::Answer {
            seq: 4,
            id: "gone".into(),
            answer: answer.clone(),
        },
    ]);
    let ids: Vec<_> = calls.iter().map(|(s, c)| (*s, c.id.as_str())).collect();
    assert_eq!(ids, [(3, "a"), (2, "b")]);
    assert_eq!(calls[0].1.answer, Some(answer));
    assert_eq!(calls[1].1.answer, None);
}

#[test]
fn only_confirmed_top_level_calls_push_by_default() {
    let mut c = call("a");
    assert!(c.pushes_by_default());
    c.urgency = Urgency::Record;
    assert!(!c.pushes_by_default());
    let mut child = call("b");
    child.top_level = false;
    assert!(!child.pushes_by_default());
}

#[test]
fn urgency_names_are_the_cli_words() {
    assert_eq!(Urgency::parse("emergency"), Some(Urgency::Emergency));
    assert_eq!(Urgency::parse("check"), Some(Urgency::Check));
    assert_eq!(Urgency::parse("loud"), None);
    assert_eq!(
        serde_json::to_string(&Urgency::Urgent).unwrap(),
        "\"urgent\""
    );
}

#[test]
fn validation_enforces_the_limits() {
    let s = |v: &[&str]| v.iter().map(|x| x.to_string()).collect::<Vec<_>>();
    assert_eq!(validate("t", "b", &s(&["a", "b", "c"])), Ok(()));
    assert_eq!(validate(" ", "b", &[]), Err(Invalid::EmptyTitle));
    assert_eq!(
        validate("t", "b", &s(&["a", "b", "c", "d"])),
        Err(Invalid::TooManyChoices)
    );
    assert_eq!(
        validate(&"x".repeat(201), "", &[]),
        Err(Invalid::TitleTooLong)
    );
    // Characters, not bytes: 200 Hangul syllables are fine.
    assert_eq!(validate(&"가".repeat(200), "", &[]), Ok(()));
    assert_eq!(
        validate("t", &"x".repeat(4001), &[]),
        Err(Invalid::BodyTooLong)
    );
    assert_eq!(
        validate("t", "", &s(&["a", "a"])),
        Err(Invalid::DuplicateChoice)
    );
    assert_eq!(
        validate("t", "", &s(&["a\nb"])),
        Err(Invalid::ControlInChoice)
    );
    assert_eq!(validate("t", "", &s(&[""])), Err(Invalid::EmptyChoice));
}
