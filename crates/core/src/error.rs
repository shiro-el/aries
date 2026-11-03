//! Errors the app can act on. Each variant maps to its own UI message.

/// Why a Host's herdr-core cannot be used by this app.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Incompatibility {
    /// The Host speaks a newer protocol generation: update Aries.
    AppTooOld { host_generation: u32 },
    /// The Host's herdr-core is older than [`crate::MIN_CORE_VERSION`] or an
    /// older generation: update herdr-core on the Host.
    CoreTooOld { version: String, minimum: String },
    /// The Host's herdr-core lacks methods Aries uses: update herdr-core.
    MissingMethods { methods: Vec<String> },
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("no SSH key has been created yet")]
    NoKey,
    #[error("no Host with id {id}")]
    UnknownHost { id: String },
    #[error("app storage failed: {detail}")]
    Storage { detail: String },
    #[error("key vault failed: {detail}")]
    Vault { detail: String },
    #[error("could not reach the Host: {detail}")]
    Unreachable { detail: String },
    /// First connection: the user must confirm `fingerprint` with
    /// `trust_host_key` before connecting again.
    #[error("the Host key {fingerprint} is not trusted yet")]
    HostKeyUnknown { fingerprint: String },
    /// The Host presented a different key than the trusted one. The
    /// connection was refused.
    #[error("the Host key changed from {expected} to {actual}")]
    HostKeyChanged { expected: String, actual: String },
    #[error("the Host did not accept the app's SSH key")]
    AuthRejected,
    /// Password bootstrap: the Host refused the user name or password.
    #[error("the Host did not accept the password")]
    PasswordRejected,
    /// Password bootstrap: logged in, but could not write
    /// `~/.ssh/authorized_keys` (for example a permission problem).
    #[error("could not install the key on the Host: {detail}")]
    KeyInstallFailed { detail: String },
    #[error("the Host's herdr-core cannot be used: {reason:?}")]
    Incompatible { reason: Incompatibility },
    /// The bytes from the Host were not the herdr-core protocol (for example
    /// a login shell printing to stdout) or the stream ended early.
    #[error("protocol failure: {detail}")]
    Protocol { detail: String },
    /// herdr-core answered a request with an error.
    #[error("herdr-core {kind}: {detail}")]
    Rpc { kind: String, detail: String },
    #[error("the connection to the Host closed")]
    Closed,
    /// Installing or managing the Beacon needs an SSH Host.
    #[error("the Beacon can only be managed on an SSH Host")]
    BeaconNeedsSsh,
    /// The Host's OS/CPU is not one the Beacon supports (yet).
    #[error("the Beacon does not support {os}/{arch} Hosts")]
    UnsupportedHost { os: String, arch: String },
    /// The release pinned in this app has no binary for the Host.
    #[error("this app has no Beacon for {os}/{arch}")]
    BeaconUnavailable { os: String, arch: String },
    #[error("downloading the Beacon failed: {detail}")]
    BeaconDownload { detail: String },
    /// The downloaded binary is not the one pinned in the app. Nothing was
    /// installed.
    #[error("the Beacon download has checksum {actual}, expected {expected}")]
    BeaconChecksum { expected: String, actual: String },
    /// A step on the Host (`upload`, `verify`, `service`, `status`, ...)
    /// failed.
    #[error("Beacon {step} failed on the Host: {detail}")]
    BeaconStep { step: String, detail: String },
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum VaultError {
    #[error("{detail}")]
    Failed { detail: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for VaultError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed { detail: e.reason }
    }
}

impl From<VaultError> for CoreError {
    fn from(e: VaultError) -> Self {
        Self::Vault {
            detail: e.to_string(),
        }
    }
}

pub(crate) fn storage(e: impl std::fmt::Display) -> CoreError {
    CoreError::Storage {
        detail: e.to_string(),
    }
}

pub(crate) fn protocol(detail: impl Into<String>) -> CoreError {
    CoreError::Protocol {
        detail: detail.into(),
    }
}
