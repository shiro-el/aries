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

use aries_beacon::status_watch::Change;
use aries_protocol::agent_status::AgentStatus;
use aries_protocol::push::{PADDED_LEN, PushMessage};
use aries_protocol::push_policy::PushPolicy;

use crate::push::{self, SendError};

/// Longest message / title text sent, in characters.
const MESSAGE_CHARS: usize = 400;
const TITLE_CHARS: usize = 120;

pub struct Sender(mpsc::Sender<(Vec<Change>, Vec<WatchedAgent>)>);

impl Sender {
    pub fn start() -> Self {
        let (tx, rx) = mpsc::channel::<(Vec<Change>, Vec<WatchedAgent>)>();
        std::thread::spawn(move || {
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
                        pending.insert(c.terminal.clone(), message(c, now / 1000));
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
                            at,
                        } => message(
                            Change {
                                terminal,
                                name,
                                title,
                                top_level,
                                status,
                            },
                            at,
                        ),
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
