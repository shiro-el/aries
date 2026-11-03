//! Claude Code: pairing a Terminal with its transcript, and reading the
//! transcript JSONL into Chat items. Facts: `docs/research/claude-code-integration.md`.
//!
//! Pairing: Claude Code keeps `<config>/sessions/<pid>.json` while it runs
//! (`sessionId`, `cwd`, `procStart`). The transcript is
//! `<config>/projects/<cwd with every non-alphanumeric as '->/<sessionId>.jsonl`,
//! created with the first message. `<config>` is `$CLAUDE_CONFIG_DIR`, or
//! `~/.claude`.

use std::collections::HashMap;
use std::path::PathBuf;

use aries_protocol::chat::{
    ChatBody, ChatDiff, ChatEvent, ChatImage, ChatItem, ChatQuestion, DiffHunk, Question,
    QuestionOption, ResponseMetadata, ToolDetails, ToolStatus,
};
use serde_json::Value;

use super::{Agent, Interpreter, KeysError, Located, TerminalFacts, media};

pub const AGENT: &str = "claude-code";

pub struct ClaudeCode {
    pub config_dir: PathBuf,
}

impl ClaudeCode {
    pub fn from_env() -> Self {
        let config_dir = std::env::var_os("CLAUDE_CONFIG_DIR")
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| crate::home().join(".claude"));
        Self { config_dir }
    }

    /// The registry entry of `pid`, if it is a running Claude Code (and not
    /// a reused pid).
    fn registry(&self, pid: u32) -> Option<(String, String)> {
        let path = self.config_dir.join("sessions").join(format!("{pid}.json"));
        let entry: Value = serde_json::from_slice(&std::fs::read(path).ok()?).ok()?;
        let started = proc_start(pid)?;
        if entry["procStart"].as_str() != Some(started.as_str()) {
            return None;
        }
        Some((
            entry["sessionId"].as_str()?.to_owned(),
            entry["cwd"].as_str()?.to_owned(),
        ))
    }
}

impl Agent for ClaudeCode {
    fn name(&self) -> &'static str {
        AGENT
    }

    fn locate(&self, terminal: &TerminalFacts) -> Option<Located> {
        terminal.pids().into_iter().find_map(|pid| {
            let (session, cwd) = self.registry(pid)?;
            let path = self
                .config_dir
                .join("projects")
                .join(slug(&cwd))
                .join(format!("{session}.jsonl"));
            Some(Located { session, path })
        })
    }

    fn interpreter(&self) -> Box<dyn Interpreter> {
        Box::<ClaudeInterpreter>::default()
    }

    fn answer_input(
        &self,
        question: &ChatQuestion,
        picks: &[Vec<usize>],
        text: &[Option<String>],
    ) -> Result<Vec<super::answer::AnswerInput>, KeysError> {
        use super::answer::AnswerInput::{Keys, Text};
        if !text.iter().any(Option::is_some) {
            return self
                .answer_keys(question, picks)
                .map(|keys| vec![Keys(keys)]);
        }
        if question.questions.iter().any(|q| q.options.len() > 8) {
            return Err(KeysError::Unsupported(
                "more than 8 options plus Other".into(),
            ));
        }
        let mut actions = Vec::new();
        for (i, q) in question.questions.iter().enumerate() {
            if let Some(value) = text.get(i).and_then(Option::as_ref) {
                actions.push(Keys(vec![(q.options.len() + 1).to_string()]));
                actions.push(Text(value.clone()));
                actions.push(Keys(vec!["enter".into()]));
            } else {
                actions.push(Keys(picks[i].iter().map(|n| (n + 1).to_string()).collect()));
            }
            if q.multi {
                actions.push(Keys(vec!["tab".into()]));
            }
        }
        if question.questions.len() > 1 || question.questions.iter().any(|q| q.multi) {
            actions.push(Keys(vec!["1".into()]));
        }
        Ok(actions)
    }

    /// AskUserQuestion: a single-choice question takes its option number
    /// and moves on by itself; a multi-select one toggles each number, then
    /// `tab` moves on. With several questions or any multi-select, a review
    /// screen follows where `1` submits.
    fn answer_keys(
        &self,
        question: &ChatQuestion,
        picks: &[Vec<usize>],
    ) -> Result<Vec<String>, KeysError> {
        if question.questions.iter().any(|q| q.options.len() > 9) {
            return Err(KeysError::Unsupported("more than 9 options".into()));
        }
        let mut keys = Vec::new();
        for (q, p) in question.questions.iter().zip(picks) {
            keys.extend(p.iter().map(|n| (n + 1).to_string()));
            if q.multi {
                keys.push("tab".into());
            }
        }
        if question.questions.len() > 1 || question.questions.iter().any(|q| q.multi) {
            keys.push("1".into());
        }
        Ok(keys)
    }
}

/// `/tmp/a.b/c` -> `-tmp-a-b-c`.
pub fn slug(cwd: &str) -> String {
    cwd.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect()
}

/// Field 22 (`starttime`) of `/proc/<pid>/stat`, as Claude Code records it.
fn proc_start(pid: u32) -> Option<String> {
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // The command name (field 2) may contain spaces; count after its ')'.
    let rest = &stat[stat.rfind(')')? + 2..];
    rest.split(' ').nth(19).map(str::to_owned)
}

/// Reads Claude Code transcript lines. Remembers tool calls so a result
/// line can resend the whole call with its output.
#[derive(Default)]
pub struct ClaudeInterpreter {
    tools: HashMap<String, ChatItem>,
    last_agent: Option<ChatItem>,
}

impl Interpreter for ClaudeInterpreter {
    fn line(&mut self, line: &str) -> Vec<ChatEvent> {
        let Ok(record) = serde_json::from_str::<Value>(line) else {
            return Vec::new();
        };
        if record["isSidechain"] == true || record["isMeta"] == true {
            return Vec::new();
        }
        if record["type"] == "system" && record["subtype"] == "turn_duration" {
            let Some(duration) = record["durationMs"].as_u64() else {
                return Vec::new();
            };
            let Some(agent) = &mut self.last_agent else {
                return Vec::new();
            };
            if let ChatBody::Agent { metadata, .. } = &mut agent.body {
                metadata.get_or_insert_default().duration_ms = Some(duration);
            }
            return vec![ChatEvent::Item {
                item: agent.clone(),
            }];
        }
        let items = match record["type"].as_str() {
            Some("user") => self.user(&record),
            Some("assistant") => self.assistant(&record),
            _ => Vec::new(),
        };
        for item in &items {
            match &item.body {
                ChatBody::User { .. } | ChatBody::Error { .. } => self.last_agent = None,
                ChatBody::Agent { .. } => self.last_agent = Some(item.clone()),
                _ => {}
            }
        }
        items
            .into_iter()
            .map(|item| ChatEvent::Item { item })
            .collect()
    }
}

impl ClaudeInterpreter {
    fn user(&mut self, record: &Value) -> Vec<ChatItem> {
        let Some(uuid) = record["uuid"].as_str() else {
            return Vec::new();
        };
        let ts = timestamp(record);
        match &record["message"]["content"] {
            Value::String(text) => user_text(text)
                .map(|text| ChatItem {
                    id: uuid.to_owned(),
                    ts,
                    body: ChatBody::User {
                        text,
                        images: Vec::new(),
                    },
                })
                .into_iter()
                .collect(),
            Value::Array(blocks) => {
                let mut items = Vec::new();
                let mut text = Vec::new();
                let mut images = Vec::new();
                // The structured result belongs to the record's only result.
                let results = blocks.iter().filter(|b| b["type"] == "tool_result").count();
                let structured = if results == 1 {
                    &record["toolUseResult"]
                } else {
                    &Value::Null
                };
                for block in blocks {
                    match block["type"].as_str() {
                        Some("tool_result") => {
                            if let Some(item) = self.tool_result(block, structured) {
                                items.push(item);
                            }
                        }
                        Some("text") => text.extend(block["text"].as_str()),
                        Some("image") => images.extend(image(block, None)),
                        _ => {}
                    }
                }
                let text = text.join("\n");
                let text = user_text(&text).or_else(|| (!images.is_empty()).then(String::new));
                if let Some(text) = text {
                    items.insert(
                        0,
                        ChatItem {
                            id: uuid.to_owned(),
                            ts,
                            body: ChatBody::User { text, images },
                        },
                    );
                }
                items
            }
            _ => Vec::new(),
        }
    }

    /// `structured` is the record's `toolUseResult` (Null when unknown).
    fn tool_result(&mut self, block: &Value, structured: &Value) -> Option<ChatItem> {
        let id = block["tool_use_id"].as_str()?;
        let item = self.tools.get_mut(id)?;
        if let ChatBody::Tool {
            status,
            output,
            diff,
            images,
            input,
            details,
            ..
        } = &mut item.body
        {
            let failed = block["is_error"] == true;
            *status = if failed {
                ToolStatus::Error
            } else {
                ToolStatus::Done
            };
            *output = Some(result_text(&block["content"]));
            if !failed {
                *diff = edit_diff(structured, input);
            }
            let cwd = details.as_ref().and_then(|d| d.cwd.clone());
            *details = super::details::tool(input, structured);
            if let Some(cwd) = cwd {
                details.get_or_insert_default().cwd = Some(cwd);
            }
            *images = result_images(&block["content"], structured);
        }
        Some(item.clone())
    }

    fn assistant(&mut self, record: &Value) -> Vec<ChatItem> {
        let Some(uuid) = record["uuid"].as_str() else {
            return Vec::new();
        };
        let ts = timestamp(record);
        let Some(blocks) = record["message"]["content"].as_array() else {
            return Vec::new();
        };
        let api_error = record["isApiErrorMessage"] == true;
        let mut items = Vec::new();
        for (i, block) in blocks.iter().enumerate() {
            let id = if i == 0 {
                uuid.to_owned()
            } else {
                format!("{uuid}:{i}")
            };
            let body = match block["type"].as_str() {
                Some("text") => {
                    let text = block["text"].as_str().unwrap_or_default().to_owned();
                    if api_error {
                        ChatBody::Error { message: text }
                    } else if text.trim().is_empty() {
                        continue;
                    } else {
                        ChatBody::Agent {
                            text,
                            metadata: super::details::metadata(ResponseMetadata {
                                model: super::details::text(&record["message"]["model"]),
                                input_tokens: record["message"]["usage"]["input_tokens"].as_u64(),
                                output_tokens: record["message"]["usage"]["output_tokens"].as_u64(),
                                cache_read_tokens:
                                    record["message"]["usage"]["cache_read_input_tokens"].as_u64(),
                                cache_write_tokens:
                                    record["message"]["usage"]["cache_creation_input_tokens"]
                                        .as_u64(),
                                completion: super::details::completion(
                                    &record["message"]["stop_reason"],
                                ),
                                ..ResponseMetadata::default()
                            }),
                        }
                    }
                }
                Some("thinking") => ChatBody::Thinking {
                    text: block["thinking"]
                        .as_str()
                        .filter(|t| !t.is_empty())
                        .map(str::to_owned),
                },
                Some("redacted_thinking") => ChatBody::Thinking { text: None },
                Some("tool_use") => {
                    let Some(call) = block["id"].as_str() else {
                        continue;
                    };
                    let name = block["name"].as_str().unwrap_or("tool").to_owned();
                    let input = block["input"].clone();
                    let item = ChatItem {
                        id: call.to_owned(),
                        ts: ts.clone(),
                        body: ChatBody::Tool {
                            details: {
                                let mut d = super::details::tool(&input, &Value::Null);
                                if let Some(cwd) = super::details::text(&record["cwd"]) {
                                    d.get_or_insert_with(ToolDetails::default).cwd = Some(cwd);
                                }
                                d
                            },
                            title: tool_title(&name, &input),
                            question: (name == "AskUserQuestion")
                                .then(|| question(&input))
                                .flatten(),
                            name,
                            input,
                            status: ToolStatus::Running,
                            output: None,
                            diff: None,
                            images: Vec::new(),
                        },
                    };
                    self.tools.insert(call.to_owned(), item.clone());
                    items.push(item);
                    continue;
                }
                _ => continue,
            };
            items.push(ChatItem {
                id,
                ts: ts.clone(),
                body,
            });
        }
        items
    }
}

/// AskUserQuestion `input.questions[{question, header, multiSelect,
/// options[{label, description}]}]`. `None` if it has no usable question.
fn question(input: &Value) -> Option<ChatQuestion> {
    let text = |v: &Value| v.as_str().filter(|s| !s.is_empty()).map(str::to_owned);
    let questions = input["questions"]
        .as_array()?
        .iter()
        .map(|q| {
            Some(Question {
                text: text(&q["question"])?,
                header: text(&q["header"]),
                multi: q["multiSelect"] == true,
                options: q["options"]
                    .as_array()?
                    .iter()
                    .map(|o| {
                        Some(QuestionOption {
                            label: text(&o["label"])?,
                            description: text(&o["description"]),
                        })
                    })
                    .collect::<Option<Vec<_>>>()?,
            })
        })
        .collect::<Option<Vec<_>>>()?;
    (!questions.is_empty()).then_some(ChatQuestion { questions })
}

fn timestamp(record: &Value) -> Option<String> {
    record["timestamp"].as_str().map(str::to_owned)
}

/// What to show for a typed user line: slash commands as the command,
/// local-command output and empty lines not at all.
fn user_text(text: &str) -> Option<String> {
    let text = text.trim();
    if text.is_empty() || text.starts_with("<local-command-") {
        return None;
    }
    if text.starts_with("<command-name>") {
        let command = between(text, "<command-name>", "</command-name>")?;
        let args = between(text, "<command-args>", "</command-args>").unwrap_or_default();
        return Some(format!("{command} {args}").trim().to_owned());
    }
    Some(text.to_owned())
}

fn between<'a>(text: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let start = text.find(open)? + open.len();
    let end = start + text[start..].find(close)?;
    Some(text[start..end].trim())
}

/// A `tool_result` content (a string, or text/image blocks) as text.
fn result_text(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(blocks) => blocks
            .iter()
            .map(|b| match b["type"].as_str() {
                Some("text") => b["text"].as_str().unwrap_or_default().to_owned(),
                Some("image") => format!(
                    "[image {}]",
                    b["source"]["media_type"].as_str().unwrap_or("")
                )
                .replace(" ]", "]"),
                Some(other) => format!("[{other}]"),
                None => String::new(),
            })
            .collect::<Vec<_>>()
            .join("\n"),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// An `{type: image, source: {type: base64, media_type, data}}` block.
fn image(block: &Value, size: Option<(u32, u32)>) -> Option<ChatImage> {
    let source = &block["source"];
    if source["type"] != "base64" {
        return None;
    }
    Some(media::image(
        source["media_type"].as_str().unwrap_or("image/png"),
        source["data"].as_str()?,
        size,
    ))
}

/// The images of a tool result. Claude Code records the size of a Read
/// image in `toolUseResult.file.dimensions`.
fn result_images(content: &Value, structured: &Value) -> Vec<ChatImage> {
    let Some(blocks) = content.as_array() else {
        return Vec::new();
    };
    let blocks: Vec<&Value> = blocks.iter().filter(|b| b["type"] == "image").collect();
    let dims = &structured["file"]["dimensions"];
    let size = match (
        dims["displayWidth"].as_u64(),
        dims["displayHeight"].as_u64(),
    ) {
        (Some(w), Some(h)) if blocks.len() == 1 => Some((w as u32, h as u32)),
        _ => None,
    };
    blocks.into_iter().filter_map(|b| image(b, size)).collect()
}

/// The diff of an Edit, MultiEdit or Write result: `structuredPatch`, or a
/// new file's `content`.
fn edit_diff(structured: &Value, input: &Value) -> Option<ChatDiff> {
    let path = structured["filePath"]
        .as_str()
        .or_else(|| input["file_path"].as_str())
        .unwrap_or_default();
    if let Some(patch) = structured["structuredPatch"]
        .as_array()
        .filter(|p| !p.is_empty())
    {
        let num = |h: &Value, k: &str| h[k].as_u64().unwrap_or(0) as u32;
        let hunks = patch.iter().map(|h| DiffHunk {
            old_start: num(h, "oldStart"),
            old_lines: num(h, "oldLines"),
            new_start: num(h, "newStart"),
            new_lines: num(h, "newLines"),
            lines: (h["lines"].as_array().into_iter().flatten())
                .filter_map(|l| l.as_str().map(str::to_owned))
                .collect(),
        });
        return Some(media::diff(path, hunks));
    }
    if structured["type"] == "create" {
        return Some(media::created(path, structured["content"].as_str()?));
    }
    None
}

/// The one line shown on a collapsed tool row.
fn tool_title(name: &str, input: &Value) -> String {
    let field = |k: &str| input[k].as_str().map(str::to_owned);
    let title = match name {
        "AskUserQuestion" => input["questions"][0]["question"]
            .as_str()
            .map(str::to_owned),
        _ => [
            "file_path",
            "command",
            "pattern",
            "url",
            "path",
            "description",
            "prompt",
        ]
        .iter()
        .find_map(|k| field(k)),
    };
    let title = title
        .or_else(|| {
            input
                .as_object()
                .and_then(|o| o.values().find_map(|v| v.as_str().map(str::to_owned)))
        })
        .unwrap_or_default();
    let first = title.lines().next().unwrap_or_default();
    let mut out: String = first.chars().take(120).collect();
    if out.len() < title.len() {
        out.push('…');
    }
    out
}
