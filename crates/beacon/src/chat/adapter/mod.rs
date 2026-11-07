mod claude;
mod codex;
mod details;
mod jsonl;
mod opencode;
mod permission;
mod pi;
mod question;
mod source;
mod usage;

use super::{KeysError, TerminalFacts, TerminalInput, TerminalLookup, media};
use aries_protocol::chat::{ChatEvent, ChatQuestion};
use delivery as answer;
use jsonl::{JsonlSource, Poll};
use source::{Batch, Opened, Source};
use std::path::PathBuf;

/// One Agent kind the Beacon can read.
trait Agent {
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
    fn open_chat(
        &self,
        located: &Located,
        resume: Option<&str>,
        state: &std::path::Path,
        terminal: &str,
    ) -> Opened {
        let _ = (state, terminal);
        self.open(located, resume)
    }
    fn hook(
        &self,
        _state: &Path,
        _terminal: &str,
        _located: &Located,
    ) -> Option<claude::hook::Pending> {
        None
    }
    fn approval(
        &self,
        _session: &str,
        _facts: &TerminalFacts,
        _screen: &str,
    ) -> Option<permission::Permission> {
        None
    }
    fn approval_decision(
        &self,
        _permission: &permission::Permission,
        _pick: usize,
    ) -> Option<&'static str> {
        None
    }
    fn current_question<'a>(
        &self,
        items: &'a [aries_protocol::chat::ChatItem],
    ) -> Option<(&'a str, &'a ChatQuestion)> {
        requests::open_question(items)
    }
    fn prepare(
        &self,
        _nav: &mut delivery::Navigation<'_>,
        _question: &ChatQuestion,
        _has_text: bool,
    ) -> Result<(), aries_protocol::answer::AnswerReply> {
        Ok(())
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
trait Interpreter {
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
struct Located {
    pub session: String,
    pub path: PathBuf,
}

/// Every Agent this Beacon reads, in the order they are tried.
fn agents() -> Vec<Box<dyn Agent>> {
    vec![
        Box::new(claude::ClaudeCode::from_env()),
        Box::new(codex::Codex),
        Box::new(opencode::OpenCode::from_env()),
        Box::new(pi::Pi),
    ]
}

mod delivery;
mod feed;
mod requests;
pub use feed::{ChatFrame, Feed};
pub use requests::{Observation, PendingRequest, QuickReply, RequestId};
use std::path::Path;

pub struct Adapters {
    state: PathBuf,
    agents: Vec<Box<dyn Agent>>,
}
impl Adapters {
    pub fn installed(state: &Path) -> Self {
        Self {
            state: state.to_owned(),
            agents: agents(),
        }
    }
    pub fn chat(&self, terminal: &str, cursor: Option<&str>) -> Feed<'_> {
        Feed::new(&self.state, &self.agents, terminal, cursor)
    }
    pub fn answer<L: TerminalLookup + TerminalInput>(
        &self,
        terminal: &str,
        submission: Submission,
        link: &mut L,
    ) -> std::io::Result<AnswerResult> {
        let answer = aries_protocol::answer::Answer {
            question: submission.id,
            picks: submission.picks,
            free_text: submission.text,
        };
        let reply = delivery::answer(&self.state, terminal, &answer, link, &self.agents)?;
        use aries_protocol::answer::AnswerOutcome;
        Ok(match reply.outcome {
            AnswerOutcome::Sent => AnswerResult::Sent,
            AnswerOutcome::Answered => AnswerResult::Answered(reply.detail),
            AnswerOutcome::Gone => AnswerResult::Gone(reply.detail),
            AnswerOutcome::Invalid => AnswerResult::Invalid(reply.detail),
            AnswerOutcome::Unsupported => AnswerResult::Unsupported(reply.detail),
            AnswerOutcome::NoAgent => AnswerResult::NoAgent(reply.detail),
            AnswerOutcome::NoTerminal => AnswerResult::NoTerminal(reply.detail),
            AnswerOutcome::NoCore => AnswerResult::NoCore(reply.detail),
            AnswerOutcome::Failed => AnswerResult::Failed(reply.detail),
            AnswerOutcome::NoBeacon => AnswerResult::NoBeacon(reply.detail),
            AnswerOutcome::Unknown => AnswerResult::Unknown(reply.detail),
        })
    }
}
pub struct Submission {
    id: String,
    picks: Vec<Vec<usize>>,
    text: Vec<Option<String>>,
}
impl Submission {
    pub fn from_parts(id: &str, picks: &[Vec<usize>], text: &[Option<String>]) -> Self {
        Self {
            id: id.to_owned(),
            picks: picks.to_vec(),
            text: text.to_vec(),
        }
    }
}
pub enum AnswerResult {
    Sent,
    Answered(Option<String>),
    Gone(Option<String>),
    Invalid(Option<String>),
    Unsupported(Option<String>),
    NoAgent(Option<String>),
    NoTerminal(Option<String>),
    NoCore(Option<String>),
    Failed(Option<String>),
    NoBeacon(Option<String>),
    Unknown(Option<String>),
}
pub fn run_claude_hook(state: &Path) -> std::io::Result<()> {
    claude::hook::run(state)
}

#[cfg(test)]
mod tests;
