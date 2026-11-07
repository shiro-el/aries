use super::{
    TerminalLookup,
    adapter::{Adapters, AnswerResult, Submission},
};
use aries_protocol::answer::{Answer, AnswerOutcome, AnswerReply};

/// Presses named keys (`2`, `tab`, `enter`, `down`) in a Terminal
/// (herdr-core `terminal.keys`).
pub trait TerminalInput {
    fn keys(&mut self, terminal: &str, keys: &[String]) -> Result<(), String>;
    fn text(&mut self, _terminal: &str, _text: &str) -> Result<(), String> {
        Err("literal text input is unavailable".into())
    }
}

/// Why an Agent cannot map picks to keys.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum KeysError {
    /// This Agent (or this shape of question) cannot be answered from Chat.
    Unsupported(String),
    /// The picks do not fit the question.
    Invalid(String),
}

pub fn answer<L: TerminalLookup + TerminalInput>(
    terminal: &str,
    answer: &Answer,
    link: &mut L,
    adapters: &Adapters,
) -> std::io::Result<AnswerReply> {
    let submission = Submission::from_parts(&answer.question, &answer.picks, &answer.free_text);
    let (outcome, detail) = match adapters.answer(terminal, submission, link)? {
        AnswerResult::Sent => (AnswerOutcome::Sent, None),
        AnswerResult::Answered(d) => (AnswerOutcome::Answered, d),
        AnswerResult::Gone(d) => (AnswerOutcome::Gone, d),
        AnswerResult::Invalid(d) => (AnswerOutcome::Invalid, d),
        AnswerResult::Unsupported(d) => (AnswerOutcome::Unsupported, d),
        AnswerResult::NoAgent(d) => (AnswerOutcome::NoAgent, d),
        AnswerResult::NoTerminal(d) => (AnswerOutcome::NoTerminal, d),
        AnswerResult::NoCore(d) => (AnswerOutcome::NoCore, d),
        AnswerResult::Failed(d) => (AnswerOutcome::Failed, d),
        AnswerResult::NoBeacon(d) => (AnswerOutcome::NoBeacon, d),
        AnswerResult::Unknown(d) => (AnswerOutcome::Unknown, d),
    };
    Ok(AnswerReply::new(outcome, detail))
}
