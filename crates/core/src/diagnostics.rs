//! Local diagnostics contain only typed metadata. Never format CoreError or
//! protocol payloads here: their details can contain credentials or content.
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::{AriesCore, CoreError, store::Store};

pub const DIAGNOSTICS_KEPT: usize = 500;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum DiagnosticSource {
    Ssh,
    Beacon,
    Relay,
    Push,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum DiagnosticEvent {
    Connect,
    BeaconInstall,
    HerdrInstall,
    HerdrStatus,
    BeaconStatus,
    BeaconCheck,
    BeaconRemove,
    PushEnable,
    PushDisable,
    PushTest,
    RelayRequest,
    PushReceive,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum DiagnosticError {
    KeyUnavailable,
    HostKeyUntrusted,
    HostKeyChanged,
    Authentication,
    Unreachable,
    Incompatible,
    Protocol,
    Closed,
    Storage,
    HostUnavailable,
    BeaconUnavailable,
    BeaconDownload,
    HerdrInstall,
    HerdrChecksum,
    BeaconChecksum,
    BeaconOperation,
    RelayRegistration,
    RelayRemoval,
    PushRejected,
    Http,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct DiagnosticEntry {
    pub timestamp_ms: u64,
    /// Generated Host ID only; names and connection settings are not saved.
    pub host_id: Option<String>,
    pub source: DiagnosticSource,
    pub event: DiagnosticEvent,
    /// None means success. Free-form error strings are deliberately excluded.
    pub error: Option<DiagnosticError>,
    pub http_status: Option<u16>,
}

#[derive(Clone)]
pub(crate) struct Log {
    store: Store,
}

impl Log {
    pub fn new(store: Store) -> Self {
        Self { store }
    }

    // An OS file lock also serializes independent core handles (background receivers).
    fn lock(&self) -> Result<std::fs::File, CoreError> {
        let file = std::fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(self.store.root().join("diagnostics.lock"))
            .map_err(crate::error::storage)?;
        file.lock().map_err(crate::error::storage)?;
        Ok(file)
    }

    fn read(&self) -> Result<Vec<DiagnosticEntry>, CoreError> {
        match std::fs::read(self.store.root().join("diagnostics.json")) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(crate::error::storage),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
            Err(e) => Err(crate::error::storage(e)),
        }
    }

    fn save(&self, entries: &[DiagnosticEntry]) -> Result<(), CoreError> {
        self.store.write_atomic(
            "diagnostics.json",
            &serde_json::to_vec(entries).map_err(crate::error::storage)?,
        )
    }

    pub fn record(
        &self,
        host_id: Option<&str>,
        source: DiagnosticSource,
        event: DiagnosticEvent,
        error: Option<DiagnosticError>,
        http_status: Option<u16>,
    ) {
        // Diagnostics must never change the outcome of the operation recorded.
        let Ok(_guard) = self.lock() else {
            return;
        };
        let mut entries = self.read().unwrap_or_default();
        entries.push(DiagnosticEntry {
            timestamp_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            host_id: host_id.map(str::to_owned),
            source,
            event,
            error,
            http_status,
        });
        if entries.len() > DIAGNOSTICS_KEPT {
            entries.drain(..entries.len() - DIAGNOSTICS_KEPT);
        }
        let _ = self.save(&entries);
    }

    pub fn outcome<T>(
        &self,
        host_id: Option<&str>,
        source: DiagnosticSource,
        event: DiagnosticEvent,
        result: Result<T, CoreError>,
    ) -> Result<T, CoreError> {
        self.record(
            host_id,
            source,
            event,
            result.as_ref().err().map(category),
            None,
        );
        result
    }

    pub fn remove_host(&self, host_id: &str) -> Result<(), CoreError> {
        let _guard = self.lock()?;
        let mut entries = self.read()?;
        entries.retain(|entry| entry.host_id.as_deref() != Some(host_id));
        self.save(&entries)
    }
}

#[uniffi::export]
impl AriesCore {
    /// Newest first; filters combine. Reading history never starts network work.
    pub fn diagnostics(
        &self,
        source: Option<DiagnosticSource>,
        host_id: Option<String>,
        failures_only: bool,
    ) -> Result<Vec<DiagnosticEntry>, CoreError> {
        let _guard = self.diagnostics.lock()?;
        Ok(self
            .diagnostics
            .read()?
            .into_iter()
            .rev()
            .take(DIAGNOSTICS_KEPT)
            .filter(|entry| {
                source.is_none_or(|s| s == entry.source)
                    && host_id
                        .as_ref()
                        .is_none_or(|id| entry.host_id.as_ref() == Some(id))
                    && (!failures_only || entry.error.is_some())
            })
            .collect())
    }
}

pub(crate) fn category(error: &CoreError) -> DiagnosticError {
    use CoreError as C;
    use DiagnosticError as D;
    match error {
        C::NoKey
        | C::UnknownKey { .. }
        | C::InvalidPrivateKey
        | C::Vault { .. }
        | C::KeyLocked
        | C::BiometricUnavailable { .. } => D::KeyUnavailable,
        C::HostKeyUnknown { .. } | C::JumpHostKeyUnknown { .. } => D::HostKeyUntrusted,
        C::HostKeyChanged { .. } | C::JumpHostKeyChanged { .. } => D::HostKeyChanged,
        C::AuthRejected | C::PasswordRejected | C::PairingRefused | C::JumpAuthRejected { .. } => {
            D::Authentication
        }
        C::Unreachable { .. } => D::Unreachable,
        C::Incompatible { .. } => D::Incompatible,
        C::Protocol { .. } | C::Rpc { .. } | C::InvalidPairingQr => D::Protocol,
        C::Closed => D::Closed,
        C::Storage { .. } => D::Storage,
        C::InvalidHost { .. } | C::UnknownHost { .. } | C::UnknownJumpHost { .. } => {
            D::HostUnavailable
        }
        C::BeaconNeedsSsh
        | C::BeaconNotInstalled
        | C::UnsupportedHost { .. }
        | C::BeaconUnavailable { .. } => D::BeaconUnavailable,
        C::BeaconDownload { .. } => D::BeaconDownload,
        C::HerdrInstall { .. } => D::HerdrInstall,
        C::HerdrChecksum { .. } => D::HerdrChecksum,
        C::BeaconChecksum { .. } => D::BeaconChecksum,
        C::BeaconStep { .. } | C::KeyInstallFailed { .. } | C::AnswerRefused { .. } => {
            D::BeaconOperation
        }
        C::PushFailed { step, .. } => match step {
            crate::PushStep::Register => D::RelayRegistration,
            crate::PushStep::Unpair => D::RelayRemoval,
            crate::PushStep::Pair | crate::PushStep::Test => D::BeaconOperation,
        },
        C::PushRejected { .. } => D::PushRejected,
    }
}

impl Log {
    pub fn http(
        &self,
        client: Arc<dyn crate::HttpClient>,
        host_id: Option<String>,
    ) -> Arc<dyn crate::HttpClient> {
        Arc::new(RelayHttp {
            log: self.clone(),
            client,
            host_id,
        })
    }
}

struct RelayHttp {
    log: Log,
    client: Arc<dyn crate::HttpClient>,
    host_id: Option<String>,
}

impl crate::HttpClient for RelayHttp {
    fn send(&self, request: crate::HttpRequest) -> Result<crate::HttpResponse, crate::FetchError> {
        // Never inspect the URL, headers, body or returned error details.
        let result = self.client.send(request);
        let (error, status) = match &result {
            Ok(response) => (
                if (200..300).contains(&response.status) {
                    None
                } else {
                    Some(DiagnosticError::Http)
                },
                Some(response.status),
            ),
            Err(_) => (Some(DiagnosticError::Unreachable), None),
        };
        self.log.record(
            self.host_id.as_deref(),
            DiagnosticSource::Relay,
            DiagnosticEvent::RelayRequest,
            error,
            status,
        );
        result
    }
}
