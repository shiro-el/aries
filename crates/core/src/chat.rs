//! A Terminal's Agent Chat, read through the Host's Beacon.
//!
//! The core runs `aries-beacon chat <terminal> --follow` on the Host (an SSH
//! exec channel; a local shell for Local endpoints) and applies its
//! Agent-neutral lines (`aries_protocol::chat`) to a list of entries. When
//! the stream breaks it reconnects with the last cursor, so nothing is lost
//! or shown twice. The app never sees a transcript format.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use aries_protocol::chat::{
    ChatBody, ChatEvent, ChatItem, ChatLine, ChatLineError, ChatState, ToolStatus,
};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::{Mutex, watch};
use tokio::task::JoinHandle;

use crate::error::CoreError;
use crate::transport::{Ssh, Stream};

/// One Chat entry, ready to render.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatEntry {
    /// Stable while the transcript stays the same; use it as the list key.
    pub id: String,
    /// RFC 3339 time the Agent recorded, when it did.
    pub timestamp: Option<String>,
    pub body: ChatEntryBody,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ChatEntryBody {
    User {
        text: String,
        /// Pictures the user attached.
        #[uniffi(default)]
        images: Vec<ChatImage>,
    },
    Agent {
        text: String,
    },
    /// `text` is `None` when the Agent does not record what it thought.
    Thinking {
        text: Option<String>,
    },
    /// Shown collapsed as `name` + `title`; expanded shows `input` (pretty
    /// JSON) and `output`.
    Tool {
        name: String,
        title: String,
        input: String,
        status: ChatToolStatus,
        output: Option<String>,
        /// What a file edit changed; render it instead of raw input.
        #[uniffi(default)]
        diff: Option<ChatDiff>,
        /// Pictures in the result.
        #[uniffi(default)]
        images: Vec<ChatImage>,
    },
    AgentError {
        detail: String,
    },
    /// Something a newer Beacon sends that this app cannot show yet.
    Unsupported,
}

/// An image in the Chat. The entry only describes it; the bytes come from
/// [`ChatWatch::image`] when the app draws it, so snapshots stay small.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatImage {
    /// Key for [`ChatWatch::image`].
    pub id: String,
    pub media_type: String,
    /// Decoded size in bytes.
    pub bytes: u64,
    pub width: Option<u32>,
    pub height: Option<u32>,
    /// `false` when the image is over the size cap
    /// (`aries_protocol::chat::IMAGE_MAX_BYTES`): show a placeholder.
    pub available: bool,
}

/// A file edit as unified-diff hunks.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatDiff {
    pub path: String,
    pub hunks: Vec<ChatDiffHunk>,
    /// The Beacon cut a very large diff.
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatDiffHunk {
    pub old_start: u32,
    pub new_start: u32,
    /// Each starts with `-`, `+` or ` `.
    pub lines: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChatToolStatus {
    Running,
    Done,
    Failed,
}

/// Where the Chat stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChatStatus {
    Connecting,
    /// Caught up and following.
    Live,
    /// The Agent runs but has not written anything yet.
    Waiting,
    /// No supported Agent in this Terminal (yet).
    NoAgent,
    /// The Agent exited; the entries are its last Chat.
    Ended,
    /// The connection broke; reconnecting.
    Reconnecting,
    /// The Terminal is gone. Final.
    NoTerminal,
    /// The Host has no Beacon. Final.
    NoBeacon,
    /// The Beacon cannot reach the Host's herdr-core; retrying.
    NoCore,
    /// The Beacon speaks a newer Chat format: update Aries. Final.
    Incompatible,
}

impl ChatStatus {
    fn is_final(self) -> bool {
        matches!(self, Self::NoTerminal | Self::NoBeacon | Self::Incompatible)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatSnapshot {
    pub entries: Vec<ChatEntry>,
    pub status: ChatStatus,
    /// Resume point of what `entries` covers.
    pub cursor: Option<String>,
}

/// Applies Chat lines to entries (upsert by id; `reset` clears).
#[derive(Debug, Clone)]
pub(crate) struct ChatModel {
    entries: Vec<ChatEntry>,
    index: HashMap<String, usize>,
    pub status: ChatStatus,
    pub cursor: Option<String>,
    /// Decoded image bytes by [`ChatImage::id`], shared with [`ChatWatch`].
    pub images: Images,
}

pub(crate) type Images = Arc<std::sync::Mutex<HashMap<String, Arc<Vec<u8>>>>>;

impl ChatModel {
    pub fn new(cursor: Option<String>) -> Self {
        Self {
            entries: Vec::new(),
            index: HashMap::new(),
            status: ChatStatus::Connecting,
            cursor,
            images: Images::default(),
        }
    }

    fn images(&self) -> std::sync::MutexGuard<'_, HashMap<String, Arc<Vec<u8>>>> {
        self.images.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn forget_images(&self, entry: &str) {
        let prefix = format!("{entry}#");
        self.images().retain(|k, _| !k.starts_with(&prefix));
    }

    /// Describes an item's images and keeps their bytes for
    /// [`ChatWatch::image`].
    fn keep_images(
        &self,
        entry: &str,
        images: Vec<aries_protocol::chat::ChatImage>,
    ) -> Vec<ChatImage> {
        use base64::Engine as _;
        self.forget_images(entry);
        let mut store = self.images();
        images
            .into_iter()
            .enumerate()
            .map(|(i, image)| {
                let id = format!("{entry}#{i}");
                let data = image.data.and_then(|d| {
                    base64::engine::general_purpose::STANDARD
                        .decode(d.trim())
                        .ok()
                });
                let available = data.is_some();
                if let Some(data) = data {
                    store.insert(id.clone(), Arc::new(data));
                }
                ChatImage {
                    id,
                    media_type: image.media_type,
                    bytes: image.bytes,
                    width: image.width,
                    height: image.height,
                    available,
                }
            })
            .collect()
    }

    pub fn snapshot(&self) -> ChatSnapshot {
        ChatSnapshot {
            entries: self.entries.clone(),
            status: self.status,
            cursor: self.cursor.clone(),
        }
    }

    /// Applies one line of Beacon output. Lines that are not Chat lines
    /// (login noise) are skipped.
    pub fn apply_text(&mut self, text: &str) {
        match ChatLine::parse(text) {
            Ok(line) => self.apply(line),
            Err(ChatLineError::NewerFormat(_)) => self.status = ChatStatus::Incompatible,
            Err(ChatLineError::Malformed(_)) => {}
        }
    }

    pub fn apply(&mut self, line: ChatLine) {
        match line.event {
            ChatEvent::Reset { .. } => {
                self.entries.clear();
                self.index.clear();
                self.images().clear();
            }
            ChatEvent::Item { item } => {
                let entry = self.entry(item);
                match self.index.get(&entry.id) {
                    Some(&i) => self.entries[i] = entry,
                    None => {
                        self.index.insert(entry.id.clone(), self.entries.len());
                        self.entries.push(entry);
                    }
                }
            }
            ChatEvent::Remove { id } => {
                self.forget_images(&id);
                if self.index.remove(&id).is_some() {
                    self.entries.retain(|e| e.id != id);
                    self.index = (self.entries.iter().enumerate())
                        .map(|(i, e)| (e.id.clone(), i))
                        .collect();
                }
            }
            ChatEvent::State { state } => {
                if let Some(s) = status(state) {
                    self.status = s;
                }
            }
            ChatEvent::Heartbeat | ChatEvent::Unknown => {}
        }
        if let Some(c) = line.cursor {
            self.cursor = Some(c);
        }
    }
}

fn status(state: ChatState) -> Option<ChatStatus> {
    Some(match state {
        ChatState::Live => ChatStatus::Live,
        ChatState::Waiting => ChatStatus::Waiting,
        ChatState::NoAgent => ChatStatus::NoAgent,
        ChatState::Ended => ChatStatus::Ended,
        ChatState::NoTerminal => ChatStatus::NoTerminal,
        ChatState::NoCore => ChatStatus::NoCore,
        ChatState::NoBeacon => ChatStatus::NoBeacon,
        ChatState::Unknown => return None,
    })
}

impl ChatModel {
    fn entry(&self, item: ChatItem) -> ChatEntry {
        let body = match item.body {
            ChatBody::User { text, images } => ChatEntryBody::User {
                text,
                images: self.keep_images(&item.id, images),
            },
            ChatBody::Agent { text } => ChatEntryBody::Agent { text },
            ChatBody::Thinking { text } => ChatEntryBody::Thinking { text },
            ChatBody::Tool {
                name,
                title,
                input,
                status,
                output,
                diff,
                images,
            } => ChatEntryBody::Tool {
                images: self.keep_images(&item.id, images),
                diff: diff.map(|d| ChatDiff {
                    path: d.path,
                    truncated: d.truncated,
                    hunks: (d.hunks.into_iter())
                        .map(|h| ChatDiffHunk {
                            old_start: h.old_start,
                            new_start: h.new_start,
                            lines: h.lines,
                        })
                        .collect(),
                }),
                name,
                title,
                input: match input {
                    serde_json::Value::Null => String::new(),
                    serde_json::Value::String(s) => s,
                    v => serde_json::to_string_pretty(&v).unwrap_or_default(),
                },
                status: match status {
                    ToolStatus::Running | ToolStatus::Unknown => ChatToolStatus::Running,
                    ToolStatus::Done => ChatToolStatus::Done,
                    ToolStatus::Error => ChatToolStatus::Failed,
                },
                output,
            },
            ChatBody::Error { message } => ChatEntryBody::AgentError { detail: message },
            ChatBody::Unsupported => ChatEntryBody::Unsupported,
        };
        ChatEntry {
            id: item.id,
            timestamp: item.ts,
            body,
        }
    }
}

/// How to run a command on the Host for the Chat.
pub(crate) enum ChatHost {
    Ssh(Box<Ssh>),
    /// Runs `sh -c` locally with these variables (Local endpoints).
    Local(HashMap<String, String>),
}

/// Environment a local run must not inherit from the app's own process
/// unless the endpoint sets it.
const LEAKY_ENV: &[&str] = &[
    "HC_SOCKET",
    "HC_SCOPE",
    "HC_TERMINAL_ID",
    "HC_TERMINAL_SOCKET",
    "CLAUDECODE",
    "CLAUDE_CODE_CHILD_SESSION",
];

impl ChatHost {
    async fn open(&self, command: &str) -> Result<Stream, CoreError> {
        match self {
            Self::Ssh(ssh) => ssh.exec_stream(command).await,
            Self::Local(env) => {
                let mut cmd = tokio::process::Command::new("/bin/sh");
                cmd.arg("-c").arg(command);
                for k in LEAKY_ENV {
                    if !env.contains_key(*k) {
                        cmd.env_remove(k);
                    }
                }
                let mut child = cmd
                    .envs(env)
                    .stdin(std::process::Stdio::piped())
                    .stdout(std::process::Stdio::piped())
                    .stderr(std::process::Stdio::null())
                    .kill_on_drop(true)
                    .spawn()
                    .map_err(|e| CoreError::Unreachable {
                        detail: format!("sh: {e}"),
                    })?;
                let stdin = child.stdin.take().expect("piped stdin");
                let stdout = child.stdout.take().expect("piped stdout");
                Ok(Stream {
                    reader: Box::new(stdout),
                    writer: Box::new(stdin),
                    guard: Box::new(std::sync::Mutex::new(child)),
                })
            }
        }
    }
}

fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

/// The Host-side command. `ARIES_BEACON` overrides the installed path (for
/// development); without a Beacon it prints a `no_beacon` state line.
pub(crate) fn command(terminal: &str, cursor: Option<&str>) -> String {
    let no_beacon = ChatLine::new(ChatEvent::State {
        state: ChatState::NoBeacon,
    })
    .to_json();
    let cursor = cursor
        .map(|c| format!(" --cursor {}", quote(c)))
        .unwrap_or_default();
    let script = format!(
        r#"b="${{ARIES_BEACON:-$HOME/.local/bin/aries-beacon}}"; if [ -x "$b" ]; then exec "$b" chat {} --follow{cursor}; else printf '%s\n' {}; fi"#,
        quote(terminal),
        quote(&no_beacon),
    );
    format!("sh -c {}", quote(&script))
}

/// Silence after which the stream counts as dead (the Beacon sends a
/// heartbeat every 15 s).
const SILENCE: Duration = Duration::from_secs(45);

/// A running Chat for one Terminal.
#[derive(uniffi::Object)]
pub struct ChatWatch {
    rx: Arc<Mutex<watch::Receiver<Option<ChatSnapshot>>>>,
    task: JoinHandle<()>,
    images: Images,
}

impl ChatWatch {
    pub(crate) fn start(
        host: ChatHost,
        terminal: String,
        cursor: Option<String>,
        retry: Duration,
    ) -> Self {
        let model = ChatModel::new(cursor);
        let (tx, rx) = watch::channel(Some(model.snapshot()));
        let images = model.images.clone();
        let task = crate::runtime().spawn(follow(host, terminal, model, tx, retry));
        Self {
            rx: Arc::new(Mutex::new(rx)),
            task,
            images,
        }
    }
}

async fn follow(
    host: ChatHost,
    terminal: String,
    mut model: ChatModel,
    tx: watch::Sender<Option<ChatSnapshot>>,
    retry: Duration,
) {
    let mut backoff = retry;
    loop {
        let command = command(&terminal, model.cursor.as_deref());
        if let Ok(stream) = host.open(&command).await {
            // The writer and guard stay alive while reading; dropping them
            // ends the Host command.
            let Stream {
                reader,
                writer,
                guard,
            } = stream;
            let mut lines = BufReader::new(reader).lines();
            loop {
                let next = tokio::time::timeout(SILENCE, lines.next_line()).await;
                let Ok(Ok(Some(text))) = next else { break };
                model.apply_text(&text);
                backoff = retry;
                // Publish once the burst is read, not for every line.
                let burst_read = lines.get_ref().buffer().is_empty();
                if (burst_read || model.status.is_final())
                    && tx.send(Some(model.snapshot())).is_err()
                {
                    return;
                }
                if model.status.is_final() {
                    return;
                }
            }
            drop((writer, guard));
        }
        if model.status.is_final() {
            return;
        }
        model.status = ChatStatus::Reconnecting;
        if tx.send(Some(model.snapshot())).is_err() {
            return;
        }
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(Duration::from_secs(30));
    }
}

#[uniffi::export]
impl ChatWatch {
    /// The Chat: right away on the first call, then each time it changes
    /// (intermediate states may be skipped). After a final status
    /// (`NoTerminal`, `NoBeacon`, `Incompatible`) or `stop`, fails with
    /// [`CoreError::Closed`].
    pub async fn next(&self) -> Result<ChatSnapshot, CoreError> {
        let rx = self.rx.clone();
        crate::on_runtime(async move {
            let mut rx = rx.lock().await;
            rx.changed().await.map_err(|_| CoreError::Closed)?;
            rx.borrow_and_update().clone().ok_or(CoreError::Closed)
        })
        .await
    }

    /// The bytes (PNG, JPEG, ...) of a [`ChatImage`] in the current Chat;
    /// `None` when it is over the cap or no longer in the Chat.
    pub fn image(&self, id: String) -> Option<Vec<u8>> {
        let store = self.images.lock().unwrap_or_else(|e| e.into_inner());
        store.get(&id).map(|b| b.as_ref().clone())
    }

    /// Stops following; the Beacon on the Host exits.
    pub fn stop(&self) {
        self.task.abort();
    }
}

impl Drop for ChatWatch {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_command_quotes_terminal_and_cursor() {
        let c = command("it's", Some("claude-code:s:1"));
        assert!(c.starts_with("sh -c '"), "{c}");
        let script = std::process::Command::new("/bin/sh")
            .arg("-c")
            .arg(format!("export ARIES_BEACON=/bin/echo; {c}"))
            .output()
            .unwrap();
        assert_eq!(
            String::from_utf8(script.stdout).unwrap(),
            "chat it's --follow --cursor claude-code:s:1\n"
        );
    }

    #[test]
    fn without_a_beacon_the_command_says_so() {
        let c = command("t1", None);
        let out = std::process::Command::new("/bin/sh")
            .arg("-c")
            .arg(format!("export ARIES_BEACON=/nonexistent; {c}"))
            .output()
            .unwrap();
        let mut model = ChatModel::new(None);
        model.apply_text(String::from_utf8(out.stdout).unwrap().trim());
        assert_eq!(model.status, ChatStatus::NoBeacon);
    }
}
