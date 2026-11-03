//! Normalized optional measurements. Each adapter chooses its own source fields.
use aries_protocol::chat::{ResponseCompletion, ResponseMetadata, ToolDetails};
use serde_json::Value;

pub fn text(value: &Value) -> Option<String> {
    value.as_str().filter(|s| !s.is_empty()).map(str::to_owned)
}

pub fn completion(reason: &Value) -> Option<ResponseCompletion> {
    match reason.as_str()? {
        "end_turn" | "stop" | "stop_sequence" | "tool_use" | "toolUse" | "tool-calls"
        | "max_tokens" | "length" => Some(ResponseCompletion::Complete),
        "aborted" | "interrupted" | "cancelled" => Some(ResponseCompletion::Interrupted),
        "error" => Some(ResponseCompletion::Error),
        _ => None,
    }
}

pub fn metadata(value: ResponseMetadata) -> Option<ResponseMetadata> {
    (value != ResponseMetadata::default()).then_some(value)
}

pub fn tool(input: &Value, result: &Value) -> Option<ToolDetails> {
    let first = |value: &Value, keys: &[&str]| keys.iter().find_map(|k| text(&value[*k]));
    let value = ToolDetails {
        command: first(input, &["command", "cmd"]),
        cwd: first(input, &["cwd", "workdir"]),
        path: first(input, &["file_path", "filePath", "path"]),
        pattern: first(input, &["pattern", "query"]),
        exit_code: result["exitCode"]
            .as_i64()
            .or_else(|| result["exit_code"].as_i64())
            .or_else(|| result["exit"].as_i64()),
        duration_ms: result["durationMs"]
            .as_u64()
            .or_else(|| result["duration_ms"].as_u64())
            .or_else(|| {
                let seconds = result["wall_time_seconds"].as_f64()?;
                (seconds.is_finite() && seconds >= 0.0 && seconds < u64::MAX as f64 / 1000.0)
                    .then_some((seconds * 1000.0).round() as u64)
            }),
        result_count: ["numMatches", "numFiles", "count", "matches"]
            .iter()
            .find_map(|k| result[*k].as_u64()),
    };
    (value != ToolDetails::default()).then_some(value)
}

pub fn elapsed(start: &Value, end: &Value) -> Option<u64> {
    end.as_u64()?.checked_sub(start.as_u64()?)
}
