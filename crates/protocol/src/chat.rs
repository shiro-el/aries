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
//! - [`ChatEvent::Remove`] drops an item (a Pi branch switch or a consumed input queue entry).
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
// An event lives for one line; boxing the item would only add an allocation.
#[allow(clippy::large_enum_variant)]
pub enum ChatEvent {
    /// Forget every item: what follows is transcript `session` of `agent`
    /// (`claude-code`, `codex`, `opencode`, `pi`).
    Reset {
        agent: String,
        session: String,
    },
    Item {
        item: ChatItem,
    },
    Remove {
        id: String,
    },
    State {
        state: ChatState,
    },
    /// Transcript readings for this Agent, separate from response metadata.
    Usage {
        usage: AgentUsage,
    },
    Heartbeat,
    /// An event type this reader does not know; skip it.
    #[serde(other)]
    Unknown,
}

/// Last recorded readings, scoped to one Agent transcript/account. Missing
/// measurements stay absent; refresh times are transcript times, never fetch times.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AgentUsage {
    #[serde(default)]
    pub context: Option<ContextUsage>,
    #[serde(default)]
    pub limits: Vec<UsageLimit>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ContextUsage {
    pub used_tokens: Option<u64>,
    pub window_tokens: Option<u64>,
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UsageLimit {
    pub name: String,
    pub window_minutes: Option<u64>,
    /// Hundredths of a percent: 1250 = 12.5%. None is not 0%.
    pub used_basis_points: Option<u32>,
    /// Unix seconds, as recorded by the Agent.
    pub resets_at: Option<u64>,
    pub updated_at: Option<String>,
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
// Keep the optional, flat records shared with the phone API.
#[allow(clippy::large_enum_variant)]
pub enum ChatBody {
    /// What the user typed (or a slash command such as `/clear`), with the
    /// images they attached.
    User {
        text: String,
        /// Accepted into the Agent's input queue, but not delivered yet.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        queued: bool,
        #[serde(
            default,
            deserialize_with = "lenient",
            skip_serializing_if = "Vec::is_empty"
        )]
        images: Vec<ChatImage>,
    },
    /// The Agent's reply text (Markdown).
    Agent {
        text: String,
        #[serde(
            default,
            deserialize_with = "lenient",
            skip_serializing_if = "Option::is_none"
        )]
        metadata: Option<ResponseMetadata>,
    },
    /// The Agent thought. `text` is `None` when the Agent does not record
    /// the content (Claude Code's adaptive thinking).
    Thinking {
        #[serde(default)]
        text: Option<String>,
    },
    /// A tool call, collapsed by default.
    Tool {
        /// Agent-neutral fields for the expanded tool. Missing data stays absent.
        #[serde(
            default,
            deserialize_with = "lenient",
            skip_serializing_if = "Option::is_none"
        )]
        details: Option<ToolDetails>,
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
        /// What a file edit changed, once the result is in.
        #[serde(
            default,
            deserialize_with = "lenient",
            skip_serializing_if = "Option::is_none"
        )]
        diff: Option<ChatDiff>,
        /// Images in the result (a Read of a picture, a screenshot tool).
        /// `output` still names them as text for older readers.
        #[serde(
            default,
            deserialize_with = "lenient",
            skip_serializing_if = "Vec::is_empty"
        )]
        images: Vec<ChatImage>,
        /// Set when the tool asks the user to choose (Claude Code's
        /// AskUserQuestion, Codex `request_user_input`, ...). While `status`
        /// is `running` it is open: the app shows its options as buttons and
        /// answers with `aries-beacon answer` (`crate::answer`), naming this
        /// item's `id`. Readers that predate it show the plain tool.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        question: Option<ChatQuestion>,
    },
    /// An error the Agent reported (API failure, model not found).
    Error { message: String },
    /// An item kind this reader does not know.
    #[serde(other)]
    Unsupported,
}

/// Recorded response outcome; unknown outcomes stay absent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResponseCompletion {
    Complete,
    Interrupted,
    Error,
}

/// Optional transcript fields; never fill absent measurements with zero.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResponseMetadata {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cache_read_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cache_write_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub completion: Option<ResponseCompletion>,
}

/// Optional transcript fields; never fill absent measurements with zero.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToolDetails {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pattern: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result_count: Option<u64>,
}

/// Reads an optional extra (diff, images) without letting a shape this
/// reader does not understand drop the whole item: it reads as absent.
fn lenient<'de, D, T>(d: D) -> Result<T, D::Error>
where
    D: serde::Deserializer<'de>,
    T: serde::de::DeserializeOwned + Default,
{
    let value = serde_json::Value::deserialize(d)?;
    Ok(serde_json::from_value(value).unwrap_or_default())
}

/// Largest image (decoded bytes) a Chat line carries. A bigger one is sent
/// without `data`, so the reader shows a placeholder with its size.
pub const IMAGE_MAX_BYTES: u64 = 2 * 1024 * 1024;
/// Most diff lines one tool item carries (over all hunks); the rest is cut
/// and `truncated` set.
pub const DIFF_MAX_LINES: usize = 2000;
/// Longest diff line, in chars; longer ones end in `…`.
pub const DIFF_MAX_LINE_CHARS: usize = 1000;

/// An image from the transcript. The Beacon reads it from the transcript
/// itself (Agents embed images as base64) and sends it inline.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChatImage {
    /// `image/png`, `image/jpeg`, ...
    pub media_type: String,
    /// Base64 of the image; `None` when it is over [`IMAGE_MAX_BYTES`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<String>,
    /// Decoded size.
    #[serde(default)]
    pub bytes: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
}

/// A file edit as unified-diff hunks.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChatDiff {
    pub path: String,
    pub hunks: Vec<DiffHunk>,
    /// Lines beyond [`DIFF_MAX_LINES`] were left out.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DiffHunk {
    pub old_start: u32,
    pub old_lines: u32,
    pub new_start: u32,
    pub new_lines: u32,
    /// Each line starts with `-`, `+` or ` `.
    pub lines: Vec<String>,
}

/// What a question tool asks: one or more questions, each answered by
/// picking options. Agent-neutral; the Beacon turns picks into the Agent's
/// own keys.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChatQuestion {
    pub questions: Vec<Question>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Question {
    pub text: String,
    /// A short tag (Claude Code's `header`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub header: Option<String>,
    /// Several options may be picked.
    #[serde(default)]
    pub multi: bool,
    pub options: Vec<QuestionOption>,
    /// Recorded answer, kept with its prompt even after reconnecting on
    /// another device. Absent for pending questions and older Beacons.
    #[serde(
        default,
        deserialize_with = "lenient",
        skip_serializing_if = "Option::is_none"
    )]
    pub answer: Option<QuestionAnswer>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuestionAnswer {
    /// Zero-based indexes of the selected options.
    pub choices: Vec<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuestionOption {
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
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
