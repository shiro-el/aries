//! `aries-beacon chat <terminal> [--follow] [--cursor C]`: finds the Agent
//! in a Terminal, reads its transcript and prints Agent-neutral
//! [`ChatLine`]s (format: `aries_protocol::chat`).
//!
//! Adding an Agent means one [`Agent`] (pair a Terminal with a transcript)
//! and one [`Interpreter`] (transcript line -> events) for JSONL transcripts;
//! an Agent with another kind of transcript (OpenCode's SQLite database)
//! overrides [`Agent::open`] with its own [`Source`]. The follow loop,
//! cursor, rotation and the app's rendering are shared.

pub mod adapter;
pub mod answer;
mod follow;
pub mod media;

use adapter::source;
pub use adapter::{Agent, Batch, Interpreter, JsonlSource, Located, Opened, Poll, Source, agents};
pub use adapter::{claude, codex, opencode, permission, pi};
pub use answer::{KeysError, TerminalInput};
use aries_protocol::agent_status::StatusRecord;
pub use follow::{Options, TerminalLookup, run};

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
