//! The Chat event stream: what `aries-beacon chat <terminal> --follow`
//! prints, one [`ChatLine`] JSON object per line. The Beacon interprets each
//! Agent's own transcript (Claude Code JSONL, Codex rollouts, OpenCode's
//! database, Pi sessions) into these Agent-neutral events, so the app never
//! sees a transcript format.
//!
//! # Model
//!
//! The app holds an ordered list of [`ChatItem`]s.
//! - [`ChatEvent::Reset`] empties it: a new transcript starts (first open
//!   without a cursor, `/clear`, `/new`, resume of another session).
//! - [`ChatEvent::Item`] upserts by [`ChatItem::id`]: an unknown id is
//!   appended, a known one is replaced in place. A tool call whose result
//!   arrives later, or an OpenCode part that grows while streaming, is the
//!   same id sent again. Replaying events is therefore harmless.
//! - [`ChatEvent::Remove`] drops an item (a Pi branch switch).
//! - [`ChatEvent::State`] says what the Beacon is doing (see [`ChatState`]).
//! - [`ChatEvent::Heartbeat`] keeps an idle stream observably alive.
//!
//! # Cursor
//!
//! An event may carry `cursor`, an opaque string. Passing the last one seen
//! to `aries-beacon chat --cursor` resumes after it: no event is lost and
//! none that the cursor already covers is repeated. A cursor for a
//! transcript that is no longer the Terminal's current one makes the Beacon
//! start over with a `reset`.
//!
//! # Versions
//!
//! Every line carries `v` = [`CHAT_FORMAT`]. Within one format version the
//! Beacon may add fields, item kinds, event types and states; readers ignore
//! unknown fields, show unknown item kinds as [`ChatBody::Unsupported`], and
//! skip unknown events and states. Anything incompatible bumps
//! [`CHAT_FORMAT`]; a reader refuses lines with a newer `v`
//! ([`ChatLine::parse`]).

use serde::{Deserialize, Serialize};

/// Version of the Chat event format (`v` on every line).
pub const CHAT_FORMAT: u32 = 1;

/// One line of `aries-beacon chat` output.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChatLine {
    pub v: u32,
    #[serde(flatten)]
    pub event: ChatEvent,
    /// Resume point after this event (see the module docs).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ChatEvent {
    /// Forget every item: what follows is transcript `session` of `agent`
    /// (`claude-code`, `codex`, `opencode`, `pi`).
    Reset { agent: String, session: String },
    Item { item: ChatItem },
    Remove { id: String },
    State { state: ChatState },
    Heartbeat,
    /// An event type this reader does not know; skip it.
    #[serde(other)]
    Unknown,
}

/// What the Beacon is doing for this Chat.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChatState {
    /// Caught up with the transcript and following it.
    Live,
    /// An Agent is running but has not written its transcript yet (Claude
    /// Code creates the file with the first message, also after `/clear`).
    Waiting,
    /// No supported Agent runs in the Terminal (yet); the Beacon keeps
    /// looking while following.
    NoAgent,
    /// The Agent exited; the items are its last transcript.
    Ended,
    /// The Terminal does not exist. The Beacon exits after this.
    NoTerminal,
    /// The Host's herdr-core cannot be reached. The Beacon exits after this.
    NoCore,
    /// The Host has no (usable) Beacon. Written by the app's launcher
    /// script, never by the Beacon itself.
    NoBeacon,
    /// A state this reader does not know.
    #[serde(other)]
    Unknown,
}

/// One entry of the Chat.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChatItem {
    /// Stable within one transcript; the upsert key.
    pub id: String,
    /// RFC 3339 time the Agent recorded, when it did.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ts: Option<String>,
    #[serde(flatten)]
    pub body: ChatBody,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ChatBody {
    /// What the user typed (or a slash command such as `/clear`).
    User { text: String },
    /// The Agent's reply text (Markdown).
    Agent { text: String },
    /// The Agent thought. `text` is `None` when the Agent does not record
    /// the content (Claude Code's adaptive thinking).
    Thinking {
        #[serde(default)]
        text: Option<String>,
    },
    /// A tool call, collapsed by default.
    Tool {
        /// Tool name as the Agent calls it (`Write`, `Bash`, `exec`, ...).
        name: String,
        /// One line for the collapsed row (`hello.txt`, `ls -la`).
        #[serde(default)]
        title: String,
        /// The full input, as the Agent recorded it.
        #[serde(default)]
        input: serde_json::Value,
        status: ToolStatus,
        /// The full result text; `None` while running.
        #[serde(default)]
        output: Option<String>,
    },
    /// An error the Agent reported (API failure, model not found).
    Error { message: String },
    /// An item kind this reader does not know.
    #[serde(other)]
    Unsupported,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ToolStatus {
    Running,
    Done,
    Error,
    #[serde(other)]
    Unknown,
}

/// Why a line cannot be read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChatLineError {
    /// Written by a newer, incompatible format version.
    NewerFormat(u32),
    /// Not a Chat line at all.
    Malformed(String),
}

impl ChatLine {
    pub fn new(event: ChatEvent) -> Self {
        Self {
            v: CHAT_FORMAT,
            event,
            cursor: None,
        }
    }

    pub fn with_cursor(mut self, cursor: impl Into<String>) -> Self {
        self.cursor = Some(cursor.into());
        self
    }

    /// Reads one line, applying the version rule.
    pub fn parse(line: &str) -> Result<Self, ChatLineError> {
        let value: serde_json::Value =
            serde_json::from_str(line).map_err(|e| ChatLineError::Malformed(e.to_string()))?;
        let v = value["v"]
            .as_u64()
            .ok_or_else(|| ChatLineError::Malformed("no format version".into()))?;
        if v > u64::from(CHAT_FORMAT) {
            return Err(ChatLineError::NewerFormat(v as u32));
        }
        serde_json::from_value(value).map_err(|e| ChatLineError::Malformed(e.to_string()))
    }

    /// The JSON line (no trailing newline).
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("chat line json")
    }
}
