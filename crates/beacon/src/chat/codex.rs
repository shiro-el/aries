//! Codex: pairing a Terminal with its rollout, and reading the rollout JSONL
//! into Chat items. Facts: `docs/research/codex-integration.md` (ticket 03).
//!
//! Pairing: a `--no-daemon` Codex keeps its thread's rollout
//! (`$CODEX_HOME/sessions/YYYY/MM/DD/rollout-<time>-<thread>.jsonl`) and its
//! writer lock (`$CODEX_HOME/thread-writer-locks/<thread>.lock`) open. After
//! `/new` the old thread's files stay open too, so the most recently written
//! thread is the current one. A new thread has only its lock until the first
//! message creates the rollout. A daemon-mode Codex (no `--no-daemon`) holds
//! nothing in the Terminal and is not paired.
//!
//! Transcript: `{timestamp, ordinal, type, payload}` records. The Chat comes
//! from `event_msg` `item_completed` items (user and agent messages,
//! reasoning, commands, file changes, image views), the `response_item`
//! tool calls and their outputs, and `task_complete.error`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use aries_protocol::chat::{
    ChatBody, ChatDiff, ChatEvent, ChatImage, ChatItem, ChatQuestion, DiffHunk, Question,
    QuestionOption, ToolStatus,
};
use serde_json::Value;

use super::{Agent, Interpreter, KeysError, Located, TerminalFacts, media};

pub const AGENT: &str = "codex";

/// The Codex question tool.
const QUESTION_TOOL: &str = "request_user_input";

pub struct Codex;

impl Agent for Codex {
    fn name(&self) -> &'static str {
        AGENT
    }

    fn locate(&self, terminal: &TerminalFacts) -> Option<Located> {
        terminal.pids().into_iter().find_map(|pid| {
            let links = std::fs::read_dir(format!("/proc/{pid}/fd")).ok()?;
            locate_in(
                links
                    .flatten()
                    .filter_map(|l| std::fs::read_link(l.path()).ok()),
            )
        })
    }

    fn interpreter(&self) -> Box<dyn Interpreter> {
        Box::<CodexInterpreter>::default()
    }

    /// `request_user_input` shows the options as a list with the first one
    /// selected: option n is `down` n times, then `enter`. Only one
    /// single-choice question at a time was verified (ticket 03).
    fn answer_keys(
        &self,
        question: &ChatQuestion,
        picks: &[Vec<usize>],
    ) -> Result<Vec<String>, KeysError> {
        if question.questions.len() != 1 {
            return Err(KeysError::Unsupported(
                "several Codex questions at once".into(),
            ));
        }
        if question.questions[0].multi {
            return Err(KeysError::Unsupported("multi-select Codex question".into()));
        }
        let n = picks[0][0];
        let mut keys = vec!["down".to_owned(); n];
        keys.push("enter".into());
        Ok(keys)
    }
}

/// The current thread among a process's open files.
pub fn locate_in(open: impl IntoIterator<Item = PathBuf>) -> Option<Located> {
    // thread -> (rollout, newest mtime of its files)
    let mut threads: HashMap<String, (Option<PathBuf>, SystemTime)> = HashMap::new();
    let mut lock_dir: Option<PathBuf> = None;
    for path in open {
        let (id, rollout) = if let Some(id) = rollout_thread(&path) {
            (id, true)
        } else if let Some(id) = lock_thread(&path) {
            lock_dir = path.parent().map(Path::to_path_buf);
            (id, false)
        } else {
            continue;
        };
        let Ok(modified) = std::fs::metadata(&path).and_then(|m| m.modified()) else {
            continue;
        };
        let entry = threads.entry(id).or_insert((None, SystemTime::UNIX_EPOCH));
        if rollout {
            entry.0 = Some(path);
        }
        entry.1 = entry.1.max(modified);
    }
    let (session, (rollout, _)) = threads
        .into_iter()
        .max_by(|a, b| a.1.1.cmp(&b.1.1).then_with(|| a.0.cmp(&b.0)))?;
    let path = match rollout {
        Some(path) => path,
        // No rollout yet: a path that does not exist, so the Chat waits;
        // the next poll pairs the rollout once Codex opens it.
        None => lock_dir?
            .parent()?
            .join("sessions")
            .join(format!("pending-{session}.jsonl")),
    };
    Some(Located { session, path })
}

/// `.../sessions/.../rollout-<time>-<thread uuid>.jsonl` -> thread.
fn rollout_thread(path: &Path) -> Option<String> {
    let name = path.file_name()?.to_str()?;
    let stem = name.strip_prefix("rollout-")?.strip_suffix(".jsonl")?;
    if !path.components().any(|c| c.as_os_str() == "sessions") {
        return None;
    }
    let id = stem.get(stem.len().checked_sub(36)?..)?;
    is_uuid(id).then(|| id.to_owned())
}

/// `.../thread-writer-locks/<thread>.lock` -> thread.
fn lock_thread(path: &Path) -> Option<String> {
    if path.parent()?.file_name()? != "thread-writer-locks" {
        return None;
    }
    let id = path.file_name()?.to_str()?.strip_suffix(".lock")?;
    is_uuid(id).then(|| id.to_owned())
}

fn is_uuid(s: &str) -> bool {
    s.len() == 36
        && s.char_indices().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => c == '-',
            _ => c.is_ascii_hexdigit(),
        })
}

/// Reads Codex rollout lines. Remembers tool calls so their output can
/// resend them, and the images of the last user message for its
/// `UserMessage` item.
#[derive(Default)]
pub struct CodexInterpreter {
    calls: HashMap<String, ChatItem>,
    /// The `exec` call whose output has not come yet.
    open_exec: Option<String>,
    /// Items an `exec` call produced (commands, file changes, image views).
    parts: HashMap<String, Vec<ChatItem>>,
    /// `request_user_input` question ids, in order, per call.
    question_ids: HashMap<String, Vec<String>>,
    user_images: Vec<ChatImage>,
    model: Option<String>,
    last_agent: Option<ChatItem>,
}

impl Interpreter for CodexInterpreter {
    fn line(&mut self, line: &str) -> Vec<ChatEvent> {
        let Ok(record) = serde_json::from_str::<Value>(line) else {
            return Vec::new();
        };
        let ts = record["timestamp"].as_str().map(str::to_owned);
        let payload = &record["payload"];
        match record["type"].as_str() {
            Some("turn_context") => {
                self.model = super::details::text(&payload["model"]);
                Vec::new()
            }
            Some("token_usage_record") => self.response_usage(&payload["usage"]),
            Some("response_item") => self.response_item(payload, ts),
            Some("event_msg") => self.event(payload, ts),
            _ => Vec::new(),
        }
    }
}

fn item(id: impl Into<String>, ts: &Option<String>, body: ChatBody) -> ChatEvent {
    ChatEvent::Item {
        item: ChatItem {
            id: id.into(),
            ts: ts.clone(),
            body,
        },
    }
}

fn tool(name: &str, title: String, input: Value, status: ToolStatus) -> ChatBody {
    ChatBody::Tool {
        name: name.to_owned(),
        title: one_line(&title),
        details: super::details::tool(&input, &Value::Null),
        input,
        status,
        output: None,
        diff: None,
        images: Vec::new(),
        question: None,
    }
}

impl CodexInterpreter {
    fn response_item(&mut self, p: &Value, ts: Option<String>) -> Vec<ChatEvent> {
        match p["type"].as_str() {
            Some("message") if p["role"] == "user" => {
                // The UserMessage item names attached images only by path;
                // their data is here.
                let images: Vec<ChatImage> = (p["content"].as_array().into_iter().flatten())
                    .filter(|b| b["type"] == "input_image")
                    .filter_map(|b| data_url(b["image_url"].as_str()?))
                    .collect();
                self.user_images = images;
                Vec::new()
            }
            Some("custom_tool_call") => {
                let Some(call) = p["call_id"].as_str() else {
                    return Vec::new();
                };
                let name = p["name"].as_str().unwrap_or("tool");
                let input = p["input"].as_str().unwrap_or_default();
                let body = tool(
                    name,
                    exec_title(input),
                    Value::String(input.to_owned()),
                    ToolStatus::Running,
                );
                self.open_exec = Some(call.to_owned());
                self.call(call, ts, body)
            }
            Some("function_call") => {
                let Some(call) = p["call_id"].as_str() else {
                    return Vec::new();
                };
                let name = p["name"].as_str().unwrap_or("tool");
                let args = p["arguments"].as_str().unwrap_or_default();
                let input = serde_json::from_str(args).unwrap_or(Value::String(args.to_owned()));
                let mut body = tool(name, title(&input), input.clone(), ToolStatus::Running);
                if name == QUESTION_TOOL {
                    if let ChatBody::Tool {
                        question, title, ..
                    } = &mut body
                    {
                        *question = parse_question(&input);
                        if let Some(q) = question {
                            *title = one_line(&q.questions[0].text);
                        }
                    }
                    let ids = (input["questions"].as_array().into_iter().flatten())
                        .map(|q| q["id"].as_str().unwrap_or_default().to_owned())
                        .collect();
                    self.question_ids.insert(call.to_owned(), ids);
                }
                self.call(call, ts, body)
            }
            Some("custom_tool_call_output") => self.exec_output(p),
            Some("function_call_output") => self.function_output(p),
            _ => Vec::new(),
        }
    }

    fn call(&mut self, call: &str, ts: Option<String>, body: ChatBody) -> Vec<ChatEvent> {
        let it = ChatItem {
            id: call.to_owned(),
            ts,
            body,
        };
        self.calls.insert(call.to_owned(), it.clone());
        vec![ChatEvent::Item { item: it }]
    }

    /// An `exec` result. When the call produced items, they replace it (the
    /// image views get the output's images); otherwise the call shows the
    /// output itself.
    fn exec_output(&mut self, p: &Value) -> Vec<ChatEvent> {
        let Some(call) = p["call_id"].as_str() else {
            return Vec::new();
        };
        if self.open_exec.as_deref() == Some(call) {
            self.open_exec = None;
        }
        let Some(mut it) = self.calls.remove(call) else {
            return Vec::new();
        };
        let (text, failed, mut images) = exec_result(&p["output"]);
        if let Some(parts) = self.parts.remove(call) {
            let mut events = Vec::new();
            for mut part in parts {
                if let ChatBody::Tool {
                    name,
                    images: part_images,
                    ..
                } = &mut part.body
                    && name == "view_image"
                    && !images.is_empty()
                {
                    part_images.push(images.remove(0));
                    events.push(ChatEvent::Item { item: part });
                }
            }
            events.push(ChatEvent::Remove { id: it.id });
            return events;
        }
        if let ChatBody::Tool {
            status,
            output,
            images: it_images,
            ..
        } = &mut it.body
        {
            *status = if failed {
                ToolStatus::Error
            } else {
                ToolStatus::Done
            };
            *output = Some(text);
            *it_images = images;
        }
        vec![ChatEvent::Item { item: it }]
    }

    fn function_output(&mut self, p: &Value) -> Vec<ChatEvent> {
        let Some(call) = p["call_id"].as_str() else {
            return Vec::new();
        };
        let Some(mut it) = self.calls.remove(call) else {
            return Vec::new();
        };
        let raw = match &p["output"] {
            Value::String(s) => s.clone(),
            Value::Null => String::new(),
            other => exec_result(other).0,
        };
        let text = match self.question_ids.remove(call) {
            Some(ids) => answers(&raw, &ids).unwrap_or(raw),
            None => raw,
        };
        if let ChatBody::Tool {
            status,
            output,
            input,
            details,
            ..
        } = &mut it.body
        {
            // Some Codex versions record command results as JSON strings;
            // others also emit a CommandExecution item. Read only explicit fields.
            let structured = serde_json::from_str::<Value>(&text).ok();
            let result = structured.as_ref().unwrap_or(&Value::Null);
            *details = super::details::tool(input, result);
            *status = if details
                .as_ref()
                .and_then(|d| d.exit_code)
                .is_some_and(|c| c != 0)
            {
                ToolStatus::Error
            } else {
                ToolStatus::Done
            };
            *output = Some(result["output"].as_str().map(str::to_owned).unwrap_or(text));
        }
        vec![ChatEvent::Item { item: it }]
    }

    fn response_usage(&mut self, usage: &Value) -> Vec<ChatEvent> {
        let Some(agent) = &mut self.last_agent else {
            return Vec::new();
        };
        if let ChatBody::Agent { metadata, .. } = &mut agent.body {
            let m = metadata.get_or_insert_default();
            m.input_tokens = usage["input_tokens"].as_u64();
            m.output_tokens = usage["output_tokens"].as_u64();
            m.cache_read_tokens = usage["cached_input_tokens"].as_u64();
            m.cache_write_tokens = usage["cache_write_input_tokens"].as_u64();
        }
        vec![ChatEvent::Item {
            item: agent.clone(),
        }]
    }

    fn finish_response(&mut self, p: &Value, interrupted: bool) -> Vec<ChatEvent> {
        let Some(agent) = &mut self.last_agent else {
            return Vec::new();
        };
        if let ChatBody::Agent { metadata, .. } = &mut agent.body {
            let m = metadata.get_or_insert_default();
            m.duration_ms = p["duration_ms"].as_u64();
            m.completion = Some(if interrupted {
                aries_protocol::chat::ResponseCompletion::Interrupted
            } else if p["error"].is_object() {
                aries_protocol::chat::ResponseCompletion::Error
            } else {
                aries_protocol::chat::ResponseCompletion::Complete
            });
        }
        vec![ChatEvent::Item {
            item: agent.clone(),
        }]
    }

    fn event(&mut self, p: &Value, ts: Option<String>) -> Vec<ChatEvent> {
        match p["type"].as_str() {
            Some("task_started") => {
                self.last_agent = None;
                Vec::new()
            }
            Some("token_count") => self.response_usage(&p["info"]["last_token_usage"]),
            Some("turn_aborted" | "task_interrupted") => self.finish_response(p, true),
            Some("item_completed") => self.completed(&p["item"], ts),
            Some("task_complete") => {
                let mut events = self.finish_response(p, false);
                let Some(message) = p["error"]["message"].as_str() else {
                    return events;
                };
                let turn = p["turn_id"].as_str().unwrap_or("turn");
                events.push(item(
                    format!("{turn}:error"),
                    &ts,
                    ChatBody::Error {
                        message: message.to_owned(),
                    },
                ));
                events
            }
            _ => Vec::new(),
        }
    }

    fn completed(&mut self, it: &Value, ts: Option<String>) -> Vec<ChatEvent> {
        let Some(id) = it["id"].as_str() else {
            return Vec::new();
        };
        let body = match it["type"].as_str() {
            Some("UserMessage") => {
                let text = texts(&it["content"], |b| b["type"] == "text").join("\n");
                let images = std::mem::take(&mut self.user_images);
                if text.trim().is_empty() && images.is_empty() {
                    return Vec::new();
                }
                ChatBody::User {
                    text: text.trim().to_owned(),
                    images,
                }
            }
            Some("AgentMessage") => {
                let text = texts(&it["content"], |_| true).join("\n");
                if text.trim().is_empty() {
                    return Vec::new();
                }
                ChatBody::Agent {
                    text,
                    metadata: super::details::metadata(aries_protocol::chat::ResponseMetadata {
                        model: self.model.clone(),
                        ..Default::default()
                    }),
                }
            }
            Some("Reasoning") => {
                let mut parts = texts(&it["summary_text"], |_| true);
                if parts.is_empty() {
                    parts = texts(&it["raw_content"], |_| true);
                }
                let text = parts.join("\n\n");
                ChatBody::Thinking {
                    text: (!text.trim().is_empty()).then_some(text),
                }
            }
            Some("CommandExecution") => command(it),
            Some("FileChange") => file_change(it),
            Some("ImageView") => {
                let path = it["path"].as_str().unwrap_or_default();
                let path = path.strip_prefix("file://").unwrap_or(path);
                tool("view_image", path.to_owned(), it.clone(), ToolStatus::Done)
            }
            _ => return Vec::new(),
        };
        let part = ChatItem {
            id: id.to_owned(),
            ts,
            body,
        };
        if matches!(part.body, ChatBody::Agent { .. }) {
            self.last_agent = Some(part.clone());
        }
        if matches!(part.body, ChatBody::Tool { .. })
            && let Some(call) = &self.open_exec
        {
            self.parts
                .entry(call.clone())
                .or_default()
                .push(part.clone());
        }
        vec![ChatEvent::Item { item: part }]
    }
}

/// Text of string or `{text}` entries of `blocks` that pass `keep`.
fn texts(blocks: &Value, keep: impl Fn(&Value) -> bool) -> Vec<String> {
    (blocks.as_array().into_iter().flatten())
        .filter(|b| keep(b))
        .filter_map(|b| b.as_str().or_else(|| b["text"].as_str()))
        .map(str::to_owned)
        .collect()
}

fn command(it: &Value) -> ChatBody {
    let script = it["parsed_cmd"][0]["cmd"]
        .as_str()
        .map(str::to_owned)
        .or_else(|| {
            let parts = it["command"].as_array()?;
            // [shell, -lc, script] shows as the script.
            match parts.as_slice() {
                [_, flag, script] if flag.as_str().is_some_and(|f| f.starts_with('-')) => {
                    script.as_str().map(str::to_owned)
                }
                _ => Some(
                    parts
                        .iter()
                        .filter_map(Value::as_str)
                        .collect::<Vec<_>>()
                        .join(" "),
                ),
            }
        })
        .or_else(|| it["command"].as_str().map(str::to_owned))
        .unwrap_or_default();
    let mut body = tool("command", script.clone(), it.clone(), status(it));
    if let ChatBody::Tool { details, .. } = &mut body {
        let d = details.get_or_insert_default();
        d.command = Some(script);
        d.cwd = super::details::text(&it["cwd"])
            .map(|s| s.strip_prefix("file://").unwrap_or(&s).to_owned());
        d.exit_code = it["exit_code"].as_i64();
        d.duration_ms = it["duration_ms"].as_u64().or_else(|| {
            it["duration"]["secs"]
                .as_u64()?
                .checked_mul(1000)?
                .checked_add(it["duration"]["nanos"].as_u64()? / 1_000_000)
        });
    }
    if let ChatBody::Tool { output, .. } = &mut body {
        *output = it["aggregated_output"].as_str().map(str::to_owned);
    }
    body
}

fn status(it: &Value) -> ToolStatus {
    let exit = it["exit_code"].as_i64().unwrap_or(0);
    match it["status"].as_str() {
        Some("in_progress") => ToolStatus::Running,
        Some("completed") if exit == 0 => ToolStatus::Done,
        _ => ToolStatus::Error,
    }
}

fn file_change(it: &Value) -> ChatBody {
    let changes = it["changes"].as_object();
    let first = changes.and_then(|c| c.iter().next());
    let title = match changes.map_or(0, |c| c.len()) {
        0 | 1 => first.map(|(p, _)| p.clone()).unwrap_or_default(),
        n => format!("{} (+{})", first.map_or("", |(p, _)| p), n - 1),
    };
    let mut body = tool("apply_patch", title, it.clone(), status(it));
    if let ChatBody::Tool { output, diff, .. } = &mut body {
        let out = [&it["stdout"], &it["stderr"]]
            .iter()
            .filter_map(|v| v.as_str().filter(|s| !s.is_empty()))
            .collect::<Vec<_>>()
            .join("\n");
        *output = Some(out);
        *diff = first.and_then(|(path, change)| change_diff(path, change));
    }
    body
}

/// One file of a `FileChange`: `add {content}`, `delete {content}` or
/// `update {unified_diff}`.
fn change_diff(path: &str, change: &Value) -> Option<ChatDiff> {
    match change["type"].as_str()? {
        "add" => Some(media::created(path, change["content"].as_str()?)),
        "delete" => {
            let lines: Vec<String> = (change["content"].as_str()?.lines())
                .map(|l| format!("-{l}"))
                .collect();
            Some(media::diff(
                path,
                [DiffHunk {
                    old_start: 1,
                    old_lines: lines.len() as u32,
                    new_start: 0,
                    new_lines: 0,
                    lines,
                }],
            ))
        }
        "update" => {
            let hunks = unified_hunks(change["unified_diff"].as_str()?);
            (!hunks.is_empty()).then(|| media::diff(path, hunks))
        }
        _ => None,
    }
}

/// The hunks of a unified diff (`@@ -a,b +c,d @@` headers).
pub fn unified_hunks(diff: &str) -> Vec<DiffHunk> {
    let mut hunks: Vec<DiffHunk> = Vec::new();
    for line in diff.lines() {
        if let Some(rest) = line.strip_prefix("@@ ") {
            let mut ranges = rest.split_whitespace();
            let range = |r: Option<&str>, sign: char| {
                let r = r?.strip_prefix(sign)?;
                let (start, len) = r.split_once(',').unwrap_or((r, "1"));
                Some((start.parse().ok()?, len.parse().ok()?))
            };
            if let (Some((old_start, old_lines)), Some((new_start, new_lines))) =
                (range(ranges.next(), '-'), range(ranges.next(), '+'))
            {
                hunks.push(DiffHunk {
                    old_start,
                    old_lines,
                    new_start,
                    new_lines,
                    lines: Vec::new(),
                });
            }
        } else if let Some(h) = hunks.last_mut()
            && (line.starts_with([' ', '+', '-']) || line.is_empty())
        {
            h.lines.push(if line.is_empty() {
                " ".into()
            } else {
                line.to_owned()
            });
        }
    }
    hunks
}

/// `exec` output blocks: text (the JSON result blobs as their `output`),
/// whether any command exited non-zero, and images.
fn exec_result(output: &Value) -> (String, bool, Vec<ChatImage>) {
    let blocks = match output {
        Value::String(s) => return (s.clone(), false, Vec::new()),
        Value::Array(b) => b,
        _ => return (String::new(), false, Vec::new()),
    };
    let mut text = Vec::new();
    let mut failed = false;
    let mut images = Vec::new();
    for b in blocks {
        match b["type"].as_str() {
            Some("input_text") | Some("output_text") => {
                let t = b["text"].as_str().unwrap_or_default();
                match serde_json::from_str::<Value>(t) {
                    Ok(Value::Object(o)) => {
                        failed |= o.get("exit_code").and_then(Value::as_i64).unwrap_or(0) != 0;
                        if let Some(out) = o.get("output").and_then(Value::as_str) {
                            text.push(out.to_owned());
                        }
                    }
                    _ => text.push(t.to_owned()),
                }
            }
            Some("input_image") => images.extend(b["image_url"].as_str().and_then(data_url)),
            _ => {}
        }
    }
    (text.concat().trim_end().to_owned(), failed, images)
}

/// `data:<media type>;base64,<data>`.
fn data_url(url: &str) -> Option<ChatImage> {
    let (meta, data) = url.strip_prefix("data:")?.split_once(',')?;
    let media_type = meta.strip_suffix(";base64")?;
    Some(media::image(media_type, data, None))
}

/// `request_user_input` arguments `{questions: [{header, id, question,
/// options: [{label, description}]}]}`.
fn parse_question(input: &Value) -> Option<ChatQuestion> {
    let text = |v: &Value| v.as_str().filter(|s| !s.is_empty()).map(str::to_owned);
    let questions = input["questions"]
        .as_array()?
        .iter()
        .map(|q| {
            let options = q["options"]
                .as_array()?
                .iter()
                .map(|o| {
                    Some(QuestionOption {
                        label: text(&o["label"])?,
                        description: text(&o["description"]),
                    })
                })
                .collect::<Option<Vec<_>>>()?;
            if options.is_empty() {
                return None;
            }
            Some(Question {
                text: text(&q["question"])?,
                header: text(&q["header"]),
                multi: q["multiSelect"] == true || q["multi_select"] == true,
                options,
            })
        })
        .collect::<Option<Vec<_>>>()?;
    (!questions.is_empty()).then_some(ChatQuestion { questions })
}

/// `{"answers": {"<id>": {"answers": ["Blue"]}}}` as one line per question.
fn answers(raw: &str, ids: &[String]) -> Option<String> {
    let v: Value = serde_json::from_str(raw).ok()?;
    let lines: Vec<String> = ids
        .iter()
        .map(|id| texts(&v["answers"][id]["answers"], |_| true).join(", "))
        .collect();
    (!lines.iter().all(String::is_empty)).then(|| lines.join("\n"))
}

/// What a code-mode `exec` script does, for its row: the command, the
/// patched file or the viewed path.
pub fn exec_title(script: &str) -> String {
    if let Some(cmd) = js_string_after(script, "cmd:") {
        return cmd;
    }
    if let Some(patch) = js_string_after(script, "apply_patch(") {
        let file = patch.lines().find_map(|l| {
            ["*** Add File: ", "*** Update File: ", "*** Delete File: "]
                .iter()
                .find_map(|p| l.strip_prefix(p))
        });
        if let Some(file) = file {
            return file.to_owned();
        }
    }
    if let Some(path) = js_string_after(script, "path:") {
        return path;
    }
    script.trim().to_owned()
}

/// The JSON-style string literal right after the first `key` in `script`.
fn js_string_after(script: &str, key: &str) -> Option<String> {
    let rest = script[script.find(key)? + key.len()..].trim_start();
    let body = rest.strip_prefix('"')?;
    let mut escaped = false;
    for (i, c) in body.char_indices() {
        match c {
            _ if escaped => escaped = false,
            '\\' => escaped = true,
            '"' => return serde_json::from_str(&rest[..i + 2]).ok(),
            _ => {}
        }
    }
    None
}

/// A title for an unknown function call: its first string argument.
fn title(input: &Value) -> String {
    match input {
        Value::String(s) => s.clone(),
        Value::Object(o) => ["cmd", "command", "path", "query", "url"]
            .iter()
            .find_map(|k| o.get(*k).and_then(Value::as_str))
            .or_else(|| o.values().find_map(Value::as_str))
            .unwrap_or_default()
            .to_owned(),
        _ => String::new(),
    }
}

/// The first line, at most 120 characters.
fn one_line(text: &str) -> String {
    let first = text.trim().lines().next().unwrap_or_default();
    let mut out: String = first.chars().take(120).collect();
    if out.len() < text.trim().len() {
        out.push('…');
    }
    out
}
