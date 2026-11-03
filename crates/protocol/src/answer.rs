//! Answering an Agent's question from the Chat:
//! `aries-beacon answer <terminal> <question> <picks>...`.
//!
//! The question is a Chat `tool` item with a `question`
//! ([`crate::chat::ChatQuestion`]); [`Answer::question`] is that item's id.
//! The Beacon checks that the Agent still shows exactly this question (the
//! Terminal's status is `blocked` with kind `question` and the transcript
//! has no result for it), turns the picks into the Agent's own TUI keys and
//! presses them with herdr-core `terminal.keys`. It prints one
//! [`AnswerReply`] line and exits 0 only for [`AnswerOutcome::Sent`].
//!
//! # Not a Call Choice
//!
//! A Call's Choice (`aries-beacon reply`) is answer *text* the Agent itself
//! offered in its Call; the Beacon looks it up in the Call record by Call ID
//! and types it into the Terminal, once (a durable claim). An [`Answer`]
//! here is a set of option *indexes* into a question the Agent's own TUI is
//! showing; nothing is typed as text, only keys that select options, and it
//! is valid only while that question is on screen. The two never share
//! identifiers, records or commands.
//!
//! # Versions
//!
//! The reply carries `v` = [`ANSWER_FORMAT`]. New outcomes may be added
//! within a version; readers treat unknown ones as failures.

use serde::{Deserialize, Serialize};

/// Version of the answer reply line.
pub const ANSWER_FORMAT: u32 = 1;

/// Picks for one question tool.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Answer {
    /// The Chat item id of the question tool.
    pub question: String,
    /// For each question in order, the 0-based indexes of the picked
    /// options (one for a single-choice question).
    pub picks: Vec<Vec<usize>>,
}

impl Answer {
    /// Command-line form: `<question> <picks of q1> <picks of q2> ...`,
    /// each picks argument comma-separated (`0,2`).
    pub fn args(&self) -> Vec<String> {
        std::iter::once(self.question.clone())
            .chain(
                self.picks
                    .iter()
                    .map(|p| p.iter().map(usize::to_string).collect::<Vec<_>>().join(",")),
            )
            .collect()
    }

    pub fn from_args(args: &[String]) -> Result<Self, String> {
        let (question, picks) = args.split_first().ok_or("missing question id and picks")?;
        if question.is_empty() || question.starts_with('-') {
            return Err(format!("bad question id {question:?}"));
        }
        if picks.is_empty() {
            return Err("missing picks".into());
        }
        let picks = picks
            .iter()
            .map(|arg| {
                arg.split(',')
                    .map(|n| n.parse::<usize>().map_err(|_| format!("bad picks {arg:?}")))
                    .collect::<Result<Vec<_>, _>>()
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(Self {
            question: question.clone(),
            picks,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AnswerOutcome {
    /// The keys were pressed in the Agent's Terminal.
    Sent,
    /// The question already has an answer (from the Terminal or another
    /// phone). Nothing was pressed.
    Answered,
    /// The question is not open any more, or the Agent shows something else
    /// (another question, a permission prompt, no question). Nothing was
    /// pressed.
    Gone,
    /// The picks do not fit the question (wrong count, out of range, several
    /// for a single choice). Nothing was pressed.
    Invalid,
    /// This Agent's questions cannot be answered from the Chat.
    Unsupported,
    /// No supported Agent runs in the Terminal.
    NoAgent,
    NoTerminal,
    /// The Host's herdr-core cannot be reached.
    NoCore,
    /// The Host has no (usable) Beacon. Written by the app's launcher
    /// script, never by the Beacon itself.
    NoBeacon,
    /// herdr-core refused or failed the key presses.
    Failed,
    /// An outcome this reader does not know: a failure.
    #[serde(other)]
    Unknown,
}

impl AnswerOutcome {
    pub fn is_sent(self) -> bool {
        self == Self::Sent
    }
}

/// The one line `aries-beacon answer` prints.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AnswerReply {
    pub v: u32,
    pub outcome: AnswerOutcome,
    /// For logs and error details; not for matching.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

impl AnswerReply {
    pub fn new(outcome: AnswerOutcome, detail: Option<String>) -> Self {
        Self {
            v: ANSWER_FORMAT,
            outcome,
            detail,
        }
    }

    /// Reads a reply; `Err` for non-reply lines and newer versions.
    pub fn parse(line: &str) -> Result<Self, String> {
        let reply: Self = serde_json::from_str(line).map_err(|e| e.to_string())?;
        if reply.v > ANSWER_FORMAT {
            return Err(format!("answer format {} is newer", reply.v));
        }
        Ok(reply)
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("answer reply json")
    }
}
