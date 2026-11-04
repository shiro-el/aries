//! `aries-beacon answer <terminal> <question> <picks>...`: answers the
//! question an Agent shows, from the Chat (contract: `aries_protocol::answer`).
//!
//! Agent-neutral: the open question is the one the Agent's [`Interpreter`]
//! reports as a running `tool` with a `question`, and only while herdr-core
//! says the Terminal is `blocked` on a `question`. Each [`Agent`] only maps
//! picks to its TUI keys ([`Agent::answer_keys`]). The reserved `permission:`
//! IDs take a separate picks-only path: re-read a recognized live permission
//! menu and its status/turn before sending that menu's advertised shortcut.

use std::collections::HashMap;

use aries_protocol::answer::{Answer, AnswerOutcome, AnswerReply};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ChatQuestion, ToolStatus};

use super::source::Batch;
use super::{Agent, TerminalLookup};

/// Presses named keys (`2`, `tab`, `enter`, `down`) in a Terminal
/// (herdr-core `terminal.keys`).
pub trait TerminalInput {
    fn keys(&mut self, terminal: &str, keys: &[String]) -> Result<(), String>;
    fn text(&mut self, _terminal: &str, _text: &str) -> Result<(), String> {
        Err("literal text input is unavailable".into())
    }
}

/// Input actions stay separate: text is literal UTF-8, never key names or paste.
#[derive(Debug, Clone)]
pub enum AnswerInput {
    Keys(Vec<String>),
    Text(String),
    /// A highlighted menu whose current row is read again for each part.
    Menu {
        prompt: String,
        options: usize,
        pick: Option<usize>,
        text: Option<String>,
    },
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

/// Runs one answer, without retries. Input failures can follow partial input.
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

    if answer.question.starts_with(super::permission::PREFIX) {
        let screen = link.screen(terminal).unwrap_or_default();
        let Some(permission) =
            super::permission::detect(agent.name(), &located.session, &facts, &screen)
                .filter(|p| p.item.id == answer.question)
        else {
            return Ok(reply(AnswerOutcome::Gone, "the permission menu changed"));
        };
        let Some(keys) = permission.keys(&answer.picks, &answer.free_text) else {
            return Ok(reply(AnswerOutcome::Invalid, "invalid permission choice"));
        };
        // Recheck status after the screen read. Never retry a possibly sent key.
        let latest = link.get(terminal).ok().flatten();
        if latest
            .as_ref()
            .and_then(|f| super::permission::detect(agent.name(), &located.session, f, &screen))
            .is_none_or(|p| p.item.id != answer.question)
        {
            return Ok(reply(AnswerOutcome::Gone, "the permission status changed"));
        }
        return Ok(match link.keys(terminal, &keys) {
            Ok(()) => AnswerReply::new(AnswerOutcome::Sent, None),
            Err(e) => reply(AnswerOutcome::Failed, e),
        });
    }

    let Some(items) = transcript(agent.as_ref(), &located)? else {
        return Ok(reply(AnswerOutcome::Gone, "no transcript"));
    };

    let open = |item: &ChatItem| match &item.body {
        ChatBody::Tool {
            status: ToolStatus::Running,
            question: Some(q),
            ..
        } => Some(q.clone()),
        _ => None,
    };
    let Some(item) = items.iter().find(|i| i.id == answer.question) else {
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
    let current = current_question(agent.name(), &items);
    if current.is_none_or(|(id, _)| id != answer.question) {
        return Ok(reply(AnswerOutcome::Gone, "another question is first"));
    }
    let asking = facts
        .status
        .as_ref()
        .is_some_and(|s| s.state == "blocked" && s.kind.as_deref() == Some("question"));
    if !asking {
        return Ok(reply(AnswerOutcome::Gone, "the Agent is not asking"));
    }

    let texts = &answer.free_text;
    let has_text = texts.iter().any(Option::is_some);
    if !texts.is_empty() && texts.len() != question.questions.len() {
        return Ok(reply(AnswerOutcome::Invalid, "answer count mismatch"));
    }
    let mut validation_picks = answer.picks.clone();
    for (i, text) in texts.iter().enumerate() {
        if let Some(text) = text {
            if text.trim().is_empty()
                || text.len() > 4096
                || text.chars().any(char::is_control)
                || answer.picks.get(i).is_none_or(|p| !p.is_empty())
            {
                return Ok(reply(AnswerOutcome::Invalid, "invalid free text"));
            }
            // Validate the remaining option parts with the existing rules.
            validation_picks[i] = vec![0];
        }
    }
    let actions = match validate(&question, &validation_picks)
        .and_then(|()| agent.answer_input(&question, &answer.picks, texts))
    {
        Ok(actions) => actions,
        Err(KeysError::Invalid(d)) => return Ok(reply(AnswerOutcome::Invalid, d)),
        Err(KeysError::Unsupported(d)) => return Ok(reply(AnswerOutcome::Unsupported, d)),
    };
    // Codex may collapse a queued request above its composer. Open it only
    // when the live screen advertises the default edit binding, then wait for
    // the exact question before selecting anything. Never open arbitrary TUI.
    if agent.name() == "codex" {
        let screen = link.screen(terminal).unwrap_or_default();
        let normalized = |text: &str| text.split_whitespace().collect::<Vec<_>>().join(" ");
        let prompt = normalized(&question.questions[0].text);
        if !normalized(&screen).contains(&prompt) && screen.contains("to answer") {
            let hint = screen.to_lowercase();
            let key = if hint.contains("shift+left")
                || hint.contains("shift+←")
                || (hint.contains('⇧') && hint.contains('←'))
            {
                "shift+left"
            } else if hint.contains("alt+up") || hint.contains("alt+↑") {
                "alt+up"
            } else {
                return Ok(reply(
                    AnswerOutcome::Unsupported,
                    "custom Codex question binding",
                ));
            };
            if let Err(e) = link.keys(terminal, &[key.into()]) {
                return Ok(reply(AnswerOutcome::Failed, e));
            }
            let mut opened = false;
            for _ in 0..20 {
                std::thread::sleep(std::time::Duration::from_millis(50));
                if link
                    .screen(terminal)
                    .is_ok_and(|s| normalized(&s).contains(&prompt))
                {
                    opened = true;
                    break;
                }
            }
            if !opened {
                return Ok(reply(
                    AnswerOutcome::Gone,
                    "queued question menu did not open",
                ));
            }
        }
    }
    if has_text && agent.name() != "codex" {
        let screen = match link.screen(terminal) {
            Ok(screen) => screen,
            Err(e) => return Ok(reply(AnswerOutcome::Gone, e)),
        };
        let normalized = |text: &str| text.split_whitespace().collect::<Vec<_>>().join(" ");
        let row = format!("{}.", question.questions[0].options.len() + 1);
        let other = screen.lines().any(|line| {
            line.contains(&row)
                && [
                    "Other",
                    "Type something",
                    "Type your own answer",
                    "None of the above",
                ]
                .iter()
                .any(|label| line.contains(label))
        });
        if !other || !normalized(&screen).contains(&normalized(&question.questions[0].text)) {
            return Ok(reply(AnswerOutcome::Gone, "the live menu does not match"));
        }
    }

    for action in actions {
        let result = match action {
            AnswerInput::Keys(keys) => link
                .keys(terminal, &keys)
                .map_err(|e| reply(AnswerOutcome::Failed, e)),
            AnswerInput::Text(text) => link
                .text(terminal, &text)
                .map_err(|e| reply(AnswerOutcome::Failed, e)),
            AnswerInput::Menu {
                prompt,
                options,
                pick,
                text,
            } => menu(link, terminal, &prompt, options, pick, text.as_deref()),
        };
        if let Err(reply) = result {
            return Ok(reply);
        }
    }
    Ok(AnswerReply::new(AnswerOutcome::Sent, None))
}

/// Codex's editor can retain a person's selection on any part. Re-read the
/// actual menu before each part, including after a preceding Enter advances.
fn menu<L: TerminalLookup + TerminalInput>(
    link: &mut L,
    terminal: &str,
    prompt: &str,
    options: usize,
    pick: Option<usize>,
    text: Option<&str>,
) -> Result<(), AnswerReply> {
    let normalize = |text: &str| text.split_whitespace().collect::<Vec<_>>().join(" ");
    let prompt = normalize(prompt);
    let mut screen = String::new();
    for _ in 0..20 {
        screen = link
            .screen(terminal)
            .map_err(|e| reply(AnswerOutcome::Gone, e))?;
        if normalize(&screen).contains(&prompt) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    if !normalize(&screen).contains(&prompt) {
        return Err(reply(
            AnswerOutcome::Gone,
            "the next question menu did not appear",
        ));
    }
    let selected = screen.lines().rev().find_map(|line| {
        let row = line.trim().strip_prefix(['›', '❯'])?.trim();
        row.split_once('.')?.0.parse::<usize>().ok()?.checked_sub(1)
    });
    let Some(selected) = selected.filter(|n| *n < options) else {
        return Err(reply(
            AnswerOutcome::Gone,
            "Codex selection or answer draft changed",
        ));
    };
    if text.is_some() {
        let row = format!("{}.", options + 1);
        if !screen.lines().any(|line| {
            line.contains(&row)
                && ["Other", "Type something", "None of the above"]
                    .iter()
                    .any(|label| line.contains(label))
        }) {
            return Err(reply(
                AnswerOutcome::Unsupported,
                "this question has no free-text row",
            ));
        }
    }
    let facts = link
        .get(terminal)
        .map_err(|e| reply(AnswerOutcome::NoCore, e))?;
    if !facts
        .and_then(|f| f.status)
        .is_some_and(|s| s.state == "blocked" && s.kind.as_deref() == Some("question"))
    {
        return Err(reply(AnswerOutcome::Gone, "the Agent is not asking"));
    }
    let target = pick.unwrap_or(options);
    let direction = if target < selected { "up" } else { "down" };
    let mut keys = vec![direction.to_owned(); target.abs_diff(selected)];
    keys.push("enter".into());
    link.keys(terminal, &keys)
        .map_err(|e| reply(AnswerOutcome::Failed, e))?;
    if let Some(text) = text {
        link.text(terminal, text)
            .map_err(|e| reply(AnswerOutcome::Failed, e))?;
        link.keys(terminal, &["enter".into()])
            .map_err(|e| reply(AnswerOutcome::Failed, e))?;
    }
    Ok(())
}

/// A current transcript, applying the same upsert / remove / reset semantics
/// as Chat. Shared by answers and status notifications; missing or replaced
/// transcripts have no details to show.
pub fn transcript(
    agent: &dyn Agent,
    located: &super::Located,
) -> std::io::Result<Option<Vec<ChatItem>>> {
    let mut source = agent.open(located, None).source;
    let mut items: Vec<ChatItem> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    loop {
        match source.poll()? {
            Batch::Missing | Batch::Replaced => {
                return Ok(None);
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
    Ok(Some(items))
}

/// One source of truth for Chat answers and notification actions.
pub fn current_question<'a>(
    agent: &str,
    items: &'a [ChatItem],
) -> Option<(&'a str, &'a ChatQuestion)> {
    if agent == "codex" {
        queued_question(items)
    } else {
        open_question(items)
    }
}

/// Unresolved questions in the current user turn, in transcript order.
pub fn pending_questions(
    items: &[ChatItem],
) -> impl DoubleEndedIterator<Item = (&str, &ChatQuestion)> {
    let start = items
        .iter()
        .rposition(|item| matches!(item.body, ChatBody::User { queued: false, .. }))
        .map_or(0, |i| i + 1);
    items[start..].iter().filter_map(|item| match &item.body {
        ChatBody::Tool {
            status: ToolStatus::Running,
            question: Some(q),
            ..
        } => Some((item.id.as_str(), q)),
        _ => None,
    })
}

/// Codex queues requests in transcript order within the current user turn.
/// A new user message retires unfinished tools from previous turns.
pub fn queued_question(items: &[ChatItem]) -> Option<(&str, &ChatQuestion)> {
    pending_questions(items).next()
}

/// The newest unresolved question in the current user turn. Older running
/// tools may have been interrupted; a later user message retires them.
pub fn open_question(items: &[ChatItem]) -> Option<(&str, &ChatQuestion)> {
    pending_questions(items).next_back()
}
