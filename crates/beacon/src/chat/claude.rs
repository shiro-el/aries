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

use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ToolStatus};
use serde_json::Value;

use super::{Agent, Interpreter, Located, TerminalFacts};

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
}

impl Interpreter for ClaudeInterpreter {
    fn line(&mut self, line: &str) -> Vec<ChatEvent> {
        let Ok(record) = serde_json::from_str::<Value>(line) else {
            return Vec::new();
        };
        if record["isSidechain"] == true || record["isMeta"] == true {
            return Vec::new();
        }
        let items = match record["type"].as_str() {
            Some("user") => self.user(&record),
            Some("assistant") => self.assistant(&record),
            _ => Vec::new(),
        };
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
                    body: ChatBody::User { text },
                })
                .into_iter()
                .collect(),
            Value::Array(blocks) => {
                let mut items = Vec::new();
                let mut text = Vec::new();
                for block in blocks {
                    match block["type"].as_str() {
                        Some("tool_result") => {
                            if let Some(item) = self.tool_result(block) {
                                items.push(item);
                            }
                        }
                        Some("text") => text.extend(block["text"].as_str()),
                        _ => {}
                    }
                }
                let text = text.join("\n");
                if let Some(text) = user_text(&text) {
                    items.insert(
                        0,
                        ChatItem {
                            id: uuid.to_owned(),
                            ts,
                            body: ChatBody::User { text },
                        },
                    );
                }
                items
            }
            _ => Vec::new(),
        }
    }

    fn tool_result(&mut self, block: &Value) -> Option<ChatItem> {
        let id = block["tool_use_id"].as_str()?;
        let item = self.tools.get_mut(id)?;
        if let ChatBody::Tool { status, output, .. } = &mut item.body {
            *status = if block["is_error"] == true {
                ToolStatus::Error
            } else {
                ToolStatus::Done
            };
            *output = Some(result_text(&block["content"]));
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
                        ChatBody::Agent { text }
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
                            title: tool_title(&name, &input),
                            name,
                            input,
                            status: ToolStatus::Running,
                            output: None,
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

/// The one line shown on a collapsed tool row.
fn tool_title(name: &str, input: &Value) -> String {
    let field = |k: &str| input[k].as_str().map(str::to_owned);
    let title = match name {
        "AskUserQuestion" => input["questions"][0]["question"].as_str().map(str::to_owned),
        _ => ["file_path", "command", "pattern", "url", "path", "description", "prompt"]
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
