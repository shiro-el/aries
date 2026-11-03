//! The question answer contract (`aries-beacon answer`), from both sides.

use aries_protocol::answer::{ANSWER_FORMAT, Answer, AnswerOutcome, AnswerReply};

#[test]
fn an_answer_round_trips_through_its_arguments() {
    let answer = Answer {
        free_text: vec![],
        question: "toolu_01".into(),
        picks: vec![vec![1], vec![0, 2]],
    };
    let args = answer.args();
    assert_eq!(args, ["toolu_01", "1", "0,2"]);
    assert_eq!(Answer::from_args(&args), Ok(answer));
}

#[test]
fn malformed_answer_arguments_are_refused() {
    let s = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
    for bad in [
        s(&[]),
        s(&["q"]),
        s(&["q", "x"]),
        s(&["q", ""]),
        s(&["q", "1,"]),
        s(&["", "1"]),
    ] {
        assert!(Answer::from_args(&bad).is_err(), "{bad:?}");
    }
}

#[test]
fn a_reply_line_reads_back() {
    let line = r#"{"v":1,"outcome":"answered","detail":"toolu_01 has a result"}"#;
    assert_eq!(
        AnswerReply::parse(line).unwrap(),
        AnswerReply {
            v: ANSWER_FORMAT,
            outcome: AnswerOutcome::Answered,
            detail: Some("toolu_01 has a result".into()),
        }
    );
    let sent = AnswerReply::new(AnswerOutcome::Sent, None);
    assert_eq!(sent.to_json(), r#"{"v":1,"outcome":"sent"}"#);
    assert_eq!(AnswerReply::parse(&sent.to_json()).unwrap(), sent);
}

#[test]
fn an_unknown_outcome_from_a_newer_beacon_is_still_a_failure() {
    let line = r#"{"v":1,"outcome":"rate_limited"}"#;
    let reply = AnswerReply::parse(line).unwrap();
    assert_eq!(reply.outcome, AnswerOutcome::Unknown);
    assert!(!reply.outcome.is_sent());
    assert!(AnswerReply::parse("login banner").is_err());
    assert!(AnswerReply::parse(r#"{"v":2,"outcome":"sent"}"#).is_err());
}

#[test]
fn text_and_picks_share_one_request_without_confusing_indexes() {
    let answer = Answer {
        question: "call_q".into(),
        picks: vec![vec![1], vec![]],
        free_text: vec![None, Some("보라색 ' $value".into())],
    };
    assert_eq!(answer.args(), ["call_q", "1", "text:보라색 ' $value"]);
    assert_eq!(Answer::from_args(&answer.args()), Ok(answer));
}
