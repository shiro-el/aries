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
use tokio::sync::{Mutex, Notify, watch};
use tokio::task::JoinHandle;
use tokio::time::Instant;

use crate::chat_cache::{CachedChat, ChatCache, SAVE_EVERY};
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
    },
    AgentError {
        detail: String,
    },
    /// Something a newer Beacon sends that this app cannot show yet.
    Unsupported,
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
    /// The Beacon's items behind `entries`, same order (what the cache keeps).
    items: Vec<ChatItem>,
    index: HashMap<String, usize>,
    pub status: ChatStatus,
    pub cursor: Option<String>,
}

impl ChatModel {
    pub fn new(cursor: Option<String>) -> Self {
        Self {
            entries: Vec::new(),
            items: Vec::new(),
            index: HashMap::new(),
            status: ChatStatus::Connecting,
            cursor,
        }
    }

    /// A model holding a cached Chat.
    pub fn cached(chat: CachedChat) -> Self {
        let mut model = Self::new(chat.cursor);
        for item in chat.items {
            model.upsert(item);
        }
        model
    }

    pub fn to_cache(&self) -> CachedChat {
        CachedChat {
            items: self.items.clone(),
            cursor: self.cursor.clone(),
        }
    }

    fn upsert(&mut self, item: ChatItem) {
        let entry = entry(item.clone());
        match self.index.get(&entry.id) {
            Some(&i) => {
                self.entries[i] = entry;
                self.items[i] = item;
            }
            None => {
                self.index.insert(entry.id.clone(), self.entries.len());
                self.entries.push(entry);
                self.items.push(item);
            }
        }
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
                self.items.clear();
                self.index.clear();
            }
            ChatEvent::Item { item } => self.upsert(item),
            ChatEvent::Remove { id } => {
                if self.index.remove(&id).is_some() {
                    self.entries.retain(|e| e.id != id);
                    self.items.retain(|e| e.id != id);
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

fn entry(item: ChatItem) -> ChatEntry {
    let body = match item.body {
        ChatBody::User { text } => ChatEntryBody::User { text },
        ChatBody::Agent { text } => ChatEntryBody::Agent { text },
        ChatBody::Thinking { text } => ChatEntryBody::Thinking { text },
        ChatBody::Tool {
            name,
            title,
            input,
            status,
            output,
        } => ChatEntryBody::Tool {
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
    wake: Arc<Wake>,
}

/// The app's reconnect signals (see [`ChatWatch::reconnect`]).
#[derive(Default)]
struct Wake {
    /// Drop the current line and connect again now.
    replace: Notify,
    /// Skip the wait before the next attempt.
    retry: Notify,
}

impl ChatWatch {
    pub(crate) fn start(
        host: ChatHost,
        terminal: String,
        cursor: Option<String>,
        cache: Option<CacheSlot>,
        retry: Duration,
    ) -> Self {
        let model = ChatModel::new(cursor);
        let (tx, rx) = watch::channel(Some(model.snapshot()));
        let wake = Arc::new(Wake::default());
        let signals = wake.clone();
        let task = crate::runtime().spawn(async move {
            let mut model = model;
            if let Some(slot) = &cache
                && let Some(cached) = slot.load(&terminal).await
                // An explicit cursor wins unless it is the cached one.
                && (model.cursor.is_none() || model.cursor == cached.cursor)
            {
                model = ChatModel::cached(cached);
                if tx.send(Some(model.snapshot())).is_err() {
                    return;
                }
            }
            follow(host, terminal, model, cache, tx, retry, &signals).await
        });
        Self {
            rx: Arc::new(Mutex::new(rx)),
            task,
            wake,
        }
    }
}

/// Where a watch keeps its Chat between runs.
pub(crate) struct CacheSlot {
    pub cache: ChatCache,
    pub host: String,
}

impl CacheSlot {
    async fn load(&self, terminal: &str) -> Option<CachedChat> {
        let (cache, host, terminal) = (self.cache.clone(), self.host.clone(), terminal.to_owned());
        tokio::task::spawn_blocking(move || cache.load(&host, &terminal))
            .await
            .ok()?
            .ok()?
    }

    /// Best effort: a Chat that cannot be cached still shows.
    async fn save(&self, terminal: &str, model: &ChatModel) {
        let (cache, host, terminal) = (self.cache.clone(), self.host.clone(), terminal.to_owned());
        let chat = model.to_cache();
        let _ = tokio::task::spawn_blocking(move || cache.save(&host, &terminal, &chat)).await;
    }
}

/// Writes the model to the cache at most every [`SAVE_EVERY`], and when
/// asked to flush.
struct Saver<'a> {
    slot: Option<&'a CacheSlot>,
    dirty: bool,
    next: Instant,
}

impl Saver<'_> {
    /// When the pending write is due, if one is pending.
    fn due(&self) -> Option<Instant> {
        (self.dirty && self.slot.is_some()).then_some(self.next)
    }

    async fn changed(&mut self, terminal: &str, model: &ChatModel) {
        self.dirty = true;
        if Instant::now() >= self.next {
            self.flush(terminal, model).await;
        }
    }

    async fn flush(&mut self, terminal: &str, model: &ChatModel) {
        if let (true, Some(slot)) = (self.dirty, self.slot) {
            slot.save(terminal, model).await;
            self.next = Instant::now() + SAVE_EVERY;
        }
        self.dirty = false;
    }
}

async fn follow(
    host: ChatHost,
    terminal: String,
    mut model: ChatModel,
    cache: Option<CacheSlot>,
    tx: watch::Sender<Option<ChatSnapshot>>,
    retry: Duration,
    wake: &Wake,
) {
    let mut saver = Saver {
        slot: cache.as_ref(),
        dirty: false,
        next: Instant::now(),
    };
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
            let mut heard = Instant::now();
            loop {
                let silent = heard + SILENCE;
                let wake_at = saver.due().map_or(silent, |d| d.min(silent));
                let next = tokio::select! {
                    next = tokio::time::timeout_at(wake_at, lines.next_line()) => next,
                    _ = wake.replace.notified() => break,
                };
                let text = match next {
                    Ok(Ok(Some(text))) => text,
                    Ok(_) => break,
                    Err(_) if Instant::now() >= silent => break,
                    Err(_) => {
                        saver.flush(&terminal, &model).await;
                        continue;
                    }
                };
                heard = Instant::now();
                model.apply_text(&text);
                backoff = retry;
                // Publish once the burst is read, not for every line.
                let burst_read = lines.get_ref().buffer().is_empty();
                if burst_read || model.status.is_final() {
                    saver.changed(&terminal, &model).await;
                    if tx.send(Some(model.snapshot())).is_err() {
                        saver.flush(&terminal, &model).await;
                        return;
                    }
                }
                if model.status.is_final() {
                    saver.flush(&terminal, &model).await;
                    return;
                }
            }
            drop((writer, guard));
            saver.flush(&terminal, &model).await;
        }
        if model.status.is_final() {
            return;
        }
        model.status = ChatStatus::Reconnecting;
        if tx.send(Some(model.snapshot())).is_err() {
            return;
        }
        tokio::select! {
            _ = tokio::time::sleep(backoff) => backoff = (backoff * 2).min(Duration::from_secs(30)),
            _ = wake.replace.notified() => backoff = retry,
            _ = wake.retry.notified() => backoff = retry,
        }
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

    /// Stops following; the Beacon on the Host exits.
    /// The phone's network changed: the line is likely dead, so drop it
    /// and connect again now, after the last cursor.
    pub fn reconnect(&self) {
        self.wake.replace.notify_waiters();
    }

    /// The app is back in front: if the Chat waits to reconnect, try now.
    /// A live line is kept (a dead one is noticed by its silence).
    pub fn retry_now(&self) {
        self.wake.retry.notify_waiters();
    }

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
