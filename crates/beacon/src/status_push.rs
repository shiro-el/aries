//! Pushes the Agent status changes `serve` sees, filtered by the push
//! policy, from a thread of its own so a slow Relay never holds up the
//! event stream.
//!
//! The policy is `<state>/push-policy.json` (a
//! [`PushPolicy`] as JSON, missing fields take the defaults); without it the
//! defaults apply. It is read for each change, so a new file takes effect
//! at once.

use std::sync::mpsc;

use aries_beacon::status_watch::Change;
use aries_protocol::agent_status::AgentStatus;
use aries_protocol::push::{PADDED_LEN, PushMessage};
use aries_protocol::push_policy::PushPolicy;

use crate::push::{self, SendError};

/// Longest message / title text sent, in characters.
const MESSAGE_CHARS: usize = 400;
const TITLE_CHARS: usize = 120;

pub struct Sender(mpsc::Sender<PushMessage>);

impl Sender {
    pub fn start() -> Self {
        let (tx, rx) = mpsc::channel::<PushMessage>();
        std::thread::spawn(move || {
            for message in rx {
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
                let _ = self.0.send(message(c, at));
            }
        }
    }
}

pub(crate) fn policy() -> PushPolicy {
    let path = crate::state_dir().join("push-policy.json");
    match std::fs::read(&path) {
        Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|e| {
            eprintln!("aries-beacon: {}: {e}; using the defaults", path.display());
            PushPolicy::default()
        }),
        Err(_) => PushPolicy::default(),
    }
}

fn clip(text: Option<String>, chars: usize) -> Option<String> {
    text.map(|t| match t.char_indices().nth(chars) {
        Some((end, _)) => format!("{}…", &t[..end]),
        None => t,
    })
}

/// The push for `c`, cut down until it fits the envelope.
fn message(c: Change, at: u64) -> PushMessage {
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
        at,
    };
    // Escaped control characters can still overflow: drop the free text.
    let fits = |m: &PushMessage| serde_json::to_vec(m).is_ok_and(|j| j.len() <= PADDED_LEN - 2);
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
            name: Some("n".repeat(500)),
            title: Some("\u{1}".repeat(TITLE_CHARS)),
            top_level: true,
            status: AgentStatus::Error {
                message: Some("\u{1}".repeat(5000)),
            },
        };
        let m = message(change, 1);
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
