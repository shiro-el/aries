//! Which Agent status changes the Beacon pushes to the phone.
//!
//! The user can change these options; [`PushPolicy::default`] is what Aries
//! ships with: Blocked and Error on, Done off, top-level Agents only.
//! Repeats are always suppressed and are not an option: an Agent that stays
//! Blocked for the same reason (or stays in Error) is pushed once, until its
//! status moves on to something else.

use serde::{Deserialize, Serialize};

use crate::agent_status::AgentStatus;
use crate::calls::{Call, Urgency};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct PushPolicy {
    /// Push when an Agent becomes Blocked (waiting for permission, an
    /// answer or a login).
    pub blocked: bool,
    /// Push when an Agent reports an error.
    pub error: bool,
    /// Push when an Agent finishes a turn the user has not seen.
    pub done: bool,
    /// Also push for subagents (Terminals with a parent Terminal), not only
    /// for top-level Agents.
    pub child_agents: bool,
}

impl Default for PushPolicy {
    fn default() -> Self {
        Self {
            blocked: true,
            error: true,
            done: false,
            child_agents: false,
        }
    }
}

impl PushPolicy {
    /// Whether an Agent that just became `status` is pushed. `top_level`:
    /// its Terminal has no parent Terminal.
    pub fn allows(&self, status: &AgentStatus, top_level: bool) -> bool {
        if !top_level && !self.child_agents {
            return false;
        }
        match status {
            AgentStatus::Blocked { .. } => self.blocked,
            AgentStatus::Error { .. } => self.error,
            AgentStatus::Done => self.done,
            AgentStatus::Working | AgentStatus::Idle => false,
        }
    }

    /// Whether `call` is pushed. Only a confirmed caller with an urgency
    /// above `record` is; a subagent's Call only with
    /// [`PushPolicy::child_agents`]. Everything else is only recorded.
    pub fn allows_call(&self, call: &Call) -> bool {
        call.terminal.is_some()
            && call.urgency != Urgency::Record
            && (call.top_level || self.child_agents)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn blocked() -> AgentStatus {
        AgentStatus::Blocked {
            reason: None,
            message: None,
        }
    }

    #[test]
    fn by_default_pushes_blocked_and_error_of_top_level_agents_only() {
        let p = PushPolicy::default();
        assert!(p.allows(&blocked(), true));
        assert!(p.allows(&AgentStatus::Error { message: None }, true));
        assert!(!p.allows(&AgentStatus::Done, true));
        assert!(!p.allows(&AgentStatus::Working, true));
        assert!(!p.allows(&AgentStatus::Idle, true));
        assert!(!p.allows(&blocked(), false));
    }

    #[test]
    fn options_widen_it() {
        let p = PushPolicy {
            done: true,
            child_agents: true,
            ..PushPolicy::default()
        };
        assert!(p.allows(&AgentStatus::Done, false));
        assert!(p.allows(&blocked(), false));
    }

    fn call(confirmed: bool, top_level: bool, urgency: Urgency) -> Call {
        Call {
            id: "c1".into(),
            at_ms: 1,
            terminal: confirmed.then(|| "t1".into()),
            top_level,
            title: "t".into(),
            body: String::new(),
            urgency,
            choices: vec![],
            answer: None,
        }
    }

    #[test]
    fn calls_push_from_confirmed_top_level_agents_unless_record() {
        let p = PushPolicy::default();
        assert!(p.allows_call(&call(true, true, Urgency::Check)));
        assert!(p.allows_call(&call(true, true, Urgency::Emergency)));
        assert!(!p.allows_call(&call(true, true, Urgency::Record)));
        assert!(!p.allows_call(&call(false, false, Urgency::Emergency)));
        assert!(!p.allows_call(&call(true, false, Urgency::Urgent)));
    }

    #[test]
    fn the_child_agents_option_also_pushes_subagent_calls() {
        let p = PushPolicy {
            child_agents: true,
            ..PushPolicy::default()
        };
        assert!(p.allows_call(&call(true, false, Urgency::Urgent)));
        assert!(!p.allows_call(&call(true, false, Urgency::Record)));
        assert!(!p.allows_call(&call(false, false, Urgency::Urgent)));
    }

    #[test]
    fn missing_options_take_the_defaults() {
        let p: PushPolicy = serde_json::from_str(r#"{"done":true}"#).unwrap();
        assert_eq!(
            p,
            PushPolicy {
                done: true,
                ..PushPolicy::default()
            }
        );
    }
}
