//! Sending a Chat message into an Agent's Terminal with herdr-core's
//! `terminal.send` (paste, then Enter). herdr-core holds a send while a
//! person types in the Terminal and refuses it while the Agent is Blocked;
//! Aries never forces it, so a message can't answer an Agent's dialog.

use std::time::Duration;

use serde_json::json;

use crate::error::CoreError;
use crate::hc::HcClient;
use crate::host_model::{AgentStatus, BlockedReason};

/// After this long without an answer the send counts as held: an
/// unhindered send returns in about half a second, a held one only once
/// nobody has typed for ~3 s.
pub const HELD_AFTER: Duration = Duration::from_millis(1500);

/// What became of a message sent from Chat.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SendOutcome {
    /// Typed into the Terminal and submitted.
    Sent,
    /// Refused because the Agent is Blocked; nothing was typed. `reason` and
    /// `message` are the Agent's own, when it still reports them.
    Blocked {
        reason: Option<BlockedReason>,
        message: Option<String>,
    },
}

/// What herdr-core observed after pressing the Agent's stop key.
#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum InterruptOutcome {
    Stopped,
    Exited,
    StillWorking,
    Unknown,
    /// The Agent was no longer Working when checked; no input was sent.
    NotWorking,
}

pub(crate) async fn interrupt(
    client: &HcClient,
    address: &str,
) -> Result<InterruptOutcome, CoreError> {
    let terminal = client
        .call("terminal.get", json!({ "terminal": address }))
        .await?;
    let info = crate::terminal_info(&terminal);
    if info.state != "live" || info.agent_status != Some(AgentStatus::Working) {
        return Ok(InterruptOutcome::NotWorking);
    }
    // herdr-core owns stop-key overrides and terminal key encoding. Never
    // retry: a lost reply may still mean the key reached the Agent.
    let reply = client
        .call(
            "terminal.interrupt",
            json!({ "terminal": address, "timeout": 5 }),
        )
        .await?;
    serde_json::from_value(reply["outcome"].clone()).map_err(|e| CoreError::Protocol {
        detail: format!("invalid interrupt outcome: {e}"),
    })
}

/// Hears about a send in progress.
#[uniffi::export(with_foreign)]
pub trait SendListener: Send + Sync {
    /// The send has waited [`HELD_AFTER`]: herdr-core is most likely holding
    /// it until a person at the Host stops typing. It still goes in after.
    fn on_held(&self);
}

pub(crate) async fn send(
    client: &HcClient,
    address: &str,
    text: &str,
    on_held: impl FnOnce(),
) -> Result<SendOutcome, CoreError> {
    let state = require_agent(client, address).await?;
    let mut params = json!({ "terminal": address, "text": text });
    // These are the statuses herdr-core accepts for its atomic precondition.
    // Recheck after a held send too, so an Agent exit cannot type into its shell.
    if matches!(state.as_str(), "idle" | "working") {
        params["if"] = json!(state);
    }
    let call = client.call("terminal.send", params);
    tokio::pin!(call);
    let result = tokio::select! {
        r = &mut call => r,
        _ = tokio::time::sleep(HELD_AFTER) => {
            on_held();
            call.await
        }
    };
    match result {
        Ok(_) => Ok(SendOutcome::Sent),
        Err(CoreError::Rpc { kind, .. }) if kind == "blocked" => Ok(blocked(client, address).await),
        Err(e) => Err(e),
    }
}

/// A live Terminal may now be a shell. Never use Chat's cached transcript
/// or the Terminal's launch argv as proof that an Agent is still there.
pub(crate) async fn require_agent(client: &HcClient, address: &str) -> Result<String, CoreError> {
    let terminal = client
        .call("terminal.get", json!({ "terminal": address }))
        .await?;
    if terminal["state"] != "live" || crate::host_model::status_of(&terminal).0.is_none() {
        return Err(CoreError::Rpc {
            kind: "no_agent".into(),
            detail: "the Agent has ended; open Terminal to continue".into(),
        });
    }
    Ok(terminal["status"]["state"]
        .as_str()
        .unwrap_or_default()
        .to_owned())
}

/// Why the Agent is Blocked, as it reports it now.
async fn blocked(client: &HcClient, address: &str) -> SendOutcome {
    let status = client
        .call("terminal.get", json!({ "terminal": address }))
        .await
        .ok()
        .and_then(|t| crate::terminal_info(&t).agent_status);
    match status {
        Some(AgentStatus::Blocked { reason, message }) => SendOutcome::Blocked { reason, message },
        _ => SendOutcome::Blocked {
            reason: None,
            message: None,
        },
    }
}
