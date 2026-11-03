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
    /// The SSH key needs a biometric unlock and nobody unlocked it (for
    /// example in the background, or the user cancelled the prompt). The key
    /// was not used.
    #[error("the SSH key needs a biometric unlock")]
    KeyLocked,
    /// A biometric-protected key cannot be made on this phone (no secure
    /// lock screen or no enrolled biometric).
    #[error("biometric unlock is not available: {detail}")]
    BiometricUnavailable { detail: String },
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
    #[error("no SSH key with id {id}")]
    UnknownKey { id: String },
    /// `hop` is an index into the Host's jump hosts.
    #[error("the Host has no jump host {hop}")]
    UnknownJumpHost { hop: u32 },
    /// Like [`CoreError::HostKeyUnknown`], for jump host `hop` (index into
    /// the Host's jump hosts): confirm with `trust_jump_host_key`.
    #[error("jump host {hop} ({address}) key {fingerprint} is not trusted yet")]
    JumpHostKeyUnknown {
        hop: u32,
        address: String,
        fingerprint: String,
    },
    /// Like [`CoreError::HostKeyChanged`], for jump host `hop`.
    #[error("jump host {hop} ({address}) key changed from {expected} to {actual}")]
    JumpHostKeyChanged {
        hop: u32,
        address: String,
        expected: String,
        actual: String,
    },
    /// Jump host `hop` did not accept the key chosen for it.
    #[error("jump host {hop} ({address}) did not accept its SSH key")]
    JumpAuthRejected { hop: u32, address: String },
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum VaultError {
    #[error("{detail}")]
    Failed { detail: String },
    /// The key needs user authentication that did not happen.
    #[error("the key needs user authentication")]
    Locked,
    /// The requested protection cannot be set up on this device.
    #[error("biometric unavailable: {detail}")]
    BiometricUnavailable { detail: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for VaultError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed { detail: e.reason }
    }
}

impl From<VaultError> for CoreError {
    fn from(e: VaultError) -> Self {
        match e {
            VaultError::Failed { detail } => Self::Vault { detail },
            VaultError::Locked => Self::KeyLocked,
            VaultError::BiometricUnavailable { detail } => Self::BiometricUnavailable { detail },
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
