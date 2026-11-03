//! Optional details leave the original transcript text intact.
use aries_beacon::chat::{Interpreter, claude::ClaudeInterpreter};
use aries_protocol::chat::ChatEvent;
use serde_json::{Value, json};

fn feed(interpreter: &mut dyn Interpreter, record: Value) -> Vec<Value> {
    interpreter
        .line(&record.to_string())
        .into_iter()
        .filter_map(|event| match event {
            ChatEvent::Item { item } => Some(serde_json::to_value(item).unwrap()),
            _ => None,
        })
        .collect()
}

#[test]
fn claude_exposes_command_results_and_response_usage_without_inventing_missing_fields() {
    let mut chat = ClaudeInterpreter::default();
    let items = feed(
        &mut chat,
        json!({"type":"assistant","uuid":"a","cwd":"/repo","message":{
            "model":"claude-haiku-5-5","stop_reason":"end_turn",
            "usage":{"input_tokens":2,"output_tokens":45,"cache_read_input_tokens":15108,"cache_creation_input_tokens":25105},
            "content":[{"type":"text","text":"Done"},{"type":"tool_use","id":"cmd","name":"Bash","input":{"command":"pwd"}}]
        }}),
    );
    assert_eq!(
        items[0]["metadata"],
        json!({"model":"claude-haiku-5-5","input_tokens":2,"output_tokens":45,
        "cache_read_tokens":15108,"cache_write_tokens":25105,"completion":"complete"})
    );
    assert_eq!(items[1]["details"], json!({"command":"pwd","cwd":"/repo"}));
    let result = feed(
        &mut chat,
        json!({"type":"user","uuid":"u","message":{"content":[
        {"type":"tool_result","tool_use_id":"cmd","content":"/repo"}
    ]},"toolUseResult":{"stdout":"/repo","exitCode":0,"durationMs":12}}),
    );
    assert_eq!(
        result[0]["details"],
        json!({"command":"pwd","cwd":"/repo","exit_code":0,"duration_ms":12})
    );
    assert_eq!(result[0]["output"], "/repo");
    let old = feed(
        &mut chat,
        json!({"type":"assistant","uuid":"old","message":{"content":[{"type":"text","text":"Old"}]}}),
    );
    assert!(old[0].get("metadata").is_none());
}

#[test]
fn codex_updates_the_response_with_turn_usage_and_duration_and_command_measurements() {
    use aries_beacon::chat::codex::CodexInterpreter;
    let mut chat = CodexInterpreter::default();
    let mut items = std::collections::BTreeMap::new();
    for line in
        include_str!("../../../fixtures/agents/codex/sessions/simple-reply.rollout.jsonl").lines()
    {
        for event in chat.line(line) {
            match event {
                ChatEvent::Item { item } => {
                    items.insert(item.id.clone(), serde_json::to_value(item).unwrap());
                }
                ChatEvent::Remove { id } => {
                    items.remove(&id);
                }
                _ => {}
            }
        }
    }
    let response = items.values().find(|i| i["kind"] == "agent").unwrap();
    assert_eq!(response["metadata"]["model"], "gpt-6.1-sol");
    assert_eq!(response["metadata"]["duration_ms"], 3914);
    assert_eq!(response["metadata"]["completion"], "complete");
    assert!(response["metadata"]["input_tokens"].as_u64().unwrap() > 0);
    for line in
        include_str!("../../../fixtures/agents/codex/sessions/permission-exec.rollout.jsonl")
            .lines()
    {
        for value in feed(&mut chat, serde_json::from_str(line).unwrap()) {
            items.insert(value["id"].as_str().unwrap().to_owned(), value);
        }
    }
    let command = items.values().find(|i| i["name"] == "command").unwrap();
    assert_eq!(
        command["details"],
        json!({"command":"touch x.txt","cwd":"/home/user/work/a","exit_code":0,"duration_ms":0})
    );
}

#[test]
fn pi_keeps_response_usage_abort_and_search_details_on_the_active_branch() {
    use aries_beacon::chat::pi::PiInterpreter;
    let mut chat = PiInterpreter::default();
    let response = feed(
        &mut chat,
        json!({"type":"message","id":"a","parentId":null,"message":{
            "role":"assistant","model":"fake-1","usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0},
            "stopReason":"aborted","content":[{"type":"text","text":"Partial"},
            {"type":"toolCall","id":"grep1","name":"grep","arguments":{"pattern":"TODO","path":"src"}}]
        }}),
    );
    assert_eq!(
        response[0]["metadata"],
        json!({"model":"fake-1","input_tokens":10,"output_tokens":5,"cache_read_tokens":0,"cache_write_tokens":0,"completion":"interrupted"})
    );
    assert_eq!(
        response[1]["details"],
        json!({"path":"src","pattern":"TODO"})
    );
    let result = feed(
        &mut chat,
        json!({"type":"message","id":"r","parentId":"a","message":{
            "role":"toolResult","toolCallId":"grep1","toolName":"grep","isError":false,
            "content":[{"type":"text","text":"No matches"}],"details":{"count":0}
        }}),
    );
    assert_eq!(
        result[0]["details"],
        json!({"path":"src","pattern":"TODO","result_count":0})
    );
    assert!(result[0]["details"].get("exit_code").is_none());
}

#[test]
fn opencode_updates_already_streamed_text_when_message_metadata_finishes() {
    use aries_beacon::chat::opencode::{MessageRow, OpenCodeChat, PartRow};
    let mut chat = OpenCodeChat::default();
    let mut message = MessageRow {
        id: "m".into(),
        time_created: 1000,
        time_updated: 1000,
        data: json!({"role":"assistant","modelID":"big-pickle","time":{"created":1000}}),
    };
    chat.message(&message);
    let part = PartRow {
        id: "p".into(),
        message_id: "m".into(),
        time_created: 1000,
        time_updated: 1010,
        data: json!({"type":"text","text":"Done"}),
    };
    chat.part(&part);
    message.time_updated = 2300;
    message.data = json!({"role":"assistant","modelID":"big-pickle","path":{"cwd":"/repo"},"time":{"created":1000,"completed":2300},"finish":"stop",
        "tokens":{"input":125,"output":3,"cache":{"read":13056,"write":0}}});
    let events = chat.message(&message);
    let response = events
        .into_iter()
        .find_map(|e| match e {
            ChatEvent::Item { item } => Some(serde_json::to_value(item).unwrap()),
            _ => None,
        })
        .unwrap();
    assert_eq!(response["id"], "p");
    assert_eq!(
        response["metadata"],
        json!({"model":"big-pickle","input_tokens":125,"output_tokens":3,"cache_read_tokens":13056,"cache_write_tokens":0,"duration_ms":1300,"completion":"complete"})
    );
    let tool = PartRow {
        id: "t".into(),
        message_id: "m".into(),
        time_created: 1000,
        time_updated: 1010,
        data: json!({"type":"tool","tool":"bash","state":{"status":"completed","input":{"command":"pwd"},"output":"/repo","metadata":{"exit":0},"time":{"start":1100,"end":1201}}}),
    };
    let events = chat.part(&tool);
    let ChatEvent::Item { item } = &events[0] else {
        panic!("no item")
    };
    assert_eq!(
        serde_json::to_value(item).unwrap()["details"],
        json!({"command":"pwd","cwd":"/repo","exit_code":0,"duration_ms":101})
    );
}

#[test]
fn claude_attaches_turn_duration_to_the_reply_and_keeps_it_out_of_the_next_turn() {
    let mut chat = ClaudeInterpreter::default();
    feed(
        &mut chat,
        json!({"type":"assistant","uuid":"a","message":{"model":"claude","content":[{"type":"text","text":"First"}]}}),
    );
    let duration = feed(
        &mut chat,
        json!({"type":"system","subtype":"turn_duration","durationMs":1813}),
    );
    assert_eq!(duration.len(), 1);
    assert_eq!(duration[0]["id"], "a");
    assert_eq!(duration[0]["metadata"]["duration_ms"], 1813);
    feed(
        &mut chat,
        json!({"type":"user","uuid":"u","message":{"content":"Next"}}),
    );
    assert!(
        feed(
            &mut chat,
            json!({"type":"system","subtype":"turn_duration","durationMs":200})
        )
        .is_empty()
    );
}

#[test]
fn codex_function_results_expose_recorded_exit_and_time_and_keep_unknown_output() {
    let mut chat = aries_beacon::chat::codex::CodexInterpreter::default();
    feed(
        &mut chat,
        json!({"type":"response_item","payload":{"type":"function_call","call_id":"c","name":"exec_command","arguments":"{\"cmd\":\"false\",\"workdir\":\"/repo\"}"}}),
    );
    let result = feed(
        &mut chat,
        json!({"type":"response_item","payload":{"type":"function_call_output","call_id":"c",
        "output":"{\"exit_code\":7,\"wall_time_seconds\":0.123,\"output\":\"bad command\"}"}}),
    );
    assert_eq!(
        result[0]["details"],
        json!({"command":"false","cwd":"/repo","exit_code":7,"duration_ms":123})
    );
    assert_eq!(result[0]["output"], "bad command");
    assert_eq!(result[0]["status"], "error");
}
