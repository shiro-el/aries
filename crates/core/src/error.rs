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
    #[error("the Host's herdr-core cannot be used: {reason:?}")]
    Incompatible { reason: Incompatibility },
    /// The bytes from the Host were not the herdr-core protocol (for example
    /// a login shell printing to stdout) or the stream ended early.
    #[error("protocol failure: {detail}")]
    Protocol { detail: String },
    /// herdr-core answered a request with an error.
    #[error("herdr-core {kind}: {message}")]
    Rpc { kind: String, message: String },
    #[error("the connection to the Host closed")]
    Closed,
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
        Self::Vault { detail: e.to_string() }
    }
}

pub(crate) fn storage(e: impl std::fmt::Display) -> CoreError {
    CoreError::Storage { detail: e.to_string() }
}

pub(crate) fn protocol(detail: impl Into<String>) -> CoreError {
    CoreError::Protocol { detail: detail.into() }
}
