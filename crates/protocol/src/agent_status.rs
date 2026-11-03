//! Agent status: what an Agent needs from the user, computed from herdr-core's
//! Terminal facts. The phone core and the Beacon both use this one rule.
//!
//! A program reports its own state with OSC 7501; herdr-core stores the root
//! record as the Terminal's `status` (`null` if it never reported, or after it
//! exited) and counts `turn` up each time the status leaves `working`. Clients
//! share which turn the user has seen in the `herdr.seen` label. The rule
//! (herdr-app `docs/labels.md`): blocked/error need the user, working is
//! working, anything else is Done while `turn` is above `herdr.seen`, else Idle.
//! Nothing is inferred from the screen: no status, no Agent status.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

/// Shared label holding the last `turn` a user has seen.
pub const SEEN_LABEL: &str = "herdr.seen";

/// Shared label marking an Agent the user pinned (`herdr.pinned=1`).
pub const PINNED_LABEL: &str = "herdr.pinned";

/// The turn the user has seen, from `herdr.seen` (0 when unset or unreadable).
pub fn seen_turn(labels: &HashMap<String, String>) -> u64 {
    labels
        .get(SEEN_LABEL)
        .and_then(|s| s.trim().parse::<u64>().ok())
        .unwrap_or(0)
}

/// Whether `herdr.pinned` marks the Terminal pinned. Clients write `1` and
/// remove the label to unpin; an empty, `0` or `false` value counts as unpinned.
pub fn is_pinned(labels: &HashMap<String, String>) -> bool {
    labels
        .get(PINNED_LABEL)
        .map(|v| v.trim())
        .is_some_and(|v| !v.is_empty() && v != "0" && !v.eq_ignore_ascii_case("false"))
}

/// One OSC 7501 record as herdr-core reports it (`msg` already decoded).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatusRecord {
    /// `idle`, `working`, `done`, `blocked` or `error`, in the program's words.
    pub state: String,
    /// `permission`, `question` or `auth`, when blocked and the program said.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub msg: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub app: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockedReason {
    Permission,
    Question,
    Auth,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum AgentStatus {
    Working,
    /// Waiting for the user. `reason` is `None` when the program did not say
    /// (or said something this version does not know).
    Blocked {
        reason: Option<BlockedReason>,
        message: Option<String>,
    },
    Error {
        message: Option<String>,
    },
    /// Finished a turn the user has not seen yet.
    Done,
    Idle,
}

impl AgentStatus {
    /// Blocked and Error both mean the Agent needs the user.
    pub fn needs_attention(&self) -> bool {
        matches!(self, Self::Blocked { .. } | Self::Error { .. })
    }
}

/// The Agent status of a Terminal with root record `status`, `turn` and
/// `labels`; `None` for a program that does not report status.
pub fn agent_status(
    status: Option<&StatusRecord>,
    turn: u64,
    labels: &HashMap<String, String>,
) -> Option<AgentStatus> {
    let status = status?;
    let message = status.msg.clone().filter(|m| !m.is_empty());
    Some(match status.state.as_str() {
        "working" => AgentStatus::Working,
        "blocked" => AgentStatus::Blocked {
            reason: match status.kind.as_deref() {
                Some("permission") => Some(BlockedReason::Permission),
                Some("question") => Some(BlockedReason::Question),
                Some("auth") => Some(BlockedReason::Auth),
                _ => None,
            },
            message,
        },
        "error" => AgentStatus::Error { message },
        _ => {
            if turn > seen_turn(labels) {
                AgentStatus::Done
            } else {
                AgentStatus::Idle
            }
        }
    })
}
