//! Last-known state comes from herdr-core, never from push payloads.
//! The shared Host lock serializes writes with Host removal so a late
//! network response cannot recreate deleted state.
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::{
    AriesCore, CoreError, HostStructure, TerminalInfo,
    store::{SavedHost, Store},
};

/// The last Agent list fetched from one Host, including children.
/// A missing value means it has never been fetched; an empty list is a
/// successful fetch with no Agents. Failures leave the previous value intact.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct LastKnownState {
    pub host_id: String,
    pub agents: Vec<TerminalInfo>,
    /// Unix epoch milliseconds when this list was fetched (not status change time).
    pub fetched_at_ms: u64,
    /// Last successful structure read, retained across Agent-only updates.
    #[serde(default)]
    #[uniffi(default = None)]
    pub structure: Option<HostStructure>,
    #[serde(default)]
    #[uniffi(default = None)]
    pub structure_fetched_at_ms: Option<u64>,
}

#[derive(Clone)]
pub(crate) struct States {
    pub store: Store,
    pub hosts: Arc<Mutex<Vec<SavedHost>>>,
    pub changes: tokio::sync::watch::Sender<u64>,
}

fn storage(e: impl std::fmt::Display) -> CoreError {
    CoreError::Storage {
        detail: e.to_string(),
    }
}

impl States {
    pub fn changed(&self) {
        self.changes
            .send_modify(|revision| *revision = revision.wrapping_add(1));
    }

    fn all(&self) -> Result<Vec<LastKnownState>, CoreError> {
        let hosts = self.hosts.lock().expect("hosts lock");
        hosts
            .iter()
            .filter_map(|h| self.read(&h.id).transpose())
            .collect()
    }

    fn read(&self, host_id: &str) -> Result<Option<LastKnownState>, CoreError> {
        match std::fs::read(self.store.host_dir(host_id).join("last-known-state.json")) {
            Ok(bytes) => serde_json::from_slice(&bytes).map(Some).map_err(storage),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(storage(e)),
        }
    }

    pub fn save(
        &self,
        host_id: &str,
        terminals: Vec<TerminalInfo>,
    ) -> Result<LastKnownState, CoreError> {
        let hosts = self.hosts.lock().expect("hosts lock");
        if !hosts.iter().any(|h| h.id == host_id) {
            return Err(CoreError::UnknownHost { id: host_id.into() });
        }
        let previous = self.read(host_id)?;
        let state = LastKnownState {
            structure: previous.as_ref().and_then(|s| s.structure.clone()),
            structure_fetched_at_ms: previous.and_then(|s| s.structure_fetched_at_ms),
            host_id: host_id.into(),
            agents: terminals
                .into_iter()
                .filter(|t| t.agent_status.is_some())
                .collect(),
            fetched_at_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
        };
        let dir = self.store.host_dir(host_id);
        std::fs::create_dir_all(&dir).map_err(storage)?;
        let tmp = dir.join(".last-known-state.tmp");
        std::fs::write(&tmp, serde_json::to_vec(&state).map_err(storage)?).map_err(storage)?;
        std::fs::rename(tmp, dir.join("last-known-state.json")).map_err(storage)?;
        self.changed();
        Ok(state)
    }

    /// Serialized with Agent updates and Host removal by the same Host lock.
    pub fn save_structure(&self, host_id: &str, structure: HostStructure) -> Result<(), CoreError> {
        let hosts = self.hosts.lock().expect("hosts lock");
        if !hosts.iter().any(|h| h.id == host_id) {
            return Err(CoreError::UnknownHost { id: host_id.into() });
        }
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let mut state = self.read(host_id)?.unwrap_or(LastKnownState {
            host_id: host_id.into(),
            agents: vec![],
            fetched_at_ms: now,
            structure: None,
            structure_fetched_at_ms: None,
        });
        state.structure = Some(structure);
        state.structure_fetched_at_ms = Some(now);
        let dir = self.store.host_dir(host_id);
        std::fs::create_dir_all(&dir).map_err(storage)?;
        let tmp = dir.join(".last-known-state.tmp");
        std::fs::write(&tmp, serde_json::to_vec(&state).map_err(storage)?).map_err(storage)?;
        std::fs::rename(tmp, dir.join("last-known-state.json")).map_err(storage)?;
        self.changed();
        Ok(())
    }
}

#[uniffi::export]
impl AriesCore {
    /// Reads without connecting or opening an SSH key.
    pub fn last_known_state(&self, host_id: String) -> Result<Option<LastKnownState>, CoreError> {
        let hosts = self.hosts.lock().expect("hosts lock");
        if !hosts.iter().any(|h| h.id == host_id) {
            return Err(CoreError::UnknownHost { id: host_id });
        }
        self.last_known.read(&host_id)
    }

    /// Stored lists for all registered Hosts; never performs network I/O.
    pub fn last_known_states(&self) -> Result<Vec<LastKnownState>, CoreError> {
        self.last_known.all()
    }

    /// Starts with the stored lists, then returns them after a successful
    /// refresh or Host removal. Intermediate updates may be coalesced.
    pub fn watch_last_known_states(&self) -> Arc<LastKnownStateWatch> {
        let mut rx = self.last_known.changes.subscribe();
        rx.mark_changed();
        Arc::new(LastKnownStateWatch {
            states: self.last_known.clone(),
            rx: Arc::new(tokio::sync::Mutex::new(rx)),
            stopped: tokio::sync::watch::channel(false).0,
        })
    }

    /// A short connection to read Terminals and the layout, then disconnect.
    /// Background callers must check `can_answer_in_background` first;
    /// foreground callers may use a biometric key while the app is open.
    pub async fn snapshot(self: Arc<Self>, host_id: String) -> Result<LastKnownState, CoreError> {
        crate::on_runtime(async move {
            tokio::time::timeout(Duration::from_secs(30), async {
                let connection = self.connect(host_id.clone()).await?;
                let list = connection.terminals().await;
                // Layout failure must not discard a successful Agent pull.
                if list.is_ok() {
                    let _ = connection.structure().await;
                }
                connection.disconnect().await;
                list?;
                self.last_known_state(host_id)?.ok_or(CoreError::Closed)
            })
            .await
            .map_err(|_| CoreError::Unreachable {
                detail: "Host snapshot timed out".into(),
            })?
        })
        .await
    }
}

/// Change subscription for widgets, tiles and other phone surfaces.
#[derive(uniffi::Object)]
pub struct LastKnownStateWatch {
    states: States,
    rx: Arc<tokio::sync::Mutex<tokio::sync::watch::Receiver<u64>>>,
    stopped: tokio::sync::watch::Sender<bool>,
}

#[uniffi::export]
impl LastKnownStateWatch {
    pub async fn next(&self) -> Result<Vec<LastKnownState>, CoreError> {
        let states = self.states.clone();
        let rx = self.rx.clone();
        let mut stopped = self.stopped.subscribe();
        crate::on_runtime(async move {
            if *stopped.borrow() {
                return Err(CoreError::Closed);
            }
            let mut rx = rx.lock().await;
            tokio::select! {
                _ = stopped.changed() => Err(CoreError::Closed),
                changed = rx.changed() => {
                    changed.map_err(|_| CoreError::Closed)?;
                    states.all()
                }
            }
        })
        .await
    }

    pub fn stop(&self) {
        self.stopped.send_replace(true);
    }
}
