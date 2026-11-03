//! `aries-beacon chat <terminal> [--follow] [--cursor C]`: finds the Agent
//! in a Terminal, reads its transcript and prints Agent-neutral
//! [`ChatLine`]s (format: `aries_protocol::chat`).
//!
//! Adding an Agent means one [`Agent`] (pair a Terminal with a transcript)
//! and one [`Interpreter`] (transcript line -> events) for JSONL transcripts.
//! The follow loop, cursor, partial lines, rotation and the app's rendering
//! are shared.

pub mod claude;
mod follow;
mod jsonl;
pub mod media;

use std::path::PathBuf;

use aries_protocol::chat::ChatEvent;

pub use follow::{Options, TerminalLookup, run};
pub use jsonl::{JsonlSource, Poll};

/// One Agent kind the Beacon can read.
pub trait Agent {
    /// Agent name in [`ChatEvent::Reset`] and cursors (`claude-code`).
    fn name(&self) -> &'static str;
    /// The transcript the Agent running in `terminal` writes right now.
    /// `None`: this Agent does not run there.
    fn locate(&self, terminal: &TerminalFacts) -> Option<Located>;
    /// A fresh reader for one transcript.
    fn interpreter(&self) -> Box<dyn Interpreter>;
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
    pub pid: Option<u32>,
    pub foreground_pid: Option<u32>,
    pub labels: serde_json::Map<String, serde_json::Value>,
}

impl TerminalFacts {
    pub fn from_json(v: &serde_json::Value) -> Self {
        let pid = |v: &serde_json::Value| v.as_u64().and_then(|p| u32::try_from(p).ok());
        Self {
            pid: pid(&v["pid"]),
            foreground_pid: pid(&v["foreground"]["pid"]),
            labels: v["labels"].as_object().cloned().unwrap_or_default(),
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
    vec![Box::new(claude::ClaudeCode::from_env())]
}
