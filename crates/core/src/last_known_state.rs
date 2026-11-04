//! Last-known state comes from herdr-core, never from push payloads.
//! Disk work runs on blocking writers. A session generation check serializes
//! the final rename with Host edits/removal; file reads and serialization never
//! hold the global Host registry lock.
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
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
    pub sessions: crate::host_edit::HostSessions,
    pub io: Arc<Mutex<HashMap<String, Arc<Mutex<WriteOrder>>>>>,
    pub diagnostics: crate::diagnostics::Log,
}

/// Completion order can differ from observation order across one-shot reads
/// and watches. Track each field independently, including same-millisecond reads.
#[derive(Default)]
pub(crate) struct WriteOrder {
    agents: u64,
    structure: u64,
}

static OBSERVATION: AtomicU64 = AtomicU64::new(1);
fn observation() -> u64 {
    OBSERVATION.fetch_add(1, Ordering::Relaxed)
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
        let ids: Vec<_> = self
            .hosts
            .lock()
            .expect("hosts lock")
            .iter()
            .map(|h| h.id.clone())
            .collect();
        ids.iter()
            .filter_map(|id| self.read(id).transpose())
            .collect()
    }

    fn read(&self, host_id: &str) -> Result<Option<LastKnownState>, CoreError> {
        match std::fs::read(self.store.host_dir(host_id).join("last-known-state.json")) {
            Ok(bytes) => serde_json::from_slice(&bytes).map(Some).map_err(storage),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(storage(e)),
        }
    }

    /// Explicit refreshes may wait for durability; live reads use the queue.
    pub async fn save_checked(
        &self,
        host: String,
        revision: u64,
        update: Update,
    ) -> Result<(), CoreError> {
        let states = self.clone();
        tokio::task::spawn_blocking(move || states.persist(&host, revision, update))
            .await
            .map_err(storage)?
    }

    fn persist(&self, host: &str, revision: u64, update: Update) -> Result<(), CoreError> {
        let result = self.write(host, revision, update);
        if matches!(result, Err(CoreError::Storage { .. })) {
            self.diagnostics.record(
                Some(host),
                crate::DiagnosticSource::Storage,
                crate::DiagnosticEvent::LastKnownState,
                Some(crate::DiagnosticError::Storage),
                None,
            );
        }
        result
    }

    fn write(&self, host: &str, revision: u64, update: Update) -> Result<(), CoreError> {
        let gate = self
            .io
            .lock()
            .expect("state writers")
            .entry(host.into())
            .or_default()
            .clone();
        let mut order = gate.lock().expect("Host state writer");
        let mut next_agents = order.agents;
        let mut next_structure = order.structure;
        let now = update.time();
        let mut state = self.read(host)?.unwrap_or(LastKnownState {
            host_id: host.into(),
            agents: vec![],
            fetched_at_ms: now,
            structure: None,
            structure_fetched_at_ms: None,
        });
        match update {
            Update::Agents(agents, time, observed) => {
                if observed < order.agents || time < state.fetched_at_ms {
                    return Ok(());
                }
                next_agents = observed;
                state.agents = agents
                    .into_iter()
                    .filter(|t| t.agent_status.is_some())
                    .collect();
                state.fetched_at_ms = time;
            }
            Update::Structure(structure, time, observed) => {
                if observed < order.structure
                    || state.structure_fetched_at_ms.is_some_and(|t| time < t)
                {
                    return Ok(());
                }
                next_structure = observed;
                state.structure = Some(structure);
                state.structure_fetched_at_ms = Some(time);
            }
        }
        let bytes = serde_json::to_vec(&state).map_err(storage)?;
        // The staging path is outside the Host tree. A concurrent removal can
        // delete that tree without a late writer recreating it during staging.
        let tmp = self.store.root().join(format!(".last-known-{host}.tmp"));
        let commit = || -> Result<(), CoreError> {
            std::fs::write(&tmp, bytes).map_err(storage)?;
            let sessions = self.sessions.lock().expect("sessions lock");
            sessions
                .get(host)
                .ok_or(CoreError::Closed)?
                .check(revision)?;
            // Only directory creation + atomic rename share the invalidation
            // lock; JSON reading/encoding/writing happen outside registry locks.
            let dir = self.store.host_dir(host);
            std::fs::create_dir_all(&dir).map_err(storage)?;
            std::fs::rename(&tmp, dir.join("last-known-state.json")).map_err(storage)?;
            self.changed();
            Ok(())
        };
        let result = commit();
        if result.is_ok() {
            order.agents = next_agents;
            order.structure = next_structure;
        }
        if result.is_err() {
            let _ = std::fs::remove_file(tmp);
        }
        result
    }

    pub fn queue(&self, host: String, revision: u64, update: Update) {
        let writer = self.writer(host, revision);
        writer.send_replace(Some(update));
    }

    /// Latest complete values coalesce while one blocking write is in flight.
    /// Each watch owns its sender; closing it flushes the last observed value.
    pub fn writer(
        &self,
        host: String,
        revision: u64,
    ) -> tokio::sync::watch::Sender<Option<Update>> {
        let (tx, mut rx) = tokio::sync::watch::channel(None::<Update>);
        let states = self.clone();
        crate::runtime().spawn(async move {
            while rx.changed().await.is_ok() {
                tokio::time::sleep(Duration::from_millis(50)).await;
                let update = rx.borrow_and_update().clone();
                if let Some(update) = update {
                    let _ = states.save_checked(host.clone(), revision, update).await;
                }
            }
        });
        tx
    }
}

#[derive(Clone)]
pub(crate) enum Update {
    Agents(Vec<TerminalInfo>, u64, u64),
    Structure(HostStructure, u64, u64),
}

impl Update {
    pub fn agents(agents: Vec<TerminalInfo>) -> Self {
        Self::Agents(agents, now(), observation())
    }
    pub fn structure(structure: HostStructure) -> Self {
        Self::Structure(structure, now(), observation())
    }
    fn time(&self) -> u64 {
        match self {
            Self::Agents(_, t, _) | Self::Structure(_, t, _) => *t,
        }
    }
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[uniffi::export]
impl AriesCore {
    /// Reads without connecting or opening an SSH key.
    pub fn last_known_state(&self, host_id: String) -> Result<Option<LastKnownState>, CoreError> {
        let hosts = self.hosts.lock().expect("hosts lock");
        if !hosts.iter().any(|h| h.id == host_id) {
            return Err(CoreError::UnknownHost { id: host_id });
        }
        drop(hosts);
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
                let list = connection.fetch_terminals().await.map(Update::agents);
                // Layout failure must not discard a successful Agent pull.
                let structure = if list.is_ok() {
                    crate::read_structure(&connection.client, &connection.names)
                        .await
                        .ok()
                        .map(Update::structure)
                } else {
                    None
                };
                connection.disconnect().await;
                self.last_known
                    .save_checked(host_id.clone(), connection.revision, list?)
                    .await?;
                if let Some(structure) = structure {
                    self.last_known
                        .save_checked(host_id.clone(), connection.revision, structure)
                        .await?;
                }
                let states = self.last_known.clone();
                tokio::task::spawn_blocking(move || states.read(&host_id))
                    .await
                    .map_err(storage)??
                    .ok_or(CoreError::Closed)
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
                    tokio::task::spawn_blocking(move || states.all()).await.map_err(storage)?
                }
            }
        })
        .await
    }

    pub fn stop(&self) {
        self.stopped.send_replace(true);
    }
}
