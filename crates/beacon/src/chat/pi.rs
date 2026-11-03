//! Pi: pairing a Terminal with its session file, and reading the session
//! JSONL into Chat items. Facts: `docs/research/pi-integration.md`.
//!
//! Pairing: the Beacon's Pi extension (`crate::pi_extension`) labels the
//! Terminal with `aries.pi.file` (and `aries.pi.session`) whenever a
//! session starts or a prompt ends. The label outlives Pi, so a Pi process
//! must also be running in the Terminal. The file appears with the first
//! message.
//!
//! A session file is a tree (`id`/`parentId`; `/tree` starts new branches
//! in the same file). The Chat shows the path from the newest entry to the
//! root: when an entry continues an earlier node, items only on the
//! abandoned branch are removed and the new branch's are sent.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use aries_protocol::chat::{
    ChatBody, ChatDiff, ChatEvent, ChatImage, ChatItem, DiffHunk, ToolStatus,
};
use serde_json::Value;

use super::{Agent, Interpreter, Located, TerminalFacts, media};

pub const AGENT: &str = "pi";
pub const FILE_LABEL: &str = "aries.pi.file";
pub const SESSION_LABEL: &str = "aries.pi.session";

pub struct Pi;

impl Agent for Pi {
    fn name(&self) -> &'static str {
        AGENT
    }

    fn locate(&self, terminal: &TerminalFacts) -> Option<Located> {
        let file = terminal.labels.get(FILE_LABEL)?.as_str()?;
        if file.is_empty() || !terminal.pids().into_iter().any(is_pi) {
            return None;
        }
        let path = PathBuf::from(file);
        let session = terminal
            .labels
            .get(SESSION_LABEL)
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .or_else(|| Some(path.file_stem()?.to_string_lossy().into_owned()))?;
        Some(Located { session, path })
    }

    fn interpreter(&self) -> Box<dyn Interpreter> {
        Box::<PiInterpreter>::default()
    }
}

/// Pi sets its process title to `pi`; before that (or under a wrapper)
/// it is `node …/pi`.
fn is_pi(pid: u32) -> bool {
    let Ok(raw) = std::fs::read(format!("/proc/{pid}/cmdline")) else {
        return false;
    };
    let args: Vec<String> = raw
        .split(|b| *b == 0)
        .take(2)
        .map(|a| String::from_utf8_lossy(a).into_owned())
        .collect();
    let named_pi = |a: &String| {
        // A retitled process may keep its old argv as one space-joined string.
        let first = a.split(' ').next().unwrap_or_default();
        Path::new(first).file_name().is_some_and(|n| n == "pi")
    };
    args.first().is_some_and(named_pi)
        || args
            .get(1)
            .is_some_and(|a| named_pi(a) || a.ends_with("pi-coding-agent/dist/cli.js"))
}

struct Entry {
    parent: Option<String>,
    /// The full items this entry sets (a tool result re-sets its call).
    items: Vec<ChatItem>,
}

/// Reads a Pi session file (version 3).
#[derive(Default)]
pub struct PiInterpreter {
    entries: HashMap<String, Entry>,
    leaf: Option<String>,
    shown: HashMap<String, ChatItem>,
    order: Vec<String>,
}

impl Interpreter for PiInterpreter {
    fn line(&mut self, line: &str) -> Vec<ChatEvent> {
        let Ok(entry) = serde_json::from_str::<Value>(line) else {
            return Vec::new();
        };
        let Some(id) = entry["id"].as_str().map(str::to_owned) else {
            return Vec::new();
        };
        if entry["type"] == "session" || self.entries.contains_key(&id) {
            return Vec::new();
        }
        let parent = entry["parentId"].as_str().map(str::to_owned);
        let mut events = Vec::new();
        if parent != self.leaf {
            events.extend(self.switch_to(parent.clone()));
        }
        let items = self.items(&id, &entry, parent.as_deref());
        for item in &items {
            self.show(item.clone());
            events.push(ChatEvent::Item { item: item.clone() });
        }
        self.entries.insert(id.clone(), Entry { parent, items });
        self.leaf = Some(id);
        events
    }
}

impl PiInterpreter {
    fn show(&mut self, item: ChatItem) {
        if !self.shown.contains_key(&item.id) {
            self.order.push(item.id.clone());
        }
        self.shown.insert(item.id.clone(), item);
    }

    /// Makes `target` the leaf: the Chat becomes the path from it to the root.
    fn switch_to(&mut self, target: Option<String>) -> Vec<ChatEvent> {
        let mut path = Vec::new();
        let mut seen = HashSet::new();
        let mut at = target.clone();
        while let Some(id) = at {
            if !seen.insert(id.clone()) {
                break;
            }
            let Some(entry) = self.entries.get(&id) else {
                break;
            };
            path.push(id);
            at = entry.parent.clone();
        }
        let mut shown = HashMap::new();
        let mut order = Vec::new();
        for id in path.iter().rev() {
            for item in &self.entries[id].items {
                if !shown.contains_key(&item.id) {
                    order.push(item.id.clone());
                }
                shown.insert(item.id.clone(), item.clone());
            }
        }
        let mut events: Vec<ChatEvent> = self
            .order
            .iter()
            .filter(|id| !shown.contains_key(*id))
            .map(|id| ChatEvent::Remove { id: id.clone() })
            .collect();
        for id in &order {
            if self.shown.get(id) != shown.get(id) {
                events.push(ChatEvent::Item {
                    item: shown[id].clone(),
                });
            }
        }
        self.shown = shown;
        self.order = order;
        self.leaf = target;
        events
    }

    fn items(&self, id: &str, entry: &Value, parent: Option<&str>) -> Vec<ChatItem> {
        if entry["type"] != "message" {
            return Vec::new();
        }
        let ts = entry["timestamp"].as_str().map(str::to_owned);
        let message = &entry["message"];
        let item = |id: String, body| ChatItem {
            id,
            ts: ts.clone(),
            body,
        };
        match message["role"].as_str() {
            Some("user") => {
                let (text, images) = text_and_images(&message["content"]);
                if text.trim().is_empty() && images.is_empty() {
                    return Vec::new();
                }
                vec![item(
                    id.to_owned(),
                    ChatBody::User {
                        text,
                        images,
                        queued: false,
                    },
                )]
            }
            Some("assistant") => self.assistant(id, message, parent, item),
            Some("toolResult") => self
                .tool_result(message)
                .map(|(id, body)| item(id, body))
                .into_iter()
                .collect(),
            Some("bashExecution") => {
                let command = message["command"].as_str().unwrap_or_default().to_owned();
                let ok = message["exitCode"] == 0 && message["cancelled"] != true;
                vec![item(
                    id.to_owned(),
                    ChatBody::Tool {
                        details: super::details::tool(message, message),
                        name: "bash".into(),
                        title: command.clone(),
                        input: serde_json::json!({ "command": command }),
                        status: if ok {
                            ToolStatus::Done
                        } else {
                            ToolStatus::Error
                        },
                        output: message["output"].as_str().map(str::to_owned),
                        diff: None,
                        images: Vec::new(),
                        question: None,
                    },
                )]
            }
            _ => Vec::new(),
        }
    }

    fn assistant(
        &self,
        id: &str,
        message: &Value,
        parent: Option<&str>,
        item: impl Fn(String, ChatBody) -> ChatItem,
    ) -> Vec<ChatItem> {
        let mut items = Vec::new();
        let blocks = message["content"]
            .as_array()
            .map(Vec::as_slice)
            .unwrap_or_default();
        for (i, block) in blocks.iter().enumerate() {
            let block_id = if i == 0 {
                id.to_owned()
            } else {
                format!("{id}:{i}")
            };
            match block["type"].as_str() {
                Some("text") => {
                    let text = block["text"].as_str().unwrap_or_default();
                    if !text.trim().is_empty() {
                        items.push(item(
                            block_id,
                            ChatBody::Agent {
                                text: text.into(),
                                metadata: super::details::metadata(
                                    aries_protocol::chat::ResponseMetadata {
                                        model: super::details::text(&message["model"]),
                                        input_tokens: message["usage"]["input"].as_u64(),
                                        output_tokens: message["usage"]["output"].as_u64(),
                                        cache_read_tokens: message["usage"]["cacheRead"].as_u64(),
                                        cache_write_tokens: message["usage"]["cacheWrite"].as_u64(),
                                        completion: super::details::completion(
                                            &message["stopReason"],
                                        ),
                                        ..Default::default()
                                    },
                                ),
                            },
                        ));
                    }
                }
                Some("thinking") => {
                    let text = block["thinking"]
                        .as_str()
                        .filter(|t| !t.trim().is_empty() && block["redacted"] != true)
                        .map(str::to_owned);
                    items.push(item(block_id, ChatBody::Thinking { text }));
                }
                Some("toolCall") => {
                    let Some(call) = block["id"].as_str() else {
                        continue;
                    };
                    let name = block["name"].as_str().unwrap_or("tool").to_owned();
                    let input = block["arguments"].clone();
                    items.push(item(
                        call.to_owned(),
                        ChatBody::Tool {
                            details: super::details::tool(&input, &Value::Null),
                            title: tool_title(&name, &input),
                            name,
                            input,
                            status: ToolStatus::Running,
                            output: None,
                            diff: None,
                            images: Vec::new(),
                            question: None,
                        },
                    ));
                }
                _ => {}
            }
        }
        if message["stopReason"] == "error" {
            // Pi retries a failed request as a new entry under the failed
            // one: the whole retry chain is one error in the Chat.
            let retried = parent
                .and_then(|p| self.entries.get(p))
                .and_then(|e| {
                    e.items
                        .iter()
                        .rfind(|i| matches!(i.body, ChatBody::Error { .. }))
                })
                .map(|i| i.id.clone());
            let message = message["errorMessage"]
                .as_str()
                .filter(|m| !m.is_empty())
                .unwrap_or("error")
                .to_owned();
            items.push(item(
                retried.unwrap_or_else(|| format!("{id}:error")),
                ChatBody::Error { message },
            ));
        }
        items
    }

    /// The tool call's item again, with its result.
    fn tool_result(&self, message: &Value) -> Option<(String, ChatBody)> {
        let call = message["toolCallId"].as_str()?;
        let (name, title, input) = match self.shown.get(call).map(|i| &i.body) {
            Some(ChatBody::Tool {
                name, title, input, ..
            }) => (name.clone(), title.clone(), input.clone()),
            _ => (
                message["toolName"].as_str().unwrap_or("tool").to_owned(),
                String::new(),
                Value::Null,
            ),
        };
        let failed = message["isError"] == true;
        let (output, images) = text_and_images(&message["content"]);
        let diff = if failed {
            None
        } else {
            let path = input["path"].as_str().unwrap_or_default();
            match name.as_str() {
                "edit" => message["details"]["diff"]
                    .as_str()
                    .and_then(|d| numbered_diff(path, d)),
                "write" => input["content"].as_str().map(|c| media::created(path, c)),
                _ => None,
            }
        };
        Some((
            call.to_owned(),
            ChatBody::Tool {
                details: super::details::tool(&input, &message["details"]),
                name,
                title,
                input,
                status: if failed {
                    ToolStatus::Error
                } else {
                    ToolStatus::Done
                },
                output: Some(output),
                diff,
                images,
                question: None,
            },
        ))
    }
}

/// Text blocks joined by newlines, and image blocks
/// (`{type: image, data, mimeType}`). A plain string is all text.
fn text_and_images(content: &Value) -> (String, Vec<ChatImage>) {
    match content {
        Value::String(s) => (s.clone(), Vec::new()),
        Value::Array(blocks) => {
            let mut text = Vec::new();
            let mut images = Vec::new();
            for block in blocks {
                match block["type"].as_str() {
                    Some("text") => text.extend(block["text"].as_str()),
                    Some("image") => {
                        if let Some(data) = block["data"].as_str() {
                            let media_type = block["mimeType"].as_str().unwrap_or("image/png");
                            images.push(media::image(media_type, data, None));
                        }
                    }
                    _ => {}
                }
            }
            (text.join("\n"), images)
        }
        _ => (String::new(), Vec::new()),
    }
}

fn tool_title(name: &str, input: &Value) -> String {
    let field = match name {
        "bash" => "command",
        "read" | "write" | "edit" | "ls" => "path",
        "grep" | "find" => "pattern",
        _ => "",
    };
    let pick = |k: &str| input[k].as_str().filter(|s| !s.is_empty());
    pick(field)
        .or_else(|| {
            ["path", "command", "pattern", "url", "query"]
                .into_iter()
                .find_map(pick)
        })
        .unwrap_or_default()
        .lines()
        .next()
        .unwrap_or_default()
        .to_owned()
}

/// Pi's edit diff (`details.diff`): one line per change or context line,
/// `<sign><line number> <text>` (the number right-aligned; old numbers for
/// context and removals, new ones for additions), with ` <pad> ...` where
/// unchanged lines are skipped.
pub fn numbered_diff(path: &str, text: &str) -> Option<ChatDiff> {
    let mut hunks = Vec::new();
    let mut current: Option<DiffHunk> = None;
    // New line number minus old, after the lines so far.
    let mut delta: i64 = 0;
    for raw in text.lines() {
        let sign = raw.chars().next()?;
        let rest = raw[sign.len_utf8()..].trim_start();
        if sign == ' ' && rest == "..." {
            hunks.extend(current.take());
            continue;
        }
        if !matches!(sign, ' ' | '-' | '+') {
            return None;
        }
        let (num, line) = rest.split_once(' ').unwrap_or((rest, ""));
        let num: i64 = num.parse().ok()?;
        let hunk = current.get_or_insert_with(|| {
            let (old, new) = if sign == '+' {
                (num - delta, num)
            } else {
                (num, num + delta)
            };
            DiffHunk {
                old_start: old.max(0) as u32,
                old_lines: 0,
                new_start: new.max(0) as u32,
                new_lines: 0,
                lines: Vec::new(),
            }
        });
        match sign {
            ' ' => {
                hunk.old_lines += 1;
                hunk.new_lines += 1;
            }
            '-' => {
                hunk.old_lines += 1;
                delta -= 1;
            }
            _ => {
                hunk.new_lines += 1;
                delta += 1;
            }
        }
        hunk.lines.push(format!("{sign}{line}"));
    }
    hunks.extend(current);
    (!hunks.is_empty()).then(|| media::diff(path, hunks))
}
