//! Pushes the Agent status changes `serve` sees, filtered by the push
//! policy, from a thread of its own so a slow Relay never holds up the
//! event stream.
//!
//! The policy is `<state>/push-policy.json` (a
//! [`PushPolicy`] as JSON, missing fields take the defaults); without it the
//! defaults apply. It is read for each change, so a new file takes effect
//! at once.

use aries_beacon::progress::{ProgressWatch, WatchedAgent};
use std::sync::mpsc;
use std::time::Duration;

use aries_beacon::chat::{self, Agent, TerminalLookup};
use aries_beacon::core_link::CoreLookup;
use aries_beacon::status_watch::Change;
use aries_protocol::agent_status::{self, AgentStatus, BlockedReason};
use aries_protocol::chat::ChatBody;
use aries_protocol::push::{PADDED_LEN, PushMessage, PushQuestion};
use aries_protocol::push_policy::PushPolicy;

use crate::push::{self, SendError};

/// Longest message / title text sent, in characters.
const MESSAGE_CHARS: usize = 400;
const TITLE_CHARS: usize = 120;
const EXCERPT_CHARS: usize = 200;

pub struct Sender(mpsc::Sender<(Vec<Change>, Vec<WatchedAgent>)>);

impl Sender {
    pub fn start() -> Self {
        let (tx, rx) = mpsc::channel::<(Vec<Change>, Vec<WatchedAgent>)>();
        std::thread::spawn(move || {
            let mut lookup = CoreLookup::default();
            let chat_agents = chat::agents();
            let mut progress = ProgressWatch::default();
            let mut agents = Vec::new();
            let mut pending = std::collections::BTreeMap::new();
            loop {
                let mut changes = Vec::new();
                match rx.recv_timeout(Duration::from_secs(1)) {
                    Ok((c, a)) => {
                        changes = c;
                        agents = a;
                    }
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                }
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);
                // A queued old completion must not close a later Working turn.
                for a in &agents {
                    if a.status == Some(AgentStatus::Working)
                        && pending.get(&a.terminal).is_some_and(|m| {
                            !matches!(
                                m,
                                PushMessage::Progress {
                                    cancelled: false,
                                    ..
                                }
                            )
                        })
                    {
                        pending.remove(&a.terminal);
                    }
                }
                let policy = policy();
                for c in changes {
                    if policy.allows(&c.status, c.top_level) {
                        let extra = details(&c, &mut lookup, &chat_agents);
                        pending.insert(c.terminal.clone(), message(c, now / 1000, extra));
                    }
                }
                for m in progress.update(agents.clone(), now) {
                    // Forced completion uses the same envelope budget as ordinary statuses.
                    let m = match m {
                        PushMessage::Status {
                            terminal,
                            name,
                            title,
                            top_level,
                            status,
                            turn,
                            at,
                            ..
                        } => {
                            let c = Change {
                                terminal,
                                name,
                                title,
                                top_level,
                                status,
                                turn: turn.unwrap_or(0),
                            };
                            let extra = details(&c, &mut lookup, &chat_agents);
                            message(c, at, extra)
                        }
                        m => m,
                    };
                    let terminal = match &m {
                        PushMessage::Status { terminal, .. }
                        | PushMessage::Progress { terminal, .. } => terminal,
                        _ => unreachable!(),
                    };
                    // Progress cannot overwrite a pending completion/status card.
                    if !matches!(
                        m,
                        PushMessage::Progress {
                            cancelled: false,
                            ..
                        }
                    ) || !pending
                        .get(terminal)
                        .is_some_and(|m| matches!(m, PushMessage::Status { .. }))
                    {
                        pending.insert(terminal.clone(), m);
                    }
                }
                // Attention/completion first; progress leaves five slots per minute.
                let mut outgoing: Vec<_> = std::mem::take(&mut pending).into_iter().collect();
                outgoing.sort_by_key(|(_, m)| match m {
                    PushMessage::Progress {
                        cancelled: false,
                        at,
                        ..
                    } => (true, *at),
                    _ => (false, 0),
                });
                for (terminal, m) in outgoing {
                    match push::send(&m) {
                        Ok(()) | Err(SendError::NotPaired) => {}
                        Err(SendError::RateLimited) => {
                            pending.insert(terminal, m);
                        }
                        Err(e) => eprintln!("aries-beacon: status push failed: {e}"),
                    }
                }
            }
        });
        Self(tx)
    }

    /// Also updates the scheduler's latest facts; its timer runs without events.
    pub fn send(&self, changes: Vec<Change>, agents: Vec<WatchedAgent>) {
        let _ = self.0.send((changes, agents));
    }
}

pub(crate) fn policy() -> PushPolicy {
    crate::policy::read()
}

fn clip(text: Option<String>, chars: usize) -> Option<String> {
    text.map(|t| match t.char_indices().nth(chars) {
        Some((end, _)) => format!("{}…", &t[..end]),
        None => t,
    })
}

/// Match the phone's compact Markdown projection before spending the excerpt budget.
fn notification_text(markdown: &str) -> String {
    use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd, TextMergeStream};
    let mut text = String::new();
    let mut code = None::<String>;
    let line = |text: &mut String| {
        if !text.is_empty() && !text.ends_with('\n') {
            text.push('\n');
        }
    };
    for event in TextMergeStream::new(Parser::new_ext(markdown, Options::ENABLE_TABLES)) {
        match event {
            Event::Start(Tag::CodeBlock(_)) => code = Some(String::new()),
            Event::Text(t) if code.is_some() => code.as_mut().unwrap().push_str(&t),
            Event::End(TagEnd::CodeBlock) => {
                line(&mut text);
                if let Some(first) = code.take().unwrap().lines().find(|s| !s.trim().is_empty()) {
                    text.push_str(first.trim());
                }
                line(&mut text);
            }
            Event::Text(t) => {
                // Unpaired emphasis/backticks are literal Text in CommonMark. Keep
                // ordinary arithmetic and identifier underscores while dropping remnants.
                let chars: Vec<_> = t.chars().collect();
                let mut i = 0;
                while i < chars.len() {
                    let c = chars[i];
                    if !matches!(c, '*' | '_' | '`') {
                        text.push(c);
                        i += 1;
                        continue;
                    }
                    let start = i;
                    while i < chars.len() && chars[i] == c {
                        i += 1;
                    }
                    let before = start.checked_sub(1).map(|j| chars[j]);
                    let after = chars.get(i).copied();
                    let orphan = match c {
                        '`' => true,
                        '*' => {
                            i - start > 1
                                || before.is_some_and(|c| !c.is_whitespace())
                                    != after.is_some_and(|c| !c.is_whitespace())
                        }
                        '_' => {
                            !before.is_some_and(char::is_alphanumeric)
                                || !after.is_some_and(char::is_alphanumeric)
                        }
                        _ => false,
                    };
                    if !orphan {
                        text.extend(&chars[start..i]);
                    }
                }
            }
            Event::Code(t) => text.push_str(&t),
            Event::Start(Tag::Item) => {
                line(&mut text);
                text.push_str("• ");
            }
            Event::Start(Tag::TableCell) if !text.is_empty() && !text.ends_with('\n') => {
                text.push_str(" · ");
            }
            Event::End(
                TagEnd::Paragraph
                | TagEnd::Heading(_)
                | TagEnd::BlockQuote
                | TagEnd::Item
                | TagEnd::TableHead
                | TagEnd::TableRow,
            )
            | Event::Rule => line(&mut text),
            Event::SoftBreak => text.push(' '),
            Event::HardBreak => line(&mut text),
            Event::Html(t) | Event::InlineHtml(t) => {
                let mut in_tag = false;
                for c in t.chars() {
                    match c {
                        '<' => in_tag = true,
                        '>' => in_tag = false,
                        c if !in_tag => text.push(c),
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }
    text.trim().to_owned()
}

#[derive(Default)]
struct Details {
    turn: Option<u64>,
    question: Option<PushQuestion>,
    excerpt: Option<String>,
}

/// Read on the push worker so transcript I/O cannot hold up status tracking.
/// A queued transition may already be old: attach actionable details only
/// while the Host still reports that status.
fn details(c: &Change, lookup: &mut CoreLookup, agents: &[Box<dyn Agent>]) -> Details {
    let mut out = Details::default();
    let Ok(Some(facts)) = lookup.get(&c.terminal) else {
        return out;
    };
    let labels = facts
        .labels
        .iter()
        .filter_map(|(k, v)| v.as_str().map(|v| (k.clone(), v.to_owned())))
        .collect();
    if facts.turn != Some(c.turn)
        || agent_status::agent_status(facts.status.as_ref(), facts.turn.unwrap_or(0), &labels)
            .as_ref()
            != Some(&c.status)
    {
        return out;
    }
    out.turn = facts.turn;
    let asking = matches!(
        c.status,
        AgentStatus::Blocked {
            reason: Some(BlockedReason::Question),
            ..
        }
    );
    let permission = matches!(
        c.status,
        AgentStatus::Blocked {
            reason: Some(BlockedReason::Permission),
            ..
        }
    );
    if !asking && !permission && c.status != AgentStatus::Done {
        return out;
    }
    let Some((agent, located)) = agents.iter().find_map(|a| a.locate(&facts).map(|l| (a, l)))
    else {
        return out;
    };
    if permission {
        // Agents can report Blocked just before drawing the menu. Give that
        // render a short bounded window, checking the current turn/status on
        // every read so a later request cannot inherit this notification.
        for attempt in 0..20 {
            let Ok(Some(current)) = lookup.get(&c.terminal) else {
                break;
            };
            if current.turn != facts.turn || current.status != facts.status {
                break;
            }
            if let Ok(screen) = lookup.screen(&c.terminal)
                && let Some(p) =
                    chat::permission::detect(agent.name(), &located.session, &current, &screen)
                && let ChatBody::Tool {
                    question: Some(q), ..
                } = p.item.body
            {
                let q = &q.questions[0];
                out.question = Some(PushQuestion {
                    id: p.item.id,
                    // Keep persistent approval scope visible beside short buttons.
                    text: std::iter::once(q.text.clone())
                        .chain(
                            q.options
                                .iter()
                                .filter(|o| o.label == "Allow always")
                                .filter_map(|o| o.description.clone()),
                        )
                        .collect::<Vec<_>>()
                        .join("\n"),
                    options: q.options.iter().map(|o| o.label.clone()).collect(),
                });
                break;
            }
            if attempt < 19 {
                std::thread::sleep(Duration::from_millis(50));
            }
        }
        return out;
    }
    let Ok(Some(items)) = chat::answer::transcript(agent.as_ref(), &located) else {
        return out;
    };
    if asking {
        if chat::answer::pending_questions(&items).count() == 1
            && let Some((id, q)) = chat::answer::current_question(agent.name(), &items)
            && let [q] = q.questions.as_slice()
            && !q.multi
            && (1..=3).contains(&q.options.len())
        {
            out.question = Some(PushQuestion {
                id: id.to_owned(),
                text: q.text.clone(),
                options: q.options.iter().map(|o| o.label.clone()).collect(),
            });
        }
    } else {
        out.excerpt = items.iter().rev().find_map(|i| match &i.body {
            ChatBody::Agent { text, .. } => Some(text.clone()),
            _ => None,
        });
    }
    out
}

/// The push for `c`, cut down until it fits the envelope.
fn message(c: Change, at: u64, details: Details) -> PushMessage {
    let mut status = c.status;
    match &mut status {
        AgentStatus::Blocked { message, .. } | AgentStatus::Error { message } => {
            *message = clip(message.take(), MESSAGE_CHARS)
        }
        _ => {}
    }
    let mut m = PushMessage::Status {
        terminal: c.terminal,
        name: clip(c.name, TITLE_CHARS),
        title: clip(c.title, TITLE_CHARS),
        top_level: c.top_level,
        status,
        turn: details.turn,
        question: details.question,
        excerpt: clip(
            details.excerpt.map(|text| notification_text(&text)),
            EXCERPT_CHARS,
        ),
        at,
    };
    // Escaped control characters can still overflow: drop the free text.
    let fits = |m: &PushMessage| serde_json::to_vec(m).is_ok_and(|j| j.len() <= PADDED_LEN - 2);
    // Preserve the actionable question before the optional reply preview.
    if !fits(&m)
        && let PushMessage::Status { excerpt, .. } = &mut m
    {
        *excerpt = None;
    }
    if !fits(&m)
        && let PushMessage::Status { question, .. } = &mut m
    {
        *question = None;
    }
    if !fits(&m)
        && let PushMessage::Status { title, status, .. } = &mut m
    {
        *title = None;
        if let AgentStatus::Blocked { message, .. } | AgentStatus::Error { message } = status {
            *message = None;
        }
    }
    m
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_long_message_is_cut_to_fit_the_envelope() {
        let change = Change {
            terminal: "t1".into(),
            turn: 1,
            name: Some("n".repeat(500)),
            title: Some("\u{1}".repeat(TITLE_CHARS)),
            top_level: true,
            status: AgentStatus::Error {
                message: Some("\u{1}".repeat(5000)),
            },
        };
        let m = message(change, 1, Details::default());
        let kp = aries_protocol::push::KeyPair::generate();
        aries_protocol::push::seal(&m, &kp.public, &kp).expect("fits");
        match m {
            PushMessage::Status { name, .. } => {
                assert_eq!(name.unwrap().chars().count(), TITLE_CHARS + 1)
            }
            other => panic!("{other:?}"),
        }
    }
}
