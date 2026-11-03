//! `aries-beacon answer <terminal> <question> <picks>...`: answers the
//! question an Agent shows, from the Chat (contract: `aries_protocol::answer`).
//!
//! Agent-neutral: the open question is the one the Agent's [`Interpreter`]
//! reports as a running `tool` with a `question`, and only while herdr-core
//! says the Terminal is `blocked` on a `question`. Each [`Agent`] only maps
//! picks to its TUI keys ([`Agent::answer_keys`]).

use std::collections::HashMap;

use aries_protocol::answer::{Answer, AnswerOutcome, AnswerReply};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ChatQuestion, ToolStatus};

use super::source::Batch;
use super::{Agent, TerminalLookup};

/// Presses named keys (`2`, `tab`, `enter`, `down`) in a Terminal
/// (herdr-core `terminal.keys`).
pub trait TerminalInput {
    fn keys(&mut self, terminal: &str, keys: &[String]) -> Result<(), String>;
}

/// Why an Agent cannot map picks to keys.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum KeysError {
    /// This Agent (or this shape of question) cannot be answered from Chat.
    Unsupported(String),
    /// The picks do not fit the question.
    Invalid(String),
}

/// Checks picks against the question, the same for every Agent: one picks
/// list per question, indexes in range and distinct, exactly one for a
/// single-choice question, at least one otherwise.
pub fn validate(question: &ChatQuestion, picks: &[Vec<usize>]) -> Result<(), KeysError> {
    if picks.len() != question.questions.len() {
        return Err(KeysError::Invalid(format!(
            "{} questions, {} answers",
            question.questions.len(),
            picks.len()
        )));
    }
    for (i, (q, p)) in question.questions.iter().zip(picks).enumerate() {
        if p.is_empty() || (!q.multi && p.len() != 1) {
            return Err(KeysError::Invalid(format!(
                "question {i} takes {}",
                if q.multi {
                    "one or more picks"
                } else {
                    "one pick"
                }
            )));
        }
        if let Some(n) = p.iter().find(|n| **n >= q.options.len()) {
            return Err(KeysError::Invalid(format!(
                "question {i} has no option {n}"
            )));
        }
        let mut sorted = p.clone();
        sorted.sort_unstable();
        sorted.dedup();
        if sorted.len() != p.len() {
            return Err(KeysError::Invalid(format!("question {i}: repeated pick")));
        }
    }
    Ok(())
}

fn reply(outcome: AnswerOutcome, detail: impl Into<String>) -> AnswerReply {
    AnswerReply::new(outcome, Some(detail.into()))
}

/// Runs one answer. Nothing is pressed unless the outcome is `Sent`.
pub fn answer<L: TerminalLookup + TerminalInput>(
    terminal: &str,
    answer: &Answer,
    link: &mut L,
    agents: &[Box<dyn Agent>],
) -> std::io::Result<AnswerReply> {
    let facts = match link.get(terminal) {
        Ok(Some(f)) => f,
        Ok(None) => return Ok(reply(AnswerOutcome::NoTerminal, terminal)),
        Err(e) => return Ok(reply(AnswerOutcome::NoCore, e)),
    };
    let Some((agent, located)) = agents.iter().find_map(|a| a.locate(&facts).map(|l| (a, l)))
    else {
        return Ok(reply(AnswerOutcome::NoAgent, terminal));
    };

    // The whole transcript as the Chat shows it.
    let mut source = agent.open(&located, None).source;
    let mut items: Vec<ChatItem> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    loop {
        match source.poll()? {
            Batch::Missing | Batch::Replaced => {
                return Ok(reply(AnswerOutcome::Gone, "no transcript"));
            }
            Batch::Events { events, more } => {
                for (event, _) in events {
                    match event {
                        ChatEvent::Item { item } => match index.get(&item.id) {
                            Some(&i) => items[i] = item,
                            None => {
                                index.insert(item.id.clone(), items.len());
                                items.push(item);
                            }
                        },
                        ChatEvent::Remove { id } => {
                            if index.remove(&id).is_some() {
                                items.retain(|i| i.id != id);
                                index = (items.iter().enumerate())
                                    .map(|(n, i)| (i.id.clone(), n))
                                    .collect();
                            }
                        }
                        ChatEvent::Reset { .. } => {
                            items.clear();
                            index.clear();
                        }
                        _ => {}
                    }
                }
                if !more {
                    break;
                }
            }
        }
    }

    let open = |item: &ChatItem| match &item.body {
        ChatBody::Tool {
            status: ToolStatus::Running,
            question: Some(q),
            ..
        } => Some(q.clone()),
        _ => None,
    };
    let Some(item) = index.get(&answer.question).map(|&i| &items[i]) else {
        return Ok(reply(AnswerOutcome::Gone, "no such question"));
    };
    let ChatBody::Tool {
        question: Some(_), ..
    } = &item.body
    else {
        return Ok(reply(AnswerOutcome::Gone, "not a question"));
    };
    let Some(question) = open(item) else {
        return Ok(reply(AnswerOutcome::Answered, "the question has a result"));
    };
    // The Agent shows its newest open question; an older one still
    // "running" was interrupted.
    let newest = items.iter().rev().find(|i| open(i).is_some());
    if newest.is_some_and(|i| i.id != answer.question) {
        return Ok(reply(AnswerOutcome::Gone, "a newer question is open"));
    }
    let asking = facts
        .status
        .as_ref()
        .is_some_and(|s| s.state == "blocked" && s.kind.as_deref() == Some("question"));
    if !asking {
        return Ok(reply(AnswerOutcome::Gone, "the Agent is not asking"));
    }

    let keys = match validate(&question, &answer.picks)
        .and_then(|()| agent.answer_keys(&question, &answer.picks))
    {
        Ok(keys) => keys,
        Err(KeysError::Invalid(d)) => return Ok(reply(AnswerOutcome::Invalid, d)),
        Err(KeysError::Unsupported(d)) => return Ok(reply(AnswerOutcome::Unsupported, d)),
    };
    Ok(match link.keys(terminal, &keys) {
        Ok(()) => AnswerReply::new(AnswerOutcome::Sent, None),
        Err(e) => reply(AnswerOutcome::Failed, e),
    })
}
