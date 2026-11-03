//! Agent status changes worth a push, from herdr-core's `events.subscribe`
//! stream (snapshot, then `event` / `missed` notifications).
//!
//! [`StatusWatch`] keeps each Terminal's status facts and reports an Agent
//! when it *enters* Blocked (per reason), Error or Done. While it stays
//! there it is not reported again, even if the program repeats its status
//! or changes the message; once it moves on (Working, Idle, no status, or a
//! different reason) the next entry is reported again. Which of these are
//! pushed is the [`aries_protocol::push_policy::PushPolicy`]'s call.
//!
//! The first snapshot is a baseline: Agents that were already Blocked when
//! the Beacon started are not reported (a restart must not repeat pushes).
//! Later snapshots (a `missed`, a reconnect) report only what changed.

use std::collections::HashMap;

use aries_protocol::agent_status::{self as rule, AgentStatus, BlockedReason, StatusRecord};
use serde_json::Value;

/// An Agent that just entered a status worth a push.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Change {
    pub terminal: String,
    pub name: Option<String>,
    pub title: Option<String>,
    /// No parent Terminal.
    pub top_level: bool,
    pub status: AgentStatus,
}

/// What a notification asks of the caller.
#[derive(Debug, PartialEq, Eq)]
pub enum Step {
    Changes(Vec<Change>),
    /// The event does not carry this Terminal's details: read it with
    /// `terminal.get` and hand the result to [`StatusWatch::replace`].
    Refetch(String),
}

/// What makes one status entry the "same" as the last one pushed.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Entered {
    Blocked(Option<BlockedReason>),
    Error,
    /// No turn number: `turn` only grows when an Agent leaves `working`,
    /// which re-arms anyway, and it arrives after the `status` event.
    Done,
}

#[derive(Debug, Default)]
struct Facts {
    /// Tells a Terminal from a later one at the same address.
    created_ms: u64,
    status_ms: u64,
    name: Option<String>,
    title: String,
    parent: Option<String>,
    status: Option<StatusRecord>,
    turn: u64,
    labels: HashMap<String, String>,
    /// The last status entry reported, while the Agent stays in it.
    entered: Option<Entered>,
}

impl Facts {
    fn from_terminal(t: &Value) -> Self {
        let mut f = Self {
            created_ms: t["created_ms"].as_u64().unwrap_or(0),
            status_ms: t["status_ms"].as_u64().unwrap_or(0),
            name: t["name"].as_str().map(str::to_owned),
            title: t["title"].as_str().unwrap_or_default().to_owned(),
            parent: t["parent"].as_str().map(str::to_owned),
            ..Self::default()
        };
        f.set_status(&t["status"]);
        f.turn = t["turn"].as_u64().unwrap_or(0);
        f.labels = serde_json::from_value(t["labels"].clone()).unwrap_or_default();
        f
    }

    fn set_status(&mut self, status: &Value) {
        self.status = serde_json::from_value(status.clone()).ok();
    }

    fn agent_status(&self) -> Option<AgentStatus> {
        rule::agent_status(self.status.as_ref(), self.turn, &self.labels)
    }

    /// Updates `entered`; `Some` when the Agent just entered a status worth
    /// reporting.
    fn settle(&mut self, address: &str) -> Option<Change> {
        let status = self.agent_status();
        let entered = match &status {
            Some(AgentStatus::Blocked { reason, .. }) => Some(Entered::Blocked(*reason)),
            Some(AgentStatus::Error { .. }) => Some(Entered::Error),
            Some(AgentStatus::Done) => Some(Entered::Done),
            _ => None,
        };
        if entered == self.entered {
            return None;
        }
        self.entered = entered.clone();
        entered?;
        Some(Change {
            terminal: address.to_owned(),
            name: self.name.clone(),
            title: Some(self.title.clone()).filter(|t| !t.is_empty()),
            top_level: self.parent.is_none(),
            status: status?,
        })
    }
}

#[derive(Debug, Default)]
pub struct StatusWatch {
    seq: u64,
    started: bool,
    terminals: HashMap<String, Facts>,
}

impl StatusWatch {
    pub fn new() -> Self {
        Self::default()
    }

    /// Status notifications omit status_ms. A lookup may already describe a
    /// later event: take only its timestamp when its status still agrees.
    pub fn refresh_status_time(&mut self, address: &str, terminal: &Value) {
        if let Some(f) = self.terminals.get_mut(address)
            && f.agent_status() == Facts::from_terminal(terminal).agent_status()
        {
            f.status_ms = terminal["status_ms"].as_u64().unwrap_or(0);
        }
    }

    /// Current Watched Agents, including their descendant Agent counts.
    pub fn watched(&self) -> Vec<crate::progress::WatchedAgent> {
        self.terminals
            .iter()
            .filter(|(_, f)| f.labels.get("aries.watch").is_some_and(|v| v == "1"))
            .map(|(address, f)| {
                let children = self
                    .terminals
                    .iter()
                    .filter(|(child, facts)| {
                        if facts.agent_status().is_none() || *child == address {
                            return false;
                        }
                        let mut parent = facts.parent.as_deref();
                        let mut visited = std::collections::HashSet::new();
                        while let Some(p) = parent {
                            if p == address {
                                return true;
                            }
                            if !visited.insert(p) {
                                break;
                            }
                            parent = self.terminals.get(p).and_then(|f| f.parent.as_deref());
                        }
                        false
                    })
                    .count() as u32;
                crate::progress::WatchedAgent {
                    terminal: address.clone(),
                    name: f.name.clone(),
                    title: Some(f.title.clone()).filter(|t| !t.is_empty()),
                    top_level: f.parent.is_none(),
                    status: f.agent_status(),
                    status_ms: f.status_ms,
                    created_ms: f.created_ms,
                    child_agents: children,
                }
            })
            .collect()
    }

    /// An `events.subscribe` result or a `missed` notification's params.
    /// Replaces every Terminal; Agents keep what was already reported for
    /// them. The first snapshot reports nothing.
    pub fn snapshot(&mut self, snapshot: &Value) -> Vec<Change> {
        self.seq = snapshot["seq"].as_u64().unwrap_or(0);
        let baseline = !self.started;
        self.started = true;
        let mut old = std::mem::take(&mut self.terminals);
        let mut changes = Vec::new();
        for t in snapshot["terminals"].as_array().into_iter().flatten() {
            let Some(address) = t["address"].as_str() else {
                continue;
            };
            let mut facts = Facts::from_terminal(t);
            if let Some(prev) = old.remove(address)
                && prev.created_ms == facts.created_ms
            {
                facts.entered = prev.entered;
            }
            if let Some(c) = facts.settle(address)
                && !baseline
            {
                changes.push(c);
            }
            self.terminals.insert(address.to_owned(), facts);
        }
        changes
    }

    /// Applies one notification from the subscription.
    pub fn apply(&mut self, msg: &Value) -> Step {
        let params = &msg["params"];
        match msg["method"].as_str() {
            Some("missed") => Step::Changes(self.snapshot(params)),
            Some("event") => {
                let seq = params["seq"].as_u64().unwrap_or(0);
                if seq <= self.seq {
                    return Step::Changes(vec![]);
                }
                self.seq = seq;
                self.event(params)
            }
            _ => Step::Changes(vec![]), // heartbeat
        }
    }

    /// A Terminal re-read after [`Step::Refetch`] (`None`: it is gone).
    pub fn replace(&mut self, address: &str, terminal: Option<&Value>) -> Vec<Change> {
        let Some(t) = terminal else {
            self.terminals.remove(address);
            return vec![];
        };
        let mut facts = Facts::from_terminal(t);
        if let Some(prev) = self.terminals.get(address)
            && prev.created_ms == facts.created_ms
        {
            facts.entered = prev.entered.clone();
        }
        let change = facts.settle(address);
        self.terminals.insert(address.to_owned(), facts);
        change.into_iter().collect()
    }

    fn event(&mut self, params: &Value) -> Step {
        let Some(address) = params["terminal"].as_str() else {
            return Step::Changes(vec![]);
        };
        let data = &params["data"];
        let kind = params["type"].as_str().unwrap_or("");
        match kind {
            "created" => return Step::Changes(self.replace(address, Some(data))),
            "removed" => {
                self.terminals.remove(address);
                return Step::Changes(vec![]);
            }
            _ => {}
        }
        let Some(f) = self.terminals.get_mut(address) else {
            return match kind {
                "doc" => Step::Changes(vec![]),
                _ => Step::Refetch(address.to_owned()),
            };
        };
        match kind {
            "status" => f.set_status(&data["status"]),
            "turn" => f.turn = data["turn"].as_u64().unwrap_or(f.turn),
            "title" => f.title = data["title"].as_str().unwrap_or_default().to_owned(),
            "labels" => {
                f.labels = serde_json::from_value(data["labels"].clone()).unwrap_or_default()
            }
            // herdr-core reports no status once the program exited.
            "finished" => f.status = None,
            "lost" | "restarted" | "keep" => return Step::Refetch(address.to_owned()),
            _ => return Step::Changes(vec![]),
        }
        Step::Changes(f.settle(address).into_iter().collect())
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn terminal(address: &str, parent: Option<&str>, status: Value) -> Value {
        json!({"address": address, "name": address, "title": "", "parent": parent,
               "created_ms": 1, "status": status, "turn": 0, "labels": {}})
    }

    fn event(seq: u64, address: &str, kind: &str, data: Value) -> Value {
        json!({"jsonrpc": "2.0", "method": "event",
               "params": {"seq": seq, "terminal": address, "type": kind, "data": data}})
    }

    fn status(seq: u64, address: &str, status: Value) -> Value {
        event(
            seq,
            address,
            "status",
            json!({"status": status, "records": {}}),
        )
    }

    fn changed(step: Step) -> Vec<(String, AgentStatus)> {
        match step {
            Step::Changes(c) => c.into_iter().map(|c| (c.terminal, c.status)).collect(),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_later_snapshot_reports_only_what_changed() {
        let mut w = StatusWatch::new();
        let blocked = json!({"state": "blocked", "kind": "auth"});
        assert!(
            w.snapshot(&json!({"seq": 1, "terminals": [terminal("t1", None, blocked.clone())]}))
                .is_empty()
        );
        let missed = json!({"method": "missed", "params": {"seq": 9, "terminals": [
            terminal("t1", None, blocked),
            terminal("t2", None, json!({"state": "error"})),
        ]}});
        assert_eq!(
            changed(w.apply(&missed)),
            [("t2".into(), AgentStatus::Error { message: None })]
        );
    }

    #[test]
    fn a_new_terminal_at_an_old_address_is_reported() {
        let mut w = StatusWatch::new();
        let blocked = json!({"state": "blocked"});
        w.snapshot(&json!({"seq": 1, "terminals": [terminal("t1", None, blocked.clone())]}));
        let mut again = terminal("t1", None, blocked);
        again["created_ms"] = json!(2);
        assert_eq!(changed(w.apply(&event(2, "t1", "created", again))).len(), 1);
    }

    #[test]
    fn an_exited_agent_is_reported_again_when_blocked_anew() {
        let mut w = StatusWatch::new();
        w.snapshot(&json!({"seq": 1, "terminals": [terminal("t1", None, Value::Null)]}));
        let blocked = json!({"state": "blocked"});
        assert_eq!(changed(w.apply(&status(2, "t1", blocked.clone()))).len(), 1);
        assert!(changed(w.apply(&event(3, "t1", "finished", json!({})))).is_empty());
        assert_eq!(changed(w.apply(&status(4, "t1", blocked))).len(), 1);
    }

    /// herdr-core sends `status` before the `turn` it bumps on leaving
    /// `working`: with an older unseen turn, Done shows up twice in a row.
    #[test]
    fn a_finished_turn_is_reported_once_when_its_turn_count_follows() {
        let mut w = StatusWatch::new();
        let mut t = terminal("t1", None, json!({"state": "blocked"}));
        t["turn"] = json!(1);
        w.snapshot(&json!({"seq": 1, "terminals": [t]}));
        assert!(changed(w.apply(&status(2, "t1", json!({"state": "working"})))).is_empty());
        assert_eq!(
            changed(w.apply(&status(3, "t1", json!({"state": "done"})))),
            [("t1".into(), AgentStatus::Done)]
        );
        assert!(changed(w.apply(&event(4, "t1", "turn", json!({"turn": 2})))).is_empty());
        // The next turn is a new Done.
        assert!(changed(w.apply(&status(5, "t1", json!({"state": "working"})))).is_empty());
        assert_eq!(
            changed(w.apply(&status(6, "t1", json!({"state": "done"})))).len(),
            1
        );
    }

    #[test]
    fn old_and_unknown_events_are_handled() {
        let mut w = StatusWatch::new();
        w.snapshot(&json!({"seq": 5, "terminals": []}));
        assert!(changed(w.apply(&status(5, "t1", json!({"state": "error"})))).is_empty());
        assert_eq!(
            w.apply(&status(6, "t9", json!({"state": "error"}))),
            Step::Refetch("t9".into())
        );
    }
}
