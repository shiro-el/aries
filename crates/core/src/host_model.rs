//! Host model: one Host's Terminals with their Agent status, kept live from
//! `events.subscribe` (snapshot, then events; a `missed` notification brings
//! a fresh snapshot). The Agent status rule itself lives in
//! [`aries_protocol::agent_status`] so the Beacon computes the same thing.

use std::sync::Arc;

use serde_json::{Value, json};
use tokio::sync::{Mutex, watch};
use tokio::task::JoinHandle;

use aries_protocol::agent_status as rule;

use crate::error::CoreError;
use crate::hc::HcClient;
use crate::{TerminalInfo, terminal_info};

/// Why a Blocked Agent waits, when it said.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BlockedReason {
    Permission,
    Question,
    Auth,
}

/// What an Agent needs from the user.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum AgentStatus {
    Working,
    Blocked {
        reason: Option<BlockedReason>,
        message: Option<String>,
    },
    Error {
        message: Option<String>,
    },
    Done,
    Idle,
}

impl From<rule::AgentStatus> for AgentStatus {
    fn from(s: rule::AgentStatus) -> Self {
        match s {
            rule::AgentStatus::Working => Self::Working,
            rule::AgentStatus::Blocked { reason, message } => Self::Blocked {
                reason: reason.map(|r| match r {
                    rule::BlockedReason::Permission => BlockedReason::Permission,
                    rule::BlockedReason::Question => BlockedReason::Question,
                    rule::BlockedReason::Auth => BlockedReason::Auth,
                }),
                message,
            },
            rule::AgentStatus::Error { message } => Self::Error { message },
            rule::AgentStatus::Done => Self::Done,
            rule::AgentStatus::Idle => Self::Idle,
        }
    }
}

/// The Agent status of one herdr-core `Terminal` object, and whether it
/// needs the user.
pub(crate) fn status_of(terminal: &Value) -> (Option<AgentStatus>, bool) {
    let record: Option<rule::StatusRecord> = terminal
        .get("status")
        .filter(|s| !s.is_null())
        .and_then(|s| serde_json::from_value(s.clone()).ok());
    let labels = terminal
        .get("labels")
        .and_then(|l| serde_json::from_value(l.clone()).ok())
        .unwrap_or_default();
    let turn = terminal["turn"].as_u64().unwrap_or(0);
    match rule::agent_status(record.as_ref(), turn, &labels) {
        Some(s) => {
            let attention = s.needs_attention();
            (Some(s.into()), attention)
        }
        None => (None, false),
    }
}

/// What applying one notification asks of the caller.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Applied {
    Changed,
    Unchanged,
    /// The event's details are not in the stream: re-read this Terminal with
    /// `terminal.get` and pass the result to [`Terminals::replace`].
    Refetch(String),
}

/// The Terminals of one subscription, as raw herdr-core `Terminal` objects
/// in the order the core listed or created them.
#[derive(Debug, Default)]
pub(crate) struct Terminals {
    seq: u64,
    list: Vec<Value>,
}

impl Terminals {
    /// From an `events.subscribe` result or a `missed` notification's params.
    pub fn from_snapshot(snapshot: &Value) -> Self {
        Self {
            seq: snapshot["seq"].as_u64().unwrap_or(0),
            list: snapshot["terminals"]
                .as_array()
                .cloned()
                .unwrap_or_default(),
        }
    }

    pub fn infos(&self) -> Vec<TerminalInfo> {
        self.list.iter().map(terminal_info).collect()
    }

    /// Applies one notification from the event channel.
    pub fn apply(&mut self, msg: &Value) -> Applied {
        let params = &msg["params"];
        match msg["method"].as_str() {
            Some("missed") => {
                // Start over from the fresh snapshot; the seq it carries is
                // the new floor, whatever came before.
                *self = Self::from_snapshot(params);
                Applied::Changed
            }
            Some("event") => {
                let seq = params["seq"].as_u64().unwrap_or(0);
                if seq <= self.seq {
                    return Applied::Unchanged;
                }
                self.seq = seq;
                self.event(params)
            }
            _ => Applied::Unchanged, // heartbeat
        }
    }

    /// Replaces (or, with `None`, drops) a Terminal re-read after
    /// [`Applied::Refetch`].
    pub fn replace(&mut self, address: &str, terminal: Option<Value>) {
        match (self.position(address), terminal) {
            (Some(i), Some(t)) => self.list[i] = t,
            (None, Some(t)) => self.list.push(t),
            (Some(i), None) => {
                self.list.remove(i);
            }
            (None, None) => {}
        }
    }

    fn position(&self, address: &str) -> Option<usize> {
        self.list.iter().position(|t| t["address"] == address)
    }

    fn event(&mut self, params: &Value) -> Applied {
        let Some(address) = params["terminal"].as_str() else {
            return Applied::Unchanged;
        };
        let data = &params["data"];
        let kind = params["type"].as_str().unwrap_or("");
        if kind == "created" {
            self.replace(address, Some(data.clone()));
            return Applied::Changed;
        }
        let Some(i) = self.position(address) else {
            // An event for a Terminal we have not seen: read it.
            return match kind {
                "removed" | "doc" => Applied::Unchanged,
                _ => Applied::Refetch(address.to_owned()),
            };
        };
        let t = &mut self.list[i];
        match kind {
            "removed" => {
                self.list.remove(i);
            }
            "status" => {
                t["status"] = data["status"].clone();
                t["records"] = data.get("records").cloned().unwrap_or(json!({}));
                t["reported"] = data.get("reported").cloned().unwrap_or(json!(true));
            }
            "turn" => t["turn"] = data["turn"].clone(),
            "title" => t["title"] = data["title"].clone(),
            "labels" => t["labels"] = data["labels"].clone(),
            "finished" => {
                if let Some(obj) = data.as_object() {
                    for (k, v) in obj {
                        t[k] = v.clone();
                    }
                }
                t["state"] = json!("finished");
                // herdr-core reports no status once the program exited.
                t["status"] = Value::Null;
            }
            "lost" | "restarted" | "keep" => return Applied::Refetch(address.to_owned()),
            // size, foreground, doc: nothing the Terminal list shows.
            _ => return Applied::Unchanged,
        }
        Applied::Changed
    }
}

enum Latest {
    List(Vec<TerminalInfo>),
    Ended,
}

/// A live view of one Host's Terminals and their Agent status.
#[derive(uniffi::Object)]
pub struct TerminalWatch {
    rx: Arc<Mutex<watch::Receiver<Latest>>>,
    task: JoinHandle<()>,
}

impl TerminalWatch {
    pub(crate) async fn start(client: Arc<HcClient>) -> Result<Self, CoreError> {
        let (snapshot, mut events) = client.subscribe_events(json!({})).await?;
        let mut model = Terminals::from_snapshot(&snapshot);
        let (tx, mut rx) = watch::channel(Latest::List(model.infos()));
        rx.mark_changed();
        let task = tokio::spawn(async move {
            while let Some(msg) = events.recv().await {
                let changed = match model.apply(&msg) {
                    Applied::Changed => true,
                    Applied::Unchanged => false,
                    Applied::Refetch(address) => {
                        let got = client
                            .call("terminal.get", json!({ "terminal": address }))
                            .await;
                        match got {
                            Ok(t) => model.replace(&address, Some(t)),
                            Err(CoreError::Rpc { kind, .. }) if kind == "not_found" => {
                                model.replace(&address, None)
                            }
                            Err(_) => break,
                        }
                        true
                    }
                };
                if changed && tx.send(Latest::List(model.infos())).is_err() {
                    return;
                }
            }
            let _ = tx.send(Latest::Ended);
        });
        Ok(Self {
            rx: Arc::new(Mutex::new(rx)),
            task,
        })
    }
}

#[uniffi::export]
impl TerminalWatch {
    /// The Terminal list: right away on the first call, then each time it
    /// changes (intermediate states may be skipped). Fails with
    /// [`CoreError::Closed`] once the connection is gone; connect again and
    /// start a new watch, which begins from a fresh snapshot.
    pub async fn next(&self) -> Result<Vec<TerminalInfo>, CoreError> {
        let rx = self.rx.clone();
        crate::on_runtime(async move {
            let mut rx = rx.lock().await;
            rx.changed().await.map_err(|_| CoreError::Closed)?;
            match &*rx.borrow_and_update() {
                Latest::List(list) => Ok(list.clone()),
                Latest::Ended => Err(CoreError::Closed),
            }
        })
        .await
    }

    /// Stops watching. Later `next` calls fail with [`CoreError::Closed`].
    pub fn stop(&self) {
        self.task.abort();
    }
}

impl Drop for TerminalWatch {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn terminal(address: &str, status: Value, turn: u64) -> Value {
        json!({
            "address": address, "argv": ["fake"], "cwd": "/w", "labels": {},
            "state": "live", "status": status, "turn": turn, "title": "",
        })
    }

    fn event(seq: u64, address: &str, kind: &str, data: Value) -> Value {
        json!({"jsonrpc": "2.0", "method": "event",
               "params": {"seq": seq, "terminal": address, "type": kind, "data": data}})
    }

    fn status(list: &Terminals, address: &str) -> Option<AgentStatus> {
        list.infos()
            .into_iter()
            .find(|t| t.address == address)
            .and_then(|t| t.agent_status)
    }

    #[test]
    fn a_missed_snapshot_replaces_everything_and_resets_the_seq_floor() {
        let mut m = Terminals::from_snapshot(&json!({
            "seq": 500,
            "terminals": [terminal("t1", json!({"state": "working"}), 0)],
        }));
        let missed = json!({"jsonrpc": "2.0", "method": "missed", "params": {
            "seq": 40,
            "terminals": [terminal("t2", json!({"state": "blocked", "kind": "auth"}), 1)],
        }});
        assert_eq!(m.apply(&missed), Applied::Changed);
        assert_eq!(status(&m, "t1"), None);
        assert_eq!(
            status(&m, "t2"),
            Some(AgentStatus::Blocked {
                reason: Some(BlockedReason::Auth),
                message: None
            })
        );

        // Events at or below the snapshot's seq are already in it.
        let stale = event(40, "t2", "status", json!({"status": {"state": "working"}}));
        assert_eq!(m.apply(&stale), Applied::Unchanged);
        // A later one applies even though the old stream was at 500.
        let next = event(
            41,
            "t2",
            "status",
            json!({"status": {"state": "error", "msg": "boom"}}),
        );
        assert_eq!(m.apply(&next), Applied::Changed);
        assert_eq!(
            status(&m, "t2"),
            Some(AgentStatus::Error {
                message: Some("boom".into())
            })
        );
    }

    #[test]
    fn replays_a_recorded_claude_code_stream() {
        // Recorded with a real Claude Code under herdr-core 0.1.17.
        let fixture = include_str!("../../../fixtures/agents/claude-code/hc-events-7501.jsonl");
        let mut lines = fixture.lines().filter(|l| !l.trim().is_empty());
        let snapshot: Value = serde_json::from_str(lines.next().unwrap()).unwrap();
        let mut m = Terminals::from_snapshot(&snapshot);
        let mut blocked = Vec::new();
        for line in lines {
            let msg: Value = serde_json::from_str(line).unwrap();
            m.apply(&msg);
            if let Some(AgentStatus::Blocked { reason, message }) = status(&m, "t2") {
                let b = (reason.unwrap(), message.unwrap());
                if blocked.last() != Some(&b) {
                    blocked.push(b);
                }
            }
        }
        let p = |m: &str| (BlockedReason::Permission, m.to_owned());
        let q = |m: &str| (BlockedReason::Question, m.to_owned());
        assert_eq!(
            blocked,
            vec![
                p("approve Write: /tmp/aries-fixture/work-a/hello.txt"),
                q("answer: Which color do you prefer? (Red · Blue · Green)"),
                p("approve Update: /tmp/aries-fixture/work-a/hello.txt"),
            ]
        );
    }
}
