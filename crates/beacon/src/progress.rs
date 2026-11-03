//! Watched Agent progress. Time is supplied by the service, so scheduling
//! uses the same rules in event-driven and quiet (no new events) periods.
use aries_protocol::{agent_status::AgentStatus, push::PushMessage};
use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone)]
pub struct WatchedAgent {
    pub terminal: String,
    pub name: Option<String>,
    pub title: Option<String>,
    pub top_level: bool,
    pub status: Option<AgentStatus>,
    pub status_ms: u64,
    pub created_ms: u64,
    pub child_agents: u32,
}

#[derive(Default)]
pub struct ProgressWatch {
    active: HashMap<String, WatchedAgent>,
    last: HashMap<String, u64>,
}

impl ProgressWatch {
    /// At most one Progress per Agent in 60 seconds. Completion is immediate
    /// and independent of the user's ordinary Done push policy.
    pub fn update(&mut self, mut agents: Vec<WatchedAgent>, now_ms: u64) -> Vec<PushMessage> {
        let mut messages = Vec::new();
        let addresses: HashSet<_> = agents.iter().map(|a| a.terminal.clone()).collect();
        for address in self.active.keys().filter(|a| !addresses.contains(*a)) {
            messages.push(PushMessage::Progress {
                terminal: address.clone(),
                name: None,
                elapsed_secs: 0,
                child_agents: 0,
                at: now_ms / 1000,
                cancelled: true,
            });
        }
        self.active.retain(|a, _| addresses.contains(a));
        self.last
            .retain(|a, last| addresses.contains(a) || now_ms.saturating_sub(*last) < 60_000);
        // Oldest sent first: large Host lists must not starve later Agents.
        agents.sort_by_key(|a| {
            (
                self.last.get(&a.terminal).copied().unwrap_or(0),
                a.terminal.clone(),
            )
        });
        let mut progress_count = 0;
        for mut a in agents {
            if a.status == Some(AgentStatus::Working) {
                if a.status_ms == 0 {
                    a.status_ms = self
                        .active
                        .get(&a.terminal)
                        .filter(|old| old.created_ms == a.created_ms)
                        .map(|old| old.status_ms)
                        .unwrap_or(now_ms);
                }
                if self
                    .active
                    .get(&a.terminal)
                    .is_some_and(|old| old.created_ms != a.created_ms)
                {
                    self.last.remove(&a.terminal);
                }
                let due = self
                    .last
                    .get(&a.terminal)
                    .is_none_or(|last| now_ms.saturating_sub(*last) >= 60_000);
                if due && progress_count < 25 {
                    messages.push(PushMessage::Progress {
                        terminal: a.terminal.clone(),
                        name: a
                            .name
                            .clone()
                            .or(a.title.clone())
                            .map(|s| s.chars().take(120).collect()),
                        elapsed_secs: now_ms.saturating_sub(if a.status_ms == 0 {
                            now_ms
                        } else {
                            a.status_ms
                        }) / 1000,
                        child_agents: a.child_agents,
                        at: now_ms / 1000,
                        cancelled: false,
                    });
                    self.last.insert(a.terminal.clone(), now_ms);
                    progress_count += 1;
                }
                self.active.insert(a.terminal.clone(), a);
            } else if self.active.remove(&a.terminal).is_some() {
                let status = match a.status {
                    Some(s @ AgentStatus::Blocked { .. }) | Some(s @ AgentStatus::Error { .. }) => {
                        s
                    }
                    _ => AgentStatus::Done,
                };
                messages.push(PushMessage::Status {
                    terminal: a.terminal,
                    name: a.name,
                    title: a.title,
                    top_level: a.top_level,
                    status,
                    at: now_ms / 1000,
                });
            }
        }
        messages
    }
}
