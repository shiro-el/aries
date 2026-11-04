//! Calls: an Agent's explicit request for the user's attention.
//!
//! The Beacon keeps every Call in a private, append-only log on the Host
//! (`calls.jsonl` in its state directory), one [`LogEntry`] per line: the Call
//! itself, then any change to its answer. Readers fold the log into
//! [`Call`]s. `aries-beacon calls` prints [`CallsLine`]s: the folded Calls,
//! a [`CallsLine::Synced`] marker, and (with `--follow`) every Call again
//! whenever it is added or its answer changes.

use serde::{Deserialize, Serialize};

/// A Call offers at most this many Choices.
pub const MAX_CHOICES: usize = 3;
/// Longest title, in characters.
pub const MAX_TITLE: usize = 200;
/// Longest body, in characters.
pub const MAX_BODY: usize = 4000;
/// Longest Choice, in characters.
pub const MAX_CHOICE: usize = 200;

/// How urgently the Agent needs the user, least disruptive first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Urgency {
    /// Informational; kept in Call history without a push.
    Record,
    /// The default: a question or a result the user can look at when convenient.
    Check,
    /// Work cannot safely continue without a prompt decision.
    Urgent,
    /// Immediate action is needed to prevent serious harm.
    Emergency,
}

impl Urgency {
    pub const ALL: [Urgency; 4] = [Self::Record, Self::Check, Self::Urgent, Self::Emergency];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Record => "record",
            Self::Check => "check",
            Self::Urgent => "urgent",
            Self::Emergency => "emergency",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|u| u.as_str() == s)
    }
}

/// One Call as kept in the Host's Call history.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Call {
    /// Unique on the Host (`c` + creation time + random hex).
    pub id: String,
    /// When the Beacon recorded it, Unix milliseconds.
    pub at_ms: u64,
    /// Address of the calling Terminal (`t7`) as herdr-core confirmed it;
    /// `None` when the caller could not be confirmed (the Call is then only
    /// recorded, never pushed or answered).
    pub terminal: Option<String>,
    /// Identity of the original recipient, checked before typing. Older
    /// Calls without one remain readable but cannot safely be answered.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recipient: Option<Recipient>,
    /// The calling Terminal has no parent Terminal: a top-level Agent, not
    /// a helper some other Agent started. `false` when unconfirmed.
    pub top_level: bool,
    pub title: String,
    pub body: String,
    pub urgency: Urgency,
    /// At most [`MAX_CHOICES`]; each is also the text typed as the answer.
    pub choices: Vec<String>,
    /// The answer given, if any (see [`AnswerState`]).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub answer: Option<Answer>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Recipient {
    pub terminal_pid: u32,
    pub created_ms: u64,
    pub agent_pid: u32,
    pub process: String,
}

impl Call {
    /// What the default policy does with this Call: push it only when the
    /// caller is a confirmed top-level Agent and the urgency asks for one.
    /// Everything else is only recorded.
    pub fn pushes_by_default(&self) -> bool {
        crate::push_policy::PushPolicy::default().allows_call(self)
    }
}

/// An answer to a Call and how far its delivery got.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Answer {
    /// Index into [`Call::choices`].
    pub choice: u32,
    /// The text typed into the Terminal (the Choice).
    pub text: String,
    pub state: AnswerState,
    /// When the state was recorded, Unix milliseconds.
    pub at_ms: u64,
    /// Why it failed or is unconfirmed, for the user.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

/// Delivery of an [`Answer`]. A claim is durable: only a proven [`AnswerState::Failed`]
/// delivery may be claimed again by an explicit retry.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AnswerState {
    /// Claimed; being typed into the Terminal.
    Claimed,
    /// Typed into the Terminal.
    Delivered,
    /// Not typed (the Terminal is gone, ...); [`Answer::detail`] says why.
    Failed,
    /// The send may or may not have reached the Terminal; never retried.
    Unconfirmed,
}

/// One line of the Host's Call log.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum LogEntry {
    /// A new Call.
    Call { seq: u64, call: Call },
    /// The answer of Call `id` is now `answer`.
    Answer {
        seq: u64,
        id: String,
        answer: Answer,
    },
}

impl LogEntry {
    pub fn seq(&self) -> u64 {
        match self {
            Self::Call { seq, .. } | Self::Answer { seq, .. } => *seq,
        }
    }
}

/// One line printed by `aries-beacon calls`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
// Each line carries one complete Call; boxing adds an allocation to every
// history update without reducing the payload kept by either consumer.
#[allow(clippy::large_enum_variant)]
pub enum CallsLine {
    /// A Call as it is now; `seq` is the log entry of its last change.
    /// Replaces any earlier line with the same `call.id`.
    Call { seq: u64, call: Call },
    /// Every Call in the history has been printed, up to log entry `seq`.
    /// With `--follow`, later lines are changes after it.
    Synced { seq: u64 },
    /// Sent every [`HEARTBEAT_SECS`] while following, so both ends notice a
    /// dead connection.
    Heartbeat,
}

/// Seconds between [`CallsLine::Heartbeat`]s.
pub const HEARTBEAT_SECS: u64 = 15;

/// Folds log entries (in order) into Calls, oldest first, each with the seq
/// of its last change. Answers to unknown Calls are ignored.
pub fn fold(entries: impl IntoIterator<Item = LogEntry>) -> Vec<(u64, Call)> {
    let mut calls: Vec<(u64, Call)> = Vec::new();
    for entry in entries {
        match entry {
            LogEntry::Call { seq, call } => {
                calls.retain(|(_, c)| c.id != call.id);
                calls.push((seq, call));
            }
            LogEntry::Answer { seq, id, answer } => {
                if let Some(slot) = calls.iter_mut().find(|(_, c)| c.id == id) {
                    slot.0 = seq;
                    slot.1.answer = Some(answer);
                }
            }
        }
    }
    calls
}

// ------------------------------------------------------------- `reply`

/// Version of the [`ReplyLine`] format.
pub const REPLY_FORMAT: u32 = 1;

/// What `aries-beacon reply CALL CHOICE` did. Only [`ReplyOutcome::Delivered`]
/// exits 0.
///
/// The Beacon claims the Call in its log (an [`AnswerState::Claimed`]
/// answer) before it types anything. A retry or racing reply gets
/// [`ReplyOutcome::Answered`] unless the previous attempt certainly failed
/// before delivery. Uncertain delivery keeps the claim, so losing the
/// connection mid-reply cannot cause duplicate input.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReplyOutcome {
    /// Typed into the calling Terminal, then Enter.
    Delivered,
    /// Claimed, but certainly not typed (the Terminal is gone, herdr-core
    /// refused it, ...); recorded as [`AnswerState::Failed`].
    Failed,
    /// Claimed and handed to herdr-core, but its answer never came: the
    /// text may or may not be in the Terminal. Recorded as
    /// [`AnswerState::Unconfirmed`] and never retried.
    Unconfirmed,
    /// The Call already has an answer (in [`ReplyLine::answer`]); nothing
    /// was typed.
    Answered,
    /// No Call with this id in the Host's history.
    NoCall,
    /// The Call has no Choice with this index.
    NoChoice,
    /// The Call's caller was never confirmed, so there is no Terminal to
    /// type into.
    NotAnswerable,
    /// The Host has no (usable) Beacon. Written by the app's launcher
    /// script, never by the Beacon itself.
    NoBeacon,
    /// An outcome this reader does not know: treat as a failure.
    #[serde(other)]
    Unknown,
}

/// The one line `aries-beacon reply` prints.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReplyLine {
    pub v: u32,
    pub outcome: ReplyOutcome,
    /// The Call's answer as recorded after this reply (the earlier one for
    /// [`ReplyOutcome::Answered`]).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub answer: Option<Answer>,
    /// For logs and error details; not for matching.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

impl ReplyLine {
    pub fn new(outcome: ReplyOutcome, answer: Option<Answer>, detail: Option<String>) -> Self {
        Self {
            v: REPLY_FORMAT,
            outcome,
            answer,
            detail,
        }
    }

    /// Reads a line; `Err` for other lines and newer versions.
    pub fn parse(line: &str) -> Result<Self, String> {
        let reply: Self = serde_json::from_str(line).map_err(|e| e.to_string())?;
        if reply.v > REPLY_FORMAT {
            return Err(format!("reply format {} is newer", reply.v));
        }
        Ok(reply)
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("reply line json")
    }
}

/// Why a Call was refused before it was recorded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Invalid {
    EmptyTitle,
    TitleTooLong,
    BodyTooLong,
    TooManyChoices,
    EmptyChoice,
    ChoiceTooLong,
    DuplicateChoice,
    /// A Choice is typed as one line, so it cannot hold control characters.
    ControlInChoice,
}

impl std::fmt::Display for Invalid {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::EmptyTitle => "the title is empty",
            Self::TitleTooLong => "the title is longer than 200 characters",
            Self::BodyTooLong => "the body is longer than 4000 characters",
            Self::TooManyChoices => "a Call offers at most 3 Choices",
            Self::EmptyChoice => "a Choice is empty",
            Self::ChoiceTooLong => "a Choice is longer than 200 characters",
            Self::DuplicateChoice => "two Choices are the same",
            Self::ControlInChoice => "a Choice holds a newline or another control character",
        })
    }
}

/// Checks what an Agent asked for against the limits above.
pub fn validate(title: &str, body: &str, choices: &[String]) -> Result<(), Invalid> {
    let chars = |s: &str| s.chars().count();
    if title.trim().is_empty() {
        return Err(Invalid::EmptyTitle);
    }
    if chars(title) > MAX_TITLE {
        return Err(Invalid::TitleTooLong);
    }
    if chars(body) > MAX_BODY {
        return Err(Invalid::BodyTooLong);
    }
    if choices.len() > MAX_CHOICES {
        return Err(Invalid::TooManyChoices);
    }
    for (i, c) in choices.iter().enumerate() {
        if c.trim().is_empty() {
            return Err(Invalid::EmptyChoice);
        }
        if chars(c) > MAX_CHOICE {
            return Err(Invalid::ChoiceTooLong);
        }
        if c.chars().any(char::is_control) {
            return Err(Invalid::ControlInChoice);
        }
        if choices[..i].contains(c) {
            return Err(Invalid::DuplicateChoice);
        }
    }
    Ok(())
}
