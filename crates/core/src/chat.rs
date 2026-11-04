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

use aries_protocol::answer::{Answer, AnswerOutcome, AnswerReply};

use crate::chat_cache::{CachedChat, ChatCache, SAVE_EVERY};
use crate::error::{AnswerProblem, CoreError};
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
// Keep the optional, flat records shared with the phone API.
#[allow(clippy::large_enum_variant)]
pub enum ChatEntryBody {
    User {
        text: String,
        /// Pictures the user attached.
        #[uniffi(default)]
        images: Vec<ChatImage>,
        /// Still waiting in the Agent's input queue.
        #[uniffi(default = false)]
        queued: bool,
    },
    Agent {
        text: String,
        #[uniffi(default)]
        metadata: Option<ChatResponseMetadata>,
    },
    /// `text` is `None` when the Agent does not record what it thought.
    Thinking {
        text: Option<String>,
    },
    /// Collapsed as `name` + `title`; expanded shows normalized details,
    /// output and a separate raw-input disclosure.
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
        #[uniffi(default)]
        details: Option<ChatToolDetails>,
    },
    AgentError {
        detail: String,
    },
    /// A recorded Agent question. Running means unresolved, not necessarily
    /// live. Only the current question while the Terminal is Blocked on a
    /// question may use `answer_question` / `answer_question_with_text`.
    /// The reserved name `permission` and `permission:` ID prefix instead
    /// identify a transient live permission menu. It takes picks only while
    /// permission-blocked, is rechecked on the Host, and is never cached.
    /// Codex queues unresolved requests FIFO in the current user turn; other
    /// Agents use the newest one. The Beacon validates again before input.
    /// With no live menu, a reply to the latest unresolved historical question
    /// is an ordinary `send_message`, subject to its Blocked gate. Older and
    /// completed questions are read-only. `output` records an Agent's answer.
    Question {
        name: String,
        title: String,
        questions: Vec<ChatQuestionPart>,
        status: ChatToolStatus,
        output: Option<String>,
    },
    /// Something a newer Beacon sends that this app cannot show yet.
    Unsupported,
}

/// Optional measurements from the Agent transcript.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatResponseMetadata {
    #[uniffi(default)]
    pub model: Option<String>,
    #[uniffi(default)]
    pub input_tokens: Option<u64>,
    #[uniffi(default)]
    pub output_tokens: Option<u64>,
    #[uniffi(default)]
    pub cache_read_tokens: Option<u64>,
    #[uniffi(default)]
    pub cache_write_tokens: Option<u64>,
    #[uniffi(default)]
    pub duration_ms: Option<u64>,
    #[uniffi(default)]
    pub completion: Option<ChatResponseCompletion>,
}

impl From<aries_protocol::chat::ResponseMetadata> for ChatResponseMetadata {
    fn from(value: aries_protocol::chat::ResponseMetadata) -> Self {
        Self {
            model: value.model,
            input_tokens: value.input_tokens,
            output_tokens: value.output_tokens,
            cache_read_tokens: value.cache_read_tokens,
            cache_write_tokens: value.cache_write_tokens,
            duration_ms: value.duration_ms,
            completion: value.completion.map(Into::into),
        }
    }
}

/// Optional measurements from the Agent transcript.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatToolDetails {
    #[uniffi(default)]
    pub command: Option<String>,
    #[uniffi(default)]
    pub cwd: Option<String>,
    #[uniffi(default)]
    pub exit_code: Option<i64>,
    #[uniffi(default)]
    pub duration_ms: Option<u64>,
    #[uniffi(default)]
    pub path: Option<String>,
    #[uniffi(default)]
    pub pattern: Option<String>,
    #[uniffi(default)]
    pub result_count: Option<u64>,
}

impl From<aries_protocol::chat::ToolDetails> for ChatToolDetails {
    fn from(value: aries_protocol::chat::ToolDetails) -> Self {
        Self {
            command: value.command,
            cwd: value.cwd,
            exit_code: value.exit_code,
            duration_ms: value.duration_ms,
            path: value.path,
            pattern: value.pattern,
            result_count: value.result_count,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChatResponseCompletion {
    Complete,
    Interrupted,
    Error,
}

impl From<aries_protocol::chat::ResponseCompletion> for ChatResponseCompletion {
    fn from(value: aries_protocol::chat::ResponseCompletion) -> Self {
        use aries_protocol::chat::ResponseCompletion;
        match value {
            ResponseCompletion::Complete => Self::Complete,
            ResponseCompletion::Interrupted => Self::Interrupted,
            ResponseCompletion::Error => Self::Error,
        }
    }
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

/// One question of a [`ChatEntryBody::Question`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatQuestionPart {
    pub text: String,
    pub header: Option<String>,
    /// Several options may be picked.
    pub multi: bool,
    pub options: Vec<ChatQuestionOption>,
    #[uniffi(default = None)]
    pub answer: Option<ChatQuestionAnswer>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatQuestionAnswer {
    pub choices: Vec<u32>,
    pub text: Option<String>,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatQuestionOption {
    pub label: String,
    pub description: Option<String>,
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
    #[uniffi(default = None)]
    pub usage: Option<crate::AgentUsage>,
}

/// Applies Chat lines to entries (upsert by id; `reset` clears).
#[derive(Debug, Clone)]
pub(crate) struct ChatModel {
    usage: Option<aries_protocol::chat::AgentUsage>,
    entries: Vec<ChatEntry>,
    /// The Beacon's items behind `entries`, same order (what the cache keeps).
    items: Vec<ChatItem>,
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
            usage: None,
            entries: Vec::new(),
            items: Vec::new(),
            index: HashMap::new(),
            status: ChatStatus::Connecting,
            cursor,
            images: Images::default(),
        }
    }

    /// Shows a cached Chat (its images included) and resumes after its cursor.
    pub fn seed(&mut self, chat: CachedChat) {
        self.cursor = chat.cursor;
        self.usage = chat.usage;
        for item in chat
            .items
            .into_iter()
            .filter(|item| !item.id.starts_with("permission:"))
        {
            self.upsert(item);
        }
    }

    // Live screen projections have no transcript cursor. Clear them before
    // resuming a stream, which will publish only the currently visible menu.
    fn clear_permissions(&mut self) {
        let ids: Vec<_> = self
            .items
            .iter()
            .filter(|item| item.id.starts_with("permission:"))
            .map(|item| item.id.clone())
            .collect();
        for id in ids {
            self.apply(ChatLine::new(ChatEvent::Remove { id }));
        }
    }

    pub fn to_cache(&self) -> CachedChat {
        CachedChat {
            usage: self.usage.clone(),
            items: self
                .items
                .iter()
                .filter(|item| !item.id.starts_with("permission:"))
                .cloned()
                .collect(),
            cursor: self.cursor.clone(),
        }
    }

    fn upsert(&mut self, item: ChatItem) {
        let entry = self.entry(item.clone());
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
            usage: self.usage.clone().map(Into::into),
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
                self.usage = None;
                self.entries.clear();
                self.items.clear();
                self.index.clear();
                self.images().clear();
            }
            ChatEvent::Usage { usage } => self.usage = Some(usage),
            ChatEvent::Item { item } => self.upsert(item),
            ChatEvent::Remove { id } => {
                self.forget_images(&id);
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

impl ChatModel {
    fn entry(&self, item: ChatItem) -> ChatEntry {
        let body = match item.body {
            ChatBody::User {
                text,
                images,
                queued,
            } => ChatEntryBody::User {
                queued,
                text,
                images: self.keep_images(&item.id, images),
            },
            ChatBody::Agent { text, metadata } => ChatEntryBody::Agent {
                text,
                metadata: metadata.map(Into::into),
            },
            ChatBody::Thinking { text } => ChatEntryBody::Thinking { text },
            ChatBody::Tool {
                name,
                title,
                status,
                output,
                question: Some(q),
                ..
            } => ChatEntryBody::Question {
                name,
                title,
                questions: q
                    .questions
                    .into_iter()
                    .map(|q| ChatQuestionPart {
                        text: q.text,
                        header: q.header,
                        multi: q.multi,
                        answer: q.answer.map(|a| ChatQuestionAnswer {
                            choices: a.choices,
                            text: a.text,
                            notes: a.notes,
                        }),
                        options: (q.options.into_iter())
                            .map(|o| ChatQuestionOption {
                                label: o.label,
                                description: o.description,
                            })
                            .collect(),
                    })
                    .collect(),
                status: tool_status(status),
                output,
            },
            ChatBody::Tool {
                name,
                title,
                input,
                status,
                output,
                diff,
                images,
                question: None,
                details,
            } => ChatEntryBody::Tool {
                details: details.map(Into::into),
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
                status: tool_status(status),
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

fn tool_status(status: ToolStatus) -> ChatToolStatus {
    match status {
        ToolStatus::Running | ToolStatus::Unknown => ChatToolStatus::Running,
        ToolStatus::Done => ChatToolStatus::Done,
        ToolStatus::Error => ChatToolStatus::Failed,
    }
}

/// How to run a command on the Host for the Chat.
pub(crate) enum ChatHost {
    Ssh {
        first: std::sync::Mutex<Option<Box<Ssh>>>,
        reopen: Arc<dyn Fn() -> Result<Ssh, CoreError> + Send + Sync>,
    },
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
    async fn ssh(&self) -> Result<Ssh, CoreError> {
        let Self::Ssh { first, reopen } = self else {
            unreachable!()
        };
        if let Some(ssh) = first.lock().expect("first SSH lock").take() {
            return Ok(*ssh);
        }
        let reopen = reopen.clone();
        // The vault may wait for foreground biometric authorization.
        tokio::task::spawn_blocking(move || reopen())
            .await
            .map_err(|e| CoreError::Vault {
                detail: e.to_string(),
            })?
    }

    pub(crate) async fn open(&self, command: &str) -> Result<Stream, CoreError> {
        match self {
            Self::Ssh { .. } => self.ssh().await?.exec_stream(command).await,
            Self::Local(env) => open_local(env, command),
        }
    }

    /// [`ChatHost::open`] with at most `sign_in` for reaching and signing
    /// in to an SSH Host (all hops together); see [`Ssh::exec_stream_within`].
    pub(crate) async fn open_within(
        &self,
        command: &str,
        sign_in: std::time::Duration,
    ) -> Result<Stream, CoreError> {
        match self {
            Self::Ssh { .. } => self.ssh().await?.exec_stream_within(command, sign_in).await,
            Self::Local(env) => open_local(env, command),
        }
    }
}

/// Runs `command` with `sh -c` on this machine (Local Hosts).
fn open_local(env: &HashMap<String, String>, command: &str) -> Result<Stream, CoreError> {
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
        ssh: None,
        reader: Box::new(stdout),
        writer: Box::new(stdin),
        guard: Box::new(std::sync::Mutex::new(child)),
    })
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

/// The Host-side `aries-beacon answer` command; without a Beacon it prints
/// a `no_beacon` reply.
pub(crate) fn answer_command(terminal: &str, answer: &Answer) -> String {
    let no_beacon = AnswerReply::new(AnswerOutcome::NoBeacon, None).to_json();
    let args: Vec<String> = answer.args().iter().map(|a| quote(a)).collect();
    let script = format!(
        r#"b="${{ARIES_BEACON:-$HOME/.local/bin/aries-beacon}}"; if [ -x "$b" ]; then exec "$b" answer {} {}; else printf '%s\n' {}; fi"#,
        quote(terminal),
        args.join(" "),
        quote(&no_beacon),
    );
    format!("sh -c {}", quote(&script))
}

/// Runs `aries-beacon answer` on the Host once. Not retried: a key press
/// that may have landed must not land twice.
pub(crate) async fn answer(
    host: &ChatHost,
    terminal: &str,
    answer: &Answer,
) -> Result<(), CoreError> {
    let stream = host.open(&answer_command(terminal, answer)).await?;
    answer_result(stream).await
}

pub(crate) async fn answer_within(
    host: &ChatHost,
    terminal: &str,
    answer: &Answer,
    sign_in: Duration,
) -> Result<(), CoreError> {
    let stream = host
        .open_within(&answer_command(terminal, answer), sign_in)
        .await?;
    answer_result(stream).await
}

async fn answer_result(stream: Stream) -> Result<(), CoreError> {
    let Stream {
        reader,
        writer,
        guard,
        ..
    } = stream;
    let mut lines = BufReader::new(reader).lines();
    let mut reply = None;
    let read = tokio::time::timeout(Duration::from_secs(30), async {
        while let Ok(Some(text)) = lines.next_line().await {
            if let Ok(r) = AnswerReply::parse(&text) {
                reply = Some(r);
            }
        }
    })
    .await;
    drop((writer, guard));
    let refused = |reason, detail: Option<String>| CoreError::AnswerRefused {
        reason,
        detail: detail.unwrap_or_default(),
    };
    let Some(reply) = reply else {
        let why = if read.is_err() {
            "timed out"
        } else {
            "no reply"
        };
        return Err(refused(AnswerProblem::Failed, Some(why.into())));
    };
    let reason = match reply.outcome {
        AnswerOutcome::Sent => return Ok(()),
        AnswerOutcome::Answered => AnswerProblem::Answered,
        AnswerOutcome::Gone | AnswerOutcome::NoAgent | AnswerOutcome::NoTerminal => {
            AnswerProblem::Gone
        }
        AnswerOutcome::Invalid => AnswerProblem::Invalid,
        AnswerOutcome::Unsupported => AnswerProblem::Unsupported,
        AnswerOutcome::NoBeacon => AnswerProblem::NoBeacon,
        AnswerOutcome::NoCore => AnswerProblem::NoCore,
        AnswerOutcome::Failed | AnswerOutcome::Unknown => AnswerProblem::Failed,
    };
    Err(refused(reason, reply.detail))
}

/// Silence after which the stream counts as dead (the Beacon sends a
/// heartbeat every 15 s).
const SILENCE: Duration = Duration::from_secs(45);

/// Snapshots and typed connection problems share one ordered update stream.
type ChatUpdate = Result<Option<ChatSnapshot>, CoreError>;

/// A running Chat for one Terminal.
#[derive(uniffi::Object)]
pub struct ChatWatch {
    rx: Arc<Mutex<watch::Receiver<ChatUpdate>>>,
    task: JoinHandle<()>,
    wake: Arc<Wake>,
    images: Images,
}

/// The app's reconnect signals (see [`ChatWatch::reconnect`]).
struct Wake {
    active: watch::Sender<bool>,
    /// Drop the current line and connect again now.
    replace: Notify,
    /// Skip the wait before the next attempt.
    retry: Notify,
}

impl Default for Wake {
    fn default() -> Self {
        Self {
            active: watch::channel(true).0,
            replace: Notify::new(),
            retry: Notify::new(),
        }
    }
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
        let (tx, rx) = watch::channel(Ok(Some(model.snapshot())));
        let images = model.images.clone();
        let wake = Arc::new(Wake::default());
        let signals = wake.clone();
        let task = crate::runtime().spawn(async move {
            let mut model = model;
            if let Some(slot) = &cache
                && let Some(cached) = slot.load(&terminal).await
                // An explicit cursor wins unless it is the cached one.
                && (model.cursor.is_none() || model.cursor == cached.cursor)
            {
                model.seed(cached);
                if tx.send(Ok(Some(model.snapshot()))).is_err() {
                    return;
                }
            }
            follow(host, terminal, model, cache, tx, retry, &signals).await
        });
        Self {
            rx: Arc::new(Mutex::new(rx)),
            task,
            wake,
            images,
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
    tx: watch::Sender<ChatUpdate>,
    retry: Duration,
    wake: &Wake,
) {
    let mut saver = Saver {
        slot: cache.as_ref(),
        dirty: false,
        next: Instant::now(),
    };
    let mut backoff = retry;
    let mut active = wake.active.subscribe();
    loop {
        if active.wait_for(|active| *active).await.is_err() {
            return;
        }
        model.clear_permissions();
        let command = command(&terminal, model.cursor.as_deref());
        let opened = tokio::select! {
            biased;
            _ = active.changed() => continue,
            stream = host.open(&command) => stream,
        };
        if let Err(error) = opened.as_ref()
            && !error.retryable_connection()
        {
            saver.flush(&terminal, &model).await;
            if tx.send(Err(error.clone())).is_err() {
                return;
            }
            // Auth, trust, permission and configuration failures require a
            // user action. Preserve the typed cause through next(), then wait.
            tokio::select! {
                _ = wake.replace.notified() => {},
                _ = wake.retry.notified() => {},
            }
            continue;
        }

        if let Ok(stream) = opened {
            // The writer and guard stay alive while reading; dropping them
            // ends the Host command.
            let Stream {
                reader,
                writer,
                guard,
                ..
            } = stream;
            let mut lines = BufReader::new(reader).lines();
            let mut heard = Instant::now();
            loop {
                let silent = heard + SILENCE;
                let wake_at = saver.due().map_or(silent, |d| d.min(silent));
                let next = tokio::select! {
                    next = tokio::time::timeout_at(wake_at, lines.next_line()) => next,
                    _ = wake.replace.notified() => break,
                    _ = active.changed() => break,
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
                    if tx.send(Ok(Some(model.snapshot()))).is_err() {
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
        if !*active.borrow() {
            continue;
        }
        model.status = ChatStatus::Reconnecting;
        if tx.send(Ok(Some(model.snapshot()))).is_err() {
            return;
        }
        tokio::select! {
            _ = tokio::time::sleep(backoff) => backoff = (backoff * 2).min(Duration::from_secs(30)),
            _ = wake.replace.notified() => backoff = retry,
            _ = wake.retry.notified() => backoff = retry,
            _ = active.changed() => backoff = retry,
        }
    }
}

#[uniffi::export]
impl ChatWatch {
    /// The Chat: right away on the first call, then each time it changes
    /// (intermediate states may be skipped). After a final status
    /// (`NoTerminal`, `NoBeacon`, `Incompatible`) or `stop`, fails with
    /// [`CoreError::Closed`]. A permanent connection failure returns its typed
    /// error once; following pauses until an explicit reconnect/retry signal.
    pub async fn next(&self) -> Result<ChatSnapshot, CoreError> {
        let rx = self.rx.clone();
        crate::on_runtime(async move {
            let mut rx = rx.lock().await;
            rx.changed().await.map_err(|_| CoreError::Closed)?;
            rx.borrow_and_update().clone()?.ok_or(CoreError::Closed)
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
    /// The phone's network changed: the line is likely dead, so drop it
    /// and connect again now, after the last cursor.
    pub fn reconnect(&self) {
        self.wake.replace.notify_one();
    }

    /// The app is back in front: if the Chat waits to reconnect, try now.
    /// A live line is kept (a dead one is noticed by its silence).
    pub fn retry_now(&self) {
        self.wake.retry.notify_one();
    }

    /// Releases the screen's SSH stream and suspends retries, retaining the
    /// in-memory transcript, images and cursor for a later foreground resume.
    pub fn pause(&self) {
        self.wake.active.send_replace(false);
    }

    pub fn resume(&self) {
        self.wake.active.send_replace(true);
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
    fn the_answer_command_quotes_terminal_and_question() {
        let answer = Answer {
            free_text: vec![],
            question: "it's".into(),
            picks: vec![vec![1], vec![0, 2]],
        };
        let c = answer_command("t'1", &answer);
        let out = std::process::Command::new("/bin/sh")
            .arg("-c")
            .arg(format!("export ARIES_BEACON=/bin/echo; {c}"))
            .output()
            .unwrap();
        assert_eq!(
            String::from_utf8(out.stdout).unwrap(),
            "answer t'1 it's 1 0,2\n"
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
