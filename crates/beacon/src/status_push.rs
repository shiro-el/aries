//! Pushes the Agent status changes `serve` sees, filtered by the push
//! policy, from a thread of its own so a slow Relay never holds up the
//! event stream.
//!
//! The policy is `<state>/push-policy.json` (a
//! [`PushPolicy`] as JSON, missing fields take the defaults); without it the
//! defaults apply. It is read for each change, so a new file takes effect
//! at once.

use std::sync::mpsc;

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

pub struct Sender(mpsc::Sender<(Change, u64)>);

impl Sender {
    pub fn start() -> Self {
        let (tx, rx) = mpsc::channel::<(Change, u64)>();
        std::thread::spawn(move || {
            let mut lookup = CoreLookup::default();
            let agents = chat::agents();
            for (change, at) in rx {
                let details = details(&change, &mut lookup, &agents);
                let message = message(change, at, details);
                match push::send(&message) {
                    Ok(()) | Err(SendError::NotPaired) => {}
                    Err(e) => eprintln!("aries-beacon: status push failed: {e}"),
                }
            }
        });
        Self(tx)
    }

    /// Queues a push for each change the policy allows.
    pub fn send(&self, changes: Vec<Change>) {
        if changes.is_empty() {
            return;
        }
        let policy = policy();
        let at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        for c in changes {
            if policy.allows(&c.status, c.top_level) {
                let _ = self.0.send((c, at));
            }
        }
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
    if !asking && c.status != AgentStatus::Done {
        return out;
    }
    let Some((agent, located)) = agents.iter().find_map(|a| a.locate(&facts).map(|l| (a, l)))
    else {
        return out;
    };
    let Ok(Some(items)) = chat::answer::transcript(agent.as_ref(), &located) else {
        return out;
    };
    if asking {
        if let Some((id, q)) = chat::answer::open_question(&items)
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
            ChatBody::Agent { text } => Some(text.clone()),
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
        excerpt: clip(details.excerpt, EXCERPT_CHARS),
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
