//! Regression: account limits must survive without a visible reply, and absent
//! percentages must stay unknown rather than masquerading as unused quota.
use aries_beacon::chat::{Interpreter, codex::CodexInterpreter};
use serde_json::Value;

fn usage(events: Vec<aries_protocol::chat::ChatEvent>) -> Value {
    events
        .into_iter()
        .map(|e| serde_json::to_value(e).unwrap())
        .find(|e| e["type"] == "usage")
        .expect("usage event")
}

#[test]
fn codex_records_account_windows_and_current_context_without_response_metadata() {
    let mut chat = CodexInterpreter::default();
    let mut lines = include_str!("../../../fixtures/agents/usage/codex.jsonl").lines();
    let first = usage(chat.line(lines.next().unwrap()));
    assert_eq!(first["usage"]["context"]["used_tokens"], 50000);
    assert_eq!(first["usage"]["context"]["window_tokens"], 200000);
    assert_eq!(first["usage"]["limits"][0]["used_basis_points"], 1250);
    assert_eq!(first["usage"]["limits"][0]["window_minutes"], 300);
    assert_eq!(first["usage"]["limits"][0]["resets_at"], 1793790000_u64);
    assert_eq!(first["usage"]["limits"][1]["used_basis_points"], 0);
    assert_eq!(
        first["usage"]["limits"][0]["updated_at"],
        "2026-11-04T10:00:00Z"
    );
    assert!(!first.to_string().contains("private"));
    let missing = usage(chat.line(lines.next().unwrap()));
    assert!(missing["usage"]["limits"][0]["used_basis_points"].is_null());
    assert_eq!(
        missing["usage"]["context"]["updated_at"],
        "2026-11-04T10:00:00Z"
    );
}

#[test]
fn claude_uses_only_recorded_capacity_and_limits_and_invalidates_compacted_context() {
    use aries_beacon::chat::claude::ClaudeInterpreter;
    let mut chat = ClaudeInterpreter::default();
    let mut lines = include_str!("../../../fixtures/agents/usage/claude.jsonl").lines();
    let assistant = usage(chat.line(lines.next().unwrap()));
    assert_eq!(assistant["usage"]["context"]["used_tokens"], 40000);
    assert!(assistant["usage"]["context"]["window_tokens"].is_null());
    assert_eq!(assistant["usage"]["limits"], serde_json::json!([]));
    let capacity = usage(chat.line(lines.next().unwrap()));
    assert_eq!(capacity["usage"]["context"]["used_tokens"], 40000);
    assert_eq!(capacity["usage"]["context"]["window_tokens"], 200000);
    let quota = usage(chat.line(lines.next().unwrap()));
    assert_eq!(quota["usage"]["limits"][0]["window_minutes"], 300);
    assert_eq!(quota["usage"]["limits"][0]["used_basis_points"], 4000);
    assert_eq!(quota["usage"]["limits"][0]["resets_at"], 1793790000_u64);
    let missing = usage(chat.line(lines.next().unwrap()));
    assert!(missing["usage"]["limits"][0]["used_basis_points"].is_null());
    let compacted = usage(chat.line(lines.next().unwrap()));
    assert!(compacted["usage"]["context"]["used_tokens"].is_null());
}

#[test]
fn a_claude_model_change_without_measurements_clears_previous_context() {
    use aries_beacon::chat::claude::ClaudeInterpreter;
    let mut chat = ClaudeInterpreter::default();
    for line in include_str!("../../../fixtures/agents/usage/claude.jsonl")
        .lines()
        .take(2)
    {
        chat.line(line);
    }
    let changed = usage(
        chat.line(r#"{"type":"assistant","uuid":"b","message":{"model":"other","content":[]}}"#),
    );
    assert!(changed["usage"]["context"].is_null());
}
