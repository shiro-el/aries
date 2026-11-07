pub mod claude;
pub mod codex;
mod details;
mod jsonl;
pub mod opencode;
pub mod permission;
pub mod pi;
mod question;
pub(super) mod source;
mod usage;

use super::{KeysError, TerminalFacts, answer, media};
use aries_protocol::chat::{ChatEvent, ChatQuestion};
pub use jsonl::{JsonlSource, Poll};
pub use source::{Batch, Opened, Source};
use std::path::PathBuf;

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
    /// Version the projected Chat when the same bytes now mean different
    /// items. A new tag forces older caches to replay instead of resuming.
    /// Must be constant for this interpreter and contain no `:`.
    fn cursor_tag(&self) -> &'static str {
        ""
    }
}

/// Where an Agent's current transcript is. The file may not exist yet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Located {
    pub session: String,
    pub path: PathBuf,
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
