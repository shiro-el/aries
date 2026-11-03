//! `aries-beacon chat <terminal> [--follow] [--cursor C]`: finds the Agent
//! in a Terminal, reads its transcript and prints Agent-neutral
//! [`ChatLine`]s (format: `aries_protocol::chat`).
//!
//! Adding an Agent means one [`Agent`] (pair a Terminal with a transcript)
//! and one [`Interpreter`] (transcript line -> events) for JSONL transcripts;
//! an Agent with another kind of transcript (OpenCode's SQLite database)
//! overrides [`Agent::open`] with its own [`Source`]. The follow loop,
//! cursor, rotation and the app's rendering are shared.

pub mod answer;
pub mod claude;
pub mod codex;
mod details;
mod follow;
mod jsonl;
pub mod media;
pub mod opencode;
pub mod pi;
mod source;

use std::path::PathBuf;

use aries_protocol::agent_status::StatusRecord;
use aries_protocol::chat::{ChatEvent, ChatQuestion};

pub use answer::{KeysError, TerminalInput};

pub use follow::{Options, TerminalLookup, run};
pub use jsonl::{JsonlSource, Poll};
pub use source::{Batch, Opened, Source};

/// One Agent kind the Beacon can read.
pub trait Agent {
    /// Agent name in [`ChatEvent::Reset`] and cursors (`claude-code`).
    fn name(&self) -> &'static str;
    /// The transcript the Agent running in `terminal` writes right now.
    /// `None`: this Agent does not run there.
    fn locate(&self, terminal: &TerminalFacts) -> Option<Located>;
    /// A fresh reader for one JSONL transcript.
    fn interpreter(&self) -> Box<dyn Interpreter>;
    /// Opens `located` for reading. `resume`: the position part of an
    /// earlier cursor for this transcript. Default: a [`JsonlSource`] over
    /// `located.path` with [`Agent::interpreter`].
    fn open(&self, located: &Located, resume: Option<&str>) -> Opened {
        let fresh = JsonlSource::new(located.path.clone(), self.interpreter(), 0);
        let after = resume.and_then(|p| fresh.holds(p));
        match after {
            Some(after) => Opened {
                source: Box::new(JsonlSource::new(
                    located.path.clone(),
                    self.interpreter(),
                    after,
                )),
                resumed: true,
            },
            _ => Opened {
                source: Box::new(fresh),
                resumed: false,
            },
        }
    }
    /// Maps mixed picks/text to the Agent's own menu, without terminal.send.
    fn answer_input(
        &self,
        question: &ChatQuestion,
        picks: &[Vec<usize>],
        text: &[Option<String>],
    ) -> Result<Vec<answer::AnswerInput>, KeysError> {
        if text.iter().any(Option::is_some) {
            return Err(KeysError::Unsupported(format!(
                "{} free text is unsupported",
                self.name()
            )));
        }
        self.answer_keys(question, picks)
            .map(|keys| vec![answer::AnswerInput::Keys(keys)])
    }
    /// The TUI keys (herdr-core `terminal.keys` names) that answer the open
    /// `question` with `picks` (already checked by [`answer::validate`]).
    fn answer_keys(
        &self,
        _question: &ChatQuestion,
        _picks: &[Vec<usize>],
    ) -> Result<Vec<String>, KeysError> {
        Err(KeysError::Unsupported(format!(
            "{} questions cannot be answered from Chat",
            self.name()
        )))
    }
}

/// Turns transcript lines into Chat events. Must ignore lines it does not
/// understand: transcript formats are private and change.
pub trait Interpreter {
    fn line(&mut self, line: &str) -> Vec<ChatEvent>;
}

/// Where an Agent's current transcript is. The file may not exist yet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Located {
    pub session: String,
    pub path: PathBuf,
}

/// What herdr-core says about a Terminal (`terminal.get`).
#[derive(Debug, Clone, Default)]
pub struct TerminalFacts {
    /// Current Host turn, absent on older cores.
    pub turn: Option<u64>,
    pub pid: Option<u32>,
    pub foreground_pid: Option<u32>,
    /// The foreground process's argv, as herdr-core sees it (also where
    /// `/proc` is missing).
    pub foreground_argv: Vec<String>,
    pub labels: serde_json::Map<String, serde_json::Value>,
    /// The OSC 7501 status (`None` if the program never reported one).
    pub status: Option<StatusRecord>,
}

impl TerminalFacts {
    pub fn from_json(v: &serde_json::Value) -> Self {
        let pid = |v: &serde_json::Value| v.as_u64().and_then(|p| u32::try_from(p).ok());
        Self {
            turn: v["turn"].as_u64(),
            pid: pid(&v["pid"]),
            foreground_pid: pid(&v["foreground"]["pid"]),
            foreground_argv: (v["foreground"]["argv"].as_array().into_iter().flatten())
                .filter_map(|a| a.as_str().map(str::to_owned))
                .collect(),
            labels: v["labels"].as_object().cloned().unwrap_or_default(),
            status: serde_json::from_value(v["status"].clone()).ok(),
        }
    }

    /// The foreground process, the Terminal's process and all their
    /// descendants, nearest first.
    pub fn pids(&self) -> Vec<u32> {
        let mut out: Vec<u32> = Vec::new();
        let mut queue: std::collections::VecDeque<u32> =
            self.foreground_pid.into_iter().chain(self.pid).collect();
        while let Some(pid) = queue.pop_front() {
            if out.contains(&pid) || out.len() > 4096 {
                continue;
            }
            out.push(pid);
            queue.extend(children(pid));
        }
        out
    }
}

fn children(pid: u32) -> Vec<u32> {
    let Ok(tasks) = std::fs::read_dir(format!("/proc/{pid}/task")) else {
        return Vec::new();
    };
    tasks
        .flatten()
        .filter_map(|t| std::fs::read_to_string(t.path().join("children")).ok())
        .flat_map(|s| {
            s.split_whitespace()
                .filter_map(|p| p.parse().ok())
                .collect::<Vec<u32>>()
        })
        .collect()
}

/// Every Agent this Beacon reads, in the order they are tried.
pub fn agents() -> Vec<Box<dyn Agent>> {
    vec![
        Box::new(claude::ClaudeCode::from_env()),
        Box::new(codex::Codex),
        Box::new(opencode::OpenCode::from_env()),
        Box::new(pi::Pi),
    ]
}
