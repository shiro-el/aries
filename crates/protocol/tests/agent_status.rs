//! The Agent status rule, as herdr-app documents it (`docs/labels.md`):
//! blocked/error need the user, working is working, anything else is Done
//! while `turn` is above the shared `herdr.seen` label, else Idle.

use std::collections::HashMap;

use aries_protocol::agent_status::{AgentStatus, BlockedReason, StatusRecord, agent_status};

fn record(json: &str) -> StatusRecord {
    serde_json::from_str(json).unwrap()
}

fn labels(pairs: &[(&str, &str)]) -> HashMap<String, String> {
    pairs
        .iter()
        .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
        .collect()
}

#[test]
fn a_program_that_never_reported_has_no_agent_status() {
    assert_eq!(agent_status(None, 0, &labels(&[])), None);
    assert_eq!(agent_status(None, 3, &labels(&[("herdr.seen", "1")])), None);
}

#[test]
fn working_is_working_whatever_the_turn() {
    let s = record(r#"{"app":"codex","state":"working","msg":"Writing hello.txt"}"#);
    assert_eq!(
        agent_status(Some(&s), 4, &labels(&[])),
        Some(AgentStatus::Working)
    );
}

#[test]
fn blocked_carries_its_reason_and_message() {
    let s = record(
        r#"{"app":"claude-code","kind":"permission","msg":"approve Write: /tmp/a/hello.txt","state":"blocked"}"#,
    );
    let status = agent_status(Some(&s), 2, &labels(&[("herdr.seen", "2")])).unwrap();
    assert_eq!(
        status,
        AgentStatus::Blocked {
            reason: Some(BlockedReason::Permission),
            message: Some("approve Write: /tmp/a/hello.txt".into()),
        }
    );
    assert!(status.needs_attention());

    let q = record(r#"{"app":"pi","kind":"question","msg":"Which color?","state":"blocked"}"#);
    let a = record(r#"{"app":"x","kind":"auth","state":"blocked"}"#);
    let bare = record(r#"{"app":"x","state":"blocked"}"#);
    let odd = record(r#"{"app":"x","kind":"something-new","msg":"","state":"blocked"}"#);
    let reason = |s: &StatusRecord| match agent_status(Some(s), 0, &labels(&[])) {
        Some(AgentStatus::Blocked { reason, message }) => (reason, message),
        other => panic!("not blocked: {other:?}"),
    };
    assert_eq!(
        reason(&q),
        (Some(BlockedReason::Question), Some("Which color?".into()))
    );
    assert_eq!(reason(&a), (Some(BlockedReason::Auth), None));
    assert_eq!(reason(&bare), (None, None));
    assert_eq!(reason(&odd), (None, None));
}

#[test]
fn error_needs_attention_like_blocked() {
    let s = record(r#"{"app":"pi","msg":"500 fake upstream failure","state":"error"}"#);
    let status = agent_status(Some(&s), 1, &labels(&[("herdr.seen", "1")])).unwrap();
    assert_eq!(
        status,
        AgentStatus::Error {
            message: Some("500 fake upstream failure".into())
        }
    );
    assert!(status.needs_attention());
    assert!(!AgentStatus::Working.needs_attention());
    assert!(!AgentStatus::Done.needs_attention());
    assert!(!AgentStatus::Idle.needs_attention());
}

#[test]
fn a_finished_turn_nobody_has_seen_is_done() {
    let done = record(r#"{"app":"codex","state":"done"}"#);
    assert_eq!(
        agent_status(Some(&done), 1, &labels(&[])),
        Some(AgentStatus::Done)
    );
    assert_eq!(
        agent_status(Some(&done), 3, &labels(&[("herdr.seen", "2")])),
        Some(AgentStatus::Done)
    );
}

#[test]
fn a_seen_turn_is_idle() {
    let done = record(r#"{"app":"codex","state":"done"}"#);
    let idle = record(r#"{"app":"claude-code","state":"idle"}"#);
    assert_eq!(
        agent_status(Some(&done), 2, &labels(&[("herdr.seen", "2")])),
        Some(AgentStatus::Idle)
    );
    // A client that saw a later turn (another core's numbering) still counts as seen.
    assert_eq!(
        agent_status(Some(&done), 2, &labels(&[("herdr.seen", "9")])),
        Some(AgentStatus::Idle)
    );
    // A fresh program has turn 0: idle, nothing to see.
    assert_eq!(
        agent_status(Some(&idle), 0, &labels(&[])),
        Some(AgentStatus::Idle)
    );
    // An idle word after a turn nobody saw is still Done (the app's rule looks at turn, not the word).
    assert_eq!(
        agent_status(Some(&idle), 1, &labels(&[])),
        Some(AgentStatus::Done)
    );
}

#[test]
fn a_garbled_seen_label_counts_as_never_seen() {
    let done = record(r#"{"app":"codex","state":"done"}"#);
    assert_eq!(
        agent_status(Some(&done), 1, &labels(&[("herdr.seen", "soon")])),
        Some(AgentStatus::Done)
    );
}

#[test]
fn an_unknown_state_word_follows_the_turn_rule() {
    let s = record(r#"{"app":"x","state":"thinking-hard"}"#);
    assert_eq!(
        agent_status(Some(&s), 1, &labels(&[])),
        Some(AgentStatus::Done)
    );
    assert_eq!(
        agent_status(Some(&s), 1, &labels(&[("herdr.seen", "1")])),
        Some(AgentStatus::Idle)
    );
}

#[test]
fn herdr_pinned_1_pins_and_an_absent_empty_or_zero_value_does_not() {
    use aries_protocol::agent_status::is_pinned;
    assert!(is_pinned(&labels(&[("herdr.pinned", "1")])));
    assert!(!is_pinned(&labels(&[])));
    assert!(!is_pinned(&labels(&[("herdr.pinned", "")])));
    assert!(!is_pinned(&labels(&[("herdr.pinned", "0")])));
    assert!(!is_pinned(&labels(&[("herdr.seen", "1")])));
}
