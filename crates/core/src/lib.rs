//! Aries phone core. [`AriesCore`] is the only boundary the app calls,
//! through uniffi (Kotlin today, Swift later).
//!
//! Modules: `transport` (SSH / local child process), `hc` (herdr-core
//! client), `store` (what survives a relaunch). The Host model, terminal,
//! chat, push, calls and beacon manager modules arrive with their tickets.

mod beacon;
mod calls;
mod chat;
mod chat_cache;
mod error;
mod hc;
mod host_model;
mod launch;
mod push;
mod removal;
mod send;
mod settings;
mod store;
mod structure;
mod terminal;
mod transport;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use russh::keys::ssh_key::LineEnding;
use russh::keys::{Algorithm, HashAlg, PrivateKey};
use serde::{Deserialize, Serialize};

pub use beacon::{
    ALLOWED_BEACON, BeaconArtifact, BeaconCheck, BeaconFetcher, BeaconInstall, BeaconRelease,
    BeaconSkillOutcome, BeaconSkillRoot, BeaconSource, BeaconState, BeaconUpdate, FetchError,
    Linger, pinned_release,
};
pub use calls::{
    CallAnswer, CallAnswerState, CallFeedState, CallInfo, CallListener, CallReply,
    CallReplyOutcome, CallUrgency, CallWatch,
};
pub use chat::{
    ChatEntry, ChatEntryBody, ChatQuestionOption, ChatQuestionPart, ChatSnapshot, ChatStatus,
    ChatToolStatus, ChatWatch,
};
pub use error::{AnswerProblem, CoreError, Incompatibility, VaultError};
pub use hc::{GENERATION, MIN_CORE_VERSION, REQUIRED_METHODS};
pub use host_model::{AgentStatus, BlockedReason, TerminalWatch};
pub use launch::{AgentPreset, LaunchRequest, RECENT_CWDS_KEPT, SavedLaunch};
pub use push::{
    HttpClient, HttpRequest, HttpResponse, PushContent, PushDevice, PushDisable, PushNotice,
    PushPairing, PushPolicy, PushProgress, PushStep,
};
pub use send::{HELD_AFTER, SendListener, SendOutcome};
pub use settings::{AgentView, AttachMode};
pub use structure::{HostStructure, PaneInfo, SpaceInfo, StructureProblem, TabInfo};
pub use terminal::{
    AttachOptions, CellRun, CursorPos, FRAME_INTERVAL, KeyMods, ScreenFrame, ScreenLine,
    TerminalExit, TerminalKey, TerminalUpdate, TerminalView, ViewStats,
};

use hc::HcClient;
use store::{SavedHost, SavedKey, Store};
use transport::{LocalProcess, PasswordLogin, Ssh, SshHop, Transport};

/// Id of the key [`AriesCore::ensure_key`] creates, in [`AriesCore::keys`].
pub const DEFAULT_KEY_ID: &str = "default";

uniffi::setup_scaffolding!();

/// Seals secrets at rest. On Android this is an AES-GCM key held by the
/// Android Keystore, one Keystore key per [`KeyProtection`]; tests use a
/// stand-in. Bytes sealed under one protection open only under the same one.
#[uniffi::export(with_foreign)]
pub trait KeyVault: Send + Sync {
    fn seal(&self, plaintext: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError>;
    /// A [`KeyProtection::Biometric`] open fails with [`VaultError::Locked`]
    /// unless the user authenticates for this use; it never falls back.
    fn open(&self, sealed: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError>;
}

/// The user's choice of how the app's SSH key is unlocked.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KeyProtection {
    /// Sealed by a Keystore key with no user-authentication binding, so it
    /// also works on a locked phone: Choice buttons can answer from the
    /// notification.
    Plain,
    /// Every use needs a strong biometric. Unusable in the background, so
    /// notifications show "Open app" instead of Choice buttons.
    Biometric,
}

/// The app's SSH public key, ready to paste into `authorized_keys`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PublicKeyInfo {
    /// `ssh-ed25519 AAAA... aries`
    pub openssh: String,
    /// `SHA256:...`
    pub fingerprint: String,
}

/// One of the app's SSH keys. Every key is sealed by the [`KeyVault`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct KeyInfo {
    /// [`DEFAULT_KEY_ID`] for the key `ensure_key` made.
    pub id: String,
    pub label: String,
    pub openssh: String,
    pub fingerprint: String,
}

/// A jump host (bastion) on the way to a Host's SSH endpoint.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct JumpHost {
    pub address: String,
    pub port: u16,
    pub user: String,
    /// Key to sign in with; `None` is the default key.
    pub key_id: Option<String>,
    /// Trusted Host key fingerprint of this jump host. Read-only: the core
    /// sets it through `trust_jump_host_key` and ignores it in
    /// `set_jump_hosts`.
    pub host_key: Option<String>,
}

/// A QR code as a square of modules, row by row; `true` is dark. The app
/// draws it and adds the quiet zone around it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct QrMatrix {
    pub size: u32,
    pub modules: Vec<bool>,
}

/// What a password bootstrap did to the Host's `authorized_keys`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KeyInstall {
    Added,
    /// The same key was already there; nothing was written.
    AlreadyPresent,
}

/// How to reach a Host's herdr-core.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum Endpoint {
    /// SSH with the app's key; runs `herdr-core connect` without a PTY.
    Ssh {
        address: String,
        port: u16,
        user: String,
    },
    /// A local child process speaking the protocol on stdio (tests, dev).
    Local {
        argv: Vec<String>,
        env: HashMap<String, String>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Host {
    pub id: String,
    pub name: String,
    pub endpoint: Endpoint,
    /// Trusted Host key fingerprint; `None` until the user confirms one.
    pub host_key: Option<String>,
}

/// One Terminal as listed by herdr-core.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TerminalInfo {
    /// Address such as `t7` or `machine/t7`.
    pub address: String,
    pub name: Option<String>,
    pub title: String,
    pub argv: Vec<String>,
    pub cwd: Option<String>,
    /// herdr-core's state: `live`, `finished`, ...
    pub state: String,
    /// What the Agent in it needs from the user; `None` for a program that
    /// does not report Agent status (it is shown as a plain Terminal).
    #[uniffi(default = None)]
    pub agent_status: Option<AgentStatus>,
    /// Top-level Blocked or Error: the Agent needs the user.
    #[uniffi(default = false)]
    pub needs_attention: bool,
    /// herdr-core's `turn` counter (what `herdr.seen` is compared with).
    #[uniffi(default = 0)]
    pub turn: u64,
    /// Pinned by the user on any client (shared `herdr.pinned` label).
    #[uniffi(default = false)]
    pub pinned: bool,
    /// For a Terminal on a machine the Host added with `hc machine add`, that
    /// machine's name (`box` in `box/t7`). For display only: always use the
    /// whole [`address`](Self::address) to reach the Terminal.
    #[uniffi(default = None)]
    pub machine: Option<String>,
    /// Terminal that started this one, as reported by herdr-core.
    #[uniffi(default = None)]
    pub parent: Option<String>,
}

fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("aries-core")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

/// Runs `fut` on the core's runtime, so callers may await from any executor
/// (Kotlin coroutines through uniffi, or a test's runtime).
async fn on_runtime<T: Send + 'static>(
    fut: impl std::future::Future<Output = Result<T, CoreError>> + Send + 'static,
) -> Result<T, CoreError> {
    runtime()
        .spawn(fut)
        .await
        .map_err(|e| CoreError::Protocol {
            detail: format!("core task failed: {e}"),
        })?
}

#[derive(uniffi::Object)]
pub struct AriesCore {
    store: Store,
    vault: Arc<dyn KeyVault>,
    hosts: Mutex<Vec<SavedHost>>,
    beacon_release: Mutex<BeaconRelease>,
    chat_cache: chat_cache::ChatCache,
}

#[uniffi::export]
impl AriesCore {
    /// Opens the core over `data_dir` (the app's private files dir).
    #[uniffi::constructor]
    pub fn new(data_dir: String, vault: Arc<dyn KeyVault>) -> Result<Arc<Self>, CoreError> {
        let store = Store::open(&PathBuf::from(data_dir))?;
        let saved = store.load()?;
        let chat_cache = chat_cache::ChatCache::new(
            store.root(),
            vault.clone(),
            store.read_settings().chat_cache_days,
        );
        Ok(Arc::new(Self {
            store,
            vault,
            chat_cache,
            hosts: Mutex::new(saved.hosts),
            beacon_release: Mutex::new(pinned_release()),
        }))
    }

    /// The app's SSH public key, if one was created.
    pub fn public_key(&self) -> Result<Option<PublicKeyInfo>, CoreError> {
        let Some(id) = self.store.read_identity()? else {
            return Ok(None);
        };
        Ok(Some(public_key_info(&id.public)?))
    }

    /// The app's public key line as a QR code, if a key was created.
    pub fn public_key_qr(&self) -> Result<Option<QrMatrix>, CoreError> {
        let Some(info) = self.public_key()? else {
            return Ok(None);
        };
        let code = qrcode::QrCode::with_error_correction_level(
            info.openssh.as_bytes(),
            qrcode::EcLevel::M,
        )
        .map_err(|e| CoreError::Storage {
            detail: format!("QR code: {e}"),
        })?;
        Ok(Some(QrMatrix {
            size: code.width() as u32,
            modules: code
                .to_colors()
                .into_iter()
                .map(|c| c == qrcode::Color::Dark)
                .collect(),
        }))
    }

    /// Creates the app's ed25519 SSH key unless one exists, and returns its
    /// public half. The private key is stored sealed by the [`KeyVault`].
    pub fn ensure_key(&self) -> Result<PublicKeyInfo, CoreError> {
        if let Some(info) = self.public_key()? {
            return Ok(info);
        }
        let (private, public) = generate_key("aries")?;
        let sealed = self
            .vault
            .seal(private.as_bytes().to_vec(), KeyProtection::Plain)?;
        self.store.write_identity(&store::Identity {
            protection: KeyProtection::Plain,
            sealed,
            public: public.clone(),
        })?;
        public_key_info(&public)
    }

    /// How the app's SSH keys are unlocked, or `None` before the default key
    /// exists. The policy is app-wide: it covers every key in [`AriesCore::keys`].
    pub fn key_protection(&self) -> Result<Option<KeyProtection>, CoreError> {
        Ok(self.store.read_identity()?.map(|id| id.protection))
    }

    /// Re-seals every SSH key (the default one and those from `create_key`)
    /// under `protection`; the public keys and every Host's `authorized_keys`
    /// stay valid. Entering or leaving [`KeyProtection::Biometric`] asks the
    /// user to authenticate, so call it from the foreground and off the main
    /// thread. On failure every key keeps the old protection.
    pub fn set_key_protection(&self, protection: KeyProtection) -> Result<(), CoreError> {
        let id = self.store.read_identity()?.ok_or(CoreError::NoKey)?;
        // Re-seal everything in memory first, so a vault failure writes nothing.
        let mut extras = Vec::new();
        for k in self.store.read_keys()? {
            let (old, sealed) = self.store.read_key_sealed(&k.id)?;
            if old != protection {
                let private = self.vault.open(sealed.clone(), old)?;
                let resealed = self.vault.seal(private, protection)?;
                extras.push((k.id, old, sealed, resealed));
            }
        }
        let identity = if id.protection == protection {
            None
        } else {
            let private = self.vault.open(id.sealed.clone(), id.protection)?;
            Some(self.vault.seal(private, protection)?)
        };
        // The default key, which `key_protection` reports, goes last; if any
        // write fails, put back the keys already written.
        let mut written = Vec::new();
        let result = (|| {
            for (key_id, _, _, resealed) in &extras {
                self.store.write_key_sealed(key_id, protection, resealed)?;
                written.push(key_id.clone());
            }
            if let Some(sealed) = identity {
                self.store.write_identity(&store::Identity {
                    protection,
                    sealed,
                    public: id.public.clone(),
                })?;
            }
            Ok(())
        })();
        if result.is_err() {
            for (key_id, old, sealed, _) in &extras {
                if written.contains(key_id) {
                    let _ = self.store.write_key_sealed(key_id, *old, sealed);
                }
            }
        }
        result
    }

    /// Whether a Choice for this Host can be answered from a notification
    /// without opening the app: signing in to the Host must not need a
    /// biometric unlock. When `false`, show "Open app" instead of Choices.
    ///
    /// For an SSH Host that means the Host's key and every jump host's key
    /// exist and are [`KeyProtection::Plain`].
    pub fn can_answer_in_background(&self, host_id: String) -> Result<bool, CoreError> {
        let host = self.saved_host(&host_id)?;
        if let Endpoint::Local { .. } = host.endpoint {
            return Ok(true);
        }
        let key_ids = std::iter::once(host.key_id.as_deref())
            .chain(host.jumps.iter().map(|j| j.key_id.as_deref()));
        for key_id in key_ids {
            match self.sealed_key(key_id) {
                Ok((KeyProtection::Plain, _)) => {}
                Ok(_) | Err(CoreError::NoKey | CoreError::UnknownKey { .. }) => return Ok(false),
                Err(e) => return Err(e),
            }
        }
        Ok(true)
    }

    /// Registers a Host. Connecting is a separate step.
    pub fn add_host(&self, name: String, endpoint: Endpoint) -> Result<Host, CoreError> {
        let saved = SavedHost {
            id: new_id(),
            name,
            endpoint,
            host_key: None,
            jumps: Vec::new(),
            key_id: None,
        };
        self.update_hosts(|hosts| hosts.push(saved.clone()))?;
        Ok(to_host(&saved))
    }

    pub fn hosts(&self) -> Vec<Host> {
        self.hosts
            .lock()
            .expect("hosts lock")
            .iter()
            .map(to_host)
            .collect()
    }

    /// Trusts `fingerprint` as `host_id`'s Host key, after the user compared
    /// it with the one `connect` reported in [`CoreError::HostKeyUnknown`].
    pub fn trust_host_key(&self, host_id: String, fingerprint: String) -> Result<(), CoreError> {
        let mut found = false;
        self.update_hosts(|hosts| {
            if let Some(h) = hosts.iter_mut().find(|h| h.id == host_id) {
                h.host_key = Some(fingerprint.clone());
                found = true;
            }
        })?;
        if found {
            Ok(())
        } else {
            Err(CoreError::UnknownHost { id: host_id })
        }
    }

    /// Creates another ed25519 SSH key, for jump hosts or Hosts that need an
    /// account of their own. It is sealed under the current
    /// [`AriesCore::key_protection`] (`Plain` before the default key exists).
    pub fn create_key(&self, label: String) -> Result<KeyInfo, CoreError> {
        let protection = self.key_protection()?.unwrap_or(KeyProtection::Plain);
        let (private, public) = generate_key(&label)?;
        let sealed = self.vault.seal(private.into_bytes(), protection)?;
        let saved = SavedKey {
            id: new_id(),
            label,
            public,
        };
        self.store.add_key(&saved, protection, &sealed)?;
        key_info(&saved)
    }

    /// Every SSH key: the default one first (once created), then the others
    /// in creation order.
    pub fn keys(&self) -> Result<Vec<KeyInfo>, CoreError> {
        let mut keys = Vec::new();
        if let Some(info) = self.public_key()? {
            keys.push(KeyInfo {
                id: DEFAULT_KEY_ID.into(),
                label: String::new(),
                openssh: info.openssh,
                fingerprint: info.fingerprint,
            });
        }
        for saved in self.store.read_keys()? {
            keys.push(key_info(&saved)?);
        }
        Ok(keys)
    }

    /// The Host's jump hosts, in the order they are passed through.
    pub fn jump_hosts(&self, host_id: String) -> Result<Vec<JumpHost>, CoreError> {
        Ok(self.saved_host(&host_id)?.jumps)
    }

    /// Replaces the Host's jump hosts. A hop keeps its trusted fingerprint
    /// only if the previous list had a hop at the same address and port; the
    /// `host_key` passed in is ignored, so trust is only ever set by
    /// `trust_jump_host_key`.
    pub fn set_jump_hosts(&self, host_id: String, jumps: Vec<JumpHost>) -> Result<(), CoreError> {
        for j in &jumps {
            self.check_key_id(j.key_id.as_deref())?;
        }
        self.update_host(&host_id, |h| {
            let old = std::mem::take(&mut h.jumps);
            h.jumps = jumps
                .into_iter()
                .map(|mut j| {
                    j.host_key = old
                        .iter()
                        .find(|o| o.address == j.address && o.port == j.port)
                        .and_then(|o| o.host_key.clone());
                    j
                })
                .collect();
        })
    }

    /// Trusts `fingerprint` for jump host `hop` (index into `jump_hosts`),
    /// after the user compared it with the one `connect` reported in
    /// [`CoreError::JumpHostKeyUnknown`].
    pub fn trust_jump_host_key(
        &self,
        host_id: String,
        hop: u32,
        fingerprint: String,
    ) -> Result<(), CoreError> {
        let mut found = false;
        self.update_host(&host_id, |h| {
            if let Some(j) = h.jumps.get_mut(hop as usize) {
                j.host_key = Some(fingerprint);
                found = true;
            }
        })?;
        if found {
            Ok(())
        } else {
            Err(CoreError::UnknownJumpHost { hop })
        }
    }

    /// The key the Host itself is signed in with; `None` is the default key.
    pub fn host_ssh_key(&self, host_id: String) -> Result<Option<String>, CoreError> {
        Ok(self.saved_host(&host_id)?.key_id)
    }

    pub fn set_host_ssh_key(
        &self,
        host_id: String,
        key_id: Option<String>,
    ) -> Result<(), CoreError> {
        self.check_key_id(key_id.as_deref())?;
        self.update_host(&host_id, |h| h.key_id = key_id)
    }

    /// Logs in to an SSH Host once with `password` and adds the public half
    /// of the Host's SSH key (see `host_ssh_key`) to its
    /// `~/.ssh/authorized_keys`, so later connections use the key. The
    /// password is not stored. The Host is reached through its jump hosts,
    /// which sign in with their own keys. Host keys are checked like
    /// `connect` does, so the first call may return
    /// [`CoreError::HostKeyUnknown`] or [`CoreError::JumpHostKeyUnknown`].
    pub async fn install_key_with_password(
        &self,
        host_id: String,
        password: String,
    ) -> Result<KeyInstall, CoreError> {
        let host = self.saved_host(&host_id)?;
        let Endpoint::Ssh {
            address,
            port,
            user,
        } = host.endpoint
        else {
            return Err(CoreError::KeyInstallFailed {
                detail: "this Host is not reached over SSH".into(),
            });
        };
        let public = match host.key_id.as_deref() {
            None | Some(DEFAULT_KEY_ID) => self.ensure_key()?.openssh,
            Some(id) => {
                self.keys()?
                    .into_iter()
                    .find(|k| k.id == id)
                    .ok_or_else(|| CoreError::UnknownKey { id: id.to_owned() })?
                    .openssh
            }
        };
        let login = PasswordLogin {
            address,
            port,
            user,
            password,
            trusted: host.host_key,
            jumps: self.ssh_hops(host.jumps)?,
        };
        on_runtime(async move { login.install_key(&public).await }).await
    }

    /// Removes a Host after the user confirmed it: runs every cleanup step
    /// (see `removal.rs` for how tickets add theirs), then forgets the
    /// Host's connection settings and trusted Host key. If a step fails the
    /// Host stays registered and removal can be retried.
    pub fn remove_host(&self, host_id: String) -> Result<(), CoreError> {
        let host = self
            .hosts
            .lock()
            .expect("hosts lock")
            .iter()
            .find(|h| h.id == host_id)
            .cloned()
            .ok_or_else(|| CoreError::UnknownHost {
                id: host_id.clone(),
            })?;
        removal::run(&host, &self.store)?;
        self.update_hosts(|hosts| hosts.retain(|h| h.id != host_id))
    }

    /// Connects to a Host and checks its herdr-core is compatible.
    pub async fn connect(&self, host_id: String) -> Result<Arc<HostConnection>, CoreError> {
        let host = self.saved_host(&host_id)?;
        let transport: Box<dyn Transport> = match host.endpoint {
            Endpoint::Local { argv, env } => Box::new(LocalProcess { argv, env }),
            Endpoint::Ssh {
                address,
                port,
                user,
            } => Box::new(Ssh {
                address,
                port,
                user,
                key: self.private_key(host.key_id.as_deref())?,
                trusted: host.host_key,
                jumps: self.ssh_hops(host.jumps)?,
            }),
        };
        let client =
            on_runtime(async move { HcClient::handshake(transport.open().await?).await }).await?;
        Ok(Arc::new(HostConnection {
            client: Arc::new(client),
            names: Default::default(),
            recent: launch::RecentCwds::in_host_dir(self.store.host_dir(&host.id)),
            saved: launch::SavedLaunches::in_host_dir(self.store.host_dir(&host.id)),
        }))
    }
}

impl AriesCore {
    /// The jump hosts with their private keys, ready to connect through.
    fn ssh_hops(&self, jumps: Vec<JumpHost>) -> Result<Vec<SshHop>, CoreError> {
        jumps
            .into_iter()
            .map(|j| {
                Ok(SshHop {
                    key: self.private_key(j.key_id.as_deref())?,
                    address: j.address,
                    port: j.port,
                    user: j.user,
                    trusted: j.host_key,
                })
            })
            .collect()
    }

    /// The private half of `key_id` (`None` or [`DEFAULT_KEY_ID`]: the
    /// default key).
    fn private_key(&self, key_id: Option<&str>) -> Result<PrivateKey, CoreError> {
        let (protection, sealed) = self.sealed_key(key_id)?;
        let pem = self.vault.open(sealed, protection)?;
        let pem = String::from_utf8(pem).map_err(|e| CoreError::Vault {
            detail: e.to_string(),
        })?;
        PrivateKey::from_openssh(&pem).map_err(|e| CoreError::Vault {
            detail: e.to_string(),
        })
    }

    /// The sealed private half of `key_id` and the protection it is under.
    fn sealed_key(&self, key_id: Option<&str>) -> Result<(KeyProtection, Vec<u8>), CoreError> {
        match key_id {
            None | Some(DEFAULT_KEY_ID) => {
                let id = self.store.read_identity()?.ok_or(CoreError::NoKey)?;
                Ok((id.protection, id.sealed))
            }
            Some(id) => {
                self.check_key_id(Some(id))?;
                self.store.read_key_sealed(id)
            }
        }
    }

    fn check_key_id(&self, key_id: Option<&str>) -> Result<(), CoreError> {
        match key_id {
            None | Some(DEFAULT_KEY_ID) => Ok(()),
            Some(id) if self.store.read_keys()?.iter().any(|k| k.id == id) => Ok(()),
            Some(id) => Err(CoreError::UnknownKey { id: id.to_owned() }),
        }
    }

    fn saved_host(&self, host_id: &str) -> Result<SavedHost, CoreError> {
        self.hosts
            .lock()
            .expect("hosts lock")
            .iter()
            .find(|h| h.id == host_id)
            .cloned()
            .ok_or_else(|| CoreError::UnknownHost {
                id: host_id.to_owned(),
            })
    }

    fn update_host(&self, host_id: &str, f: impl FnOnce(&mut SavedHost)) -> Result<(), CoreError> {
        let mut found = false;
        self.update_hosts(|hosts| {
            if let Some(h) = hosts.iter_mut().find(|h| h.id == host_id) {
                f(h);
                found = true;
            }
        })?;
        if found {
            Ok(())
        } else {
            Err(CoreError::UnknownHost {
                id: host_id.to_owned(),
            })
        }
    }

    fn update_hosts(&self, f: impl FnOnce(&mut Vec<SavedHost>)) -> Result<(), CoreError> {
        let mut hosts = self.hosts.lock().expect("hosts lock");
        let mut next = hosts.clone();
        f(&mut next);
        self.store.save(&store::Saved {
            hosts: next.clone(),
        })?;
        *hosts = next;
        Ok(())
    }
}

/// Beacon management (`beacon` module).
#[uniffi::export]
impl AriesCore {
    /// The Beacon release this app installs.
    pub fn beacon_release(&self) -> BeaconRelease {
        self.beacon_release.lock().expect("release lock").clone()
    }

    /// Installs (or reinstalls) the Beacon on a Linux Host over SSH. The
    /// phone downloads the binary with `fetcher`; it is installed only if it
    /// matches the checksum pinned in the app.
    pub async fn install_beacon(
        &self,
        host_id: String,
        fetcher: Arc<dyn BeaconFetcher>,
    ) -> Result<BeaconInstall, CoreError> {
        let ssh = self.beacon_ssh(&host_id)?;
        let release = self.beacon_release();
        on_runtime(async move { beacon::install(&ssh, &release, fetcher).await }).await
    }

    /// What Beacon the Host has. Call it when connecting: a Beacon outside
    /// [`ALLOWED_BEACON`] reports `supported: false`.
    pub async fn beacon_status(&self, host_id: String) -> Result<BeaconState, CoreError> {
        let ssh = self.beacon_ssh(&host_id)?;
        on_runtime(async move { beacon::status(&ssh).await }).await
    }

    /// Stops and removes the Beacon service and binary. Call records stay.
    pub async fn uninstall_beacon(&self, host_id: String) -> Result<(), CoreError> {
        let ssh = self.beacon_ssh(&host_id)?;
        on_runtime(async move { beacon::uninstall(&ssh).await }).await?;
        // The Beacon revoked its pairing; revoke it from the phone too, in
        // case the Host could not reach the Relay.
        push::queue_revocation(&self.store, &host_id)
    }
}

/// Push (`push` module): pairing a Host with this phone through the Relay.
#[uniffi::export]
impl AriesCore {
    /// Turns push on for a Host whose Beacon is installed: registers this
    /// phone (`device`) on the Relay at `relay_url`, exchanges keys with the
    /// Beacon over SSH and has it send a test notification. `progress`
    /// hears each step as it starts; a failure names its step
    /// ([`CoreError::PushFailed`]). `relay_url` must be `https://` (or
    /// `http://` to this device's own address); a bad URL fails at
    /// [`PushStep::Register`] before anything is sent.
    ///
    /// Turning it on again, for example with another (self-hosted) Relay,
    /// replaces the pairing: only once the new one is paired is the old one
    /// deleted on its Relay ([`PushStep::Unpair`], queued if unreachable).
    /// A failed change keeps the old pairing working.
    pub async fn enable_push(
        &self,
        host_id: String,
        relay_url: String,
        device: PushDevice,
        http: Arc<dyn HttpClient>,
        progress: Arc<dyn PushProgress>,
    ) -> Result<PushPairing, CoreError> {
        let e = push::Enable {
            ssh: self.beacon_ssh(&host_id)?,
            host_id,
            relay_url,
            device,
            store: self.store.clone(),
            vault: self.vault.clone(),
            http,
            progress,
        };
        on_runtime(push::enable(e)).await
    }

    /// The Host's push pairing, if push is on.
    pub fn push_pairing(&self, host_id: String) -> Result<Option<PushPairing>, CoreError> {
        self.saved_host(&host_id)?;
        Ok(push::relay_url_of(&self.store, &host_id)?.map(|relay_url| PushPairing { relay_url }))
    }

    /// Has the paired Beacon send another test notification.
    pub async fn send_test_push(&self, host_id: String) -> Result<(), CoreError> {
        let ssh = self.beacon_ssh(&host_id)?;
        let (store, vault) = (self.store.clone(), self.vault.clone());
        on_runtime(async move {
            push::complete_pending(&store, vault.as_ref(), &host_id, &ssh).await?;
            push::test(&ssh).await
        })
        .await
    }

    /// Which Agent statuses (and subagent Calls) the Host's Beacon pushes.
    /// The policy lives on the Host, so this asks it over SSH; it applies
    /// whether or not push is on.
    pub async fn push_policy(&self, host_id: String) -> Result<PushPolicy, CoreError> {
        let ssh = self.beacon_ssh(&host_id)?;
        on_runtime(async move { push::policy(&ssh).await }).await
    }

    /// Changes the Host's push policy over SSH; returns what the Beacon
    /// saved. It takes effect for the next status change or Call.
    pub async fn set_push_policy(
        &self,
        host_id: String,
        policy: PushPolicy,
    ) -> Result<PushPolicy, CoreError> {
        let ssh = self.beacon_ssh(&host_id)?;
        on_runtime(async move { push::set_policy(&ssh, policy).await }).await
    }

    /// Turns push off: deletes the pairing on the Relay (from the phone),
    /// then on the Host (best effort, see [`PushDisable::host_cleared`]).
    /// If the Relay cannot be reached nothing changes and the error says so.
    pub async fn disable_push(
        &self,
        host_id: String,
        http: Arc<dyn HttpClient>,
    ) -> Result<PushDisable, CoreError> {
        self.saved_host(&host_id)?;
        let ssh = self.beacon_ssh(&host_id).ok();
        let (store, vault) = (self.store.clone(), self.vault.clone());
        on_runtime(push::disable(host_id, ssh, store, vault, http)).await
    }

    /// Tells the Relay this phone's new push token (FCM rotates it), for
    /// every paired Host.
    pub async fn update_push_token(
        &self,
        device: PushDevice,
        http: Arc<dyn HttpClient>,
    ) -> Result<(), CoreError> {
        let paired = self
            .hosts()
            .into_iter()
            .flat_map(|h| {
                push::pairings_of(&self.store, self.vault.as_ref(), &h.id).unwrap_or_default()
            })
            .collect();
        on_runtime(push::update_token(paired, device, http)).await
    }

    /// Sends the Relay revocations queued by Host or Beacon removal. Call it
    /// after those and at app start. Returns how many are still queued
    /// (the Relay was unreachable).
    pub async fn flush_push_revocations(&self, http: Arc<dyn HttpClient>) -> u32 {
        let (store, vault) = (self.store.clone(), self.vault.clone());
        runtime()
            .spawn(push::flush(store, vault, http))
            .await
            .unwrap_or(0)
    }

    /// Opens a push envelope as it arrived (FCM data `e`, base64-decoded).
    /// Works while the phone is locked. Rejects tampered envelopes, unknown
    /// versions and envelopes from no paired Host.
    pub fn open_push(&self, envelope: Vec<u8>) -> Result<PushNotice, CoreError> {
        let paired = self.hosts().into_iter().flat_map(|h| {
            push::pairings_of(&self.store, self.vault.as_ref(), &h.id)
                .unwrap_or_default()
                .into_iter()
                .map(move |p| (h.id.clone(), h.name.clone(), p))
        });
        push::open(&envelope, paired)
    }

    /// Call when connecting: reads the Beacon and, if it is older than the
    /// pinned release or outside [`ALLOWED_BEACON`], installs the pinned one
    /// when [`AriesCore::beacon_auto_update`] is on, or only reports
    /// [`BeaconUpdate::Needed`] when it is off.
    pub async fn check_beacon(
        &self,
        host_id: String,
        fetcher: Arc<dyn BeaconFetcher>,
    ) -> Result<BeaconCheck, CoreError> {
        let ssh = self.beacon_ssh(&host_id)?;
        let release = self.beacon_release();
        let auto = self.beacon_auto_update();
        on_runtime(async move { beacon::check(&ssh, &release, auto, fetcher).await }).await
    }

    /// Whether connecting updates an out-of-date Beacon by itself (default on).
    pub fn beacon_auto_update(&self) -> bool {
        self.store.read_settings().beacon_auto_update
    }

    pub fn set_beacon_auto_update(&self, on: bool) -> Result<(), CoreError> {
        let mut settings = self.store.read_settings();
        settings.beacon_auto_update = on;
        self.store.write_settings(&settings)
    }
}

#[uniffi::export]
impl AriesCore {
    /// Follows the Chat of the Agent in `terminal` (address or name) through
    /// the Host's Beacon. Pass the `cursor` of an earlier snapshot to resume
    /// after it. Reconnects by itself; call `next` for each change.
    pub fn watch_chat(
        &self,
        host_id: String,
        terminal: String,
        cursor: Option<String>,
    ) -> Result<Arc<ChatWatch>, CoreError> {
        self.watch_chat_retrying(host_id, terminal, cursor, std::time::Duration::from_secs(1))
    }

    /// The cached Chat of `terminal`, without reaching the Host. `None`
    /// when nothing is cached (or it expired).
    pub async fn cached_chat(
        &self,
        host_id: String,
        terminal: String,
    ) -> Result<Option<ChatSnapshot>, CoreError> {
        self.saved_host(&host_id)?;
        let cache = self.chat_cache.clone();
        on_runtime(async move {
            let cached = tokio::task::spawn_blocking(move || cache.load(&host_id, &terminal))
                .await
                .map_err(|e| CoreError::Protocol {
                    detail: format!("cache task failed: {e}"),
                })??;
            Ok(cached.map(|c| {
                let mut model = chat::ChatModel::new(None);
                model.seed(c);
                let mut s = model.snapshot();
                s.status = ChatStatus::Reconnecting;
                s
            }))
        })
        .await
    }

    /// Deletes every cached Chat of the Host.
    pub async fn clear_chat_cache(&self, host_id: String) -> Result<(), CoreError> {
        self.saved_host(&host_id)?;
        let cache = self.chat_cache.clone();
        on_runtime(async move {
            tokio::task::spawn_blocking(move || cache.clear(&host_id))
                .await
                .map_err(|e| CoreError::Protocol {
                    detail: format!("cache task failed: {e}"),
                })?
        })
        .await
    }

    /// Days a cached Chat is kept after its last update (default 30).
    pub fn chat_cache_retention_days(&self) -> u32 {
        self.chat_cache.retention_days()
    }

    /// Sets the retention period (at least one day); older Chats are
    /// deleted on the next cache use.
    pub fn set_chat_cache_retention_days(&self, days: u32) -> Result<(), CoreError> {
        let days = days.max(1);
        let mut settings = self.store.read_settings();
        settings.chat_cache_days = days;
        self.store.write_settings(&settings)?;
        self.chat_cache.set_retention_days(days);
        Ok(())
    }

    /// Answers the open question `question_id` (a
    /// [`ChatEntryBody::Question`] entry id) of the Agent in `terminal`:
    /// `picks` holds, per question in order, the 0-based indexes of the
    /// chosen options. The Host's Beacon checks the Agent still shows that
    /// question and presses its keys. Not retried; fails with
    /// [`CoreError::AnswerRefused`] when nothing was (or may not have been)
    /// pressed.
    pub async fn answer_question(
        &self,
        host_id: String,
        terminal: String,
        question_id: String,
        picks: Vec<Vec<u32>>,
    ) -> Result<(), CoreError> {
        let host = self.chat_host(&host_id)?;
        let answer = aries_protocol::answer::Answer {
            question: question_id,
            picks: (picks.into_iter())
                .map(|p| p.into_iter().map(|n| n as usize).collect())
                .collect(),
        };
        on_runtime(async move { chat::answer(&host, &terminal, &answer).await }).await
    }
}

/// Calls (`calls` module).
#[uniffi::export]
impl AriesCore {
    /// The Host's whole Call history, newest first, as its Beacon keeps it.
    pub async fn calls(&self, host_id: String) -> Result<Vec<CallInfo>, CoreError> {
        let runner = self.chat_host(&host_id)?;
        on_runtime(async move { calls::history(&runner, &host_id).await }).await
    }

    /// Follows the Host's Call history: `listener` gets the whole list after
    /// the first sync and each change, and each Call that arrives while
    /// watching once. Reconnects by itself (reported through `on_state`)
    /// until `stop`. Uses its own SSH connection, not a [`HostConnection`].
    /// Opening the Host's key may ask for a biometric unlock, so call it
    /// off the UI thread.
    pub async fn watch_calls(
        &self,
        host_id: String,
        listener: Arc<dyn CallListener>,
    ) -> Result<Arc<CallWatch>, CoreError> {
        let runner = self.chat_host(&host_id)?;
        Ok(Arc::new(calls::watch(runner, host_id, listener)))
    }

    /// Answers Call `call_id` with its Choice `choice` (0-based): the
    /// Host's Beacon types that Choice into the calling Terminal, then
    /// Enter, at most once ever (it claims the Call first).
    ///
    /// A plain core call with no UI state, so it also runs from a
    /// notification action in the background (ticket 36). How it ends:
    /// - `Err(..)`: the reply never started on the Host (no key, key locked,
    ///   Host unreachable, key rejected, Host key unknown or changed).
    ///   Reaching and signing in to the Host (every jump host included)
    ///   that takes over 15 s also ends here as `Unreachable`.
    ///   Nothing was claimed or typed; trying again is safe.
    /// - `Ok` with [`CallReplyOutcome::Unknown`]: the Beacon was started but
    ///   its result was lost (connection dropped, timed out). It may have
    ///   been delivered; the Call history shows. Trying again is still safe:
    ///   the claim makes a second try `Answered` if the first got through.
    /// - Any other `Ok`: what the Beacon did and recorded.
    pub async fn reply_call(
        &self,
        host_id: String,
        call_id: String,
        choice: u32,
    ) -> Result<CallReply, CoreError> {
        self.reply_call_within(host_id, call_id, choice, calls::REPLY_SIGN_IN)
            .await
    }
}

impl AriesCore {
    /// [`AriesCore::reply_call`] with a custom limit for reaching and
    /// signing in to the Host (tests).
    pub async fn reply_call_within(
        &self,
        host_id: String,
        call_id: String,
        choice: u32,
        sign_in: std::time::Duration,
    ) -> Result<CallReply, CoreError> {
        let runner = self.chat_host(&host_id)?;
        on_runtime(async move { calls::reply(&runner, &call_id, choice, sign_in).await }).await
    }

    /// [`AriesCore::watch_chat`] with a custom first retry delay (tests).
    pub fn watch_chat_retrying(
        &self,
        host_id: String,
        terminal: String,
        cursor: Option<String>,
        retry: std::time::Duration,
    ) -> Result<Arc<ChatWatch>, CoreError> {
        let host = self.chat_host(&host_id)?;
        let cache = chat::CacheSlot {
            cache: self.chat_cache.clone(),
            host: host_id,
        };
        Ok(Arc::new(ChatWatch::start(
            host,
            terminal,
            cursor,
            Some(cache),
            retry,
        )))
    }

    /// Moves the Chat cache's clock forward (tests of the retention period).
    pub fn shift_clock(&self, by: std::time::Duration) {
        self.chat_cache.shift_clock(by);
    }

    fn chat_host(&self, host_id: &str) -> Result<chat::ChatHost, CoreError> {
        let host = self.saved_host(host_id)?;
        Ok(match host.endpoint {
            Endpoint::Local { env, .. } => chat::ChatHost::Local(env),
            Endpoint::Ssh { .. } => {
                let first = self.beacon_ssh(host_id)?;
                let store = self.store.clone();
                let vault = self.vault.clone();
                let host_id = host_id.to_owned();
                chat::ChatHost::Ssh {
                    first: Mutex::new(Some(Box::new(first))),
                    reopen: Arc::new(move || {
                        let host = store
                            .load()?
                            .hosts
                            .into_iter()
                            .find(|h| h.id == host_id)
                            .ok_or_else(|| CoreError::UnknownHost {
                                id: host_id.clone(),
                            })?;
                        ssh_for_saved_host(&store, vault.as_ref(), host)
                    }),
                }
            }
        })
    }

    /// Replaces the pinned Beacon release. For tests and development
    /// builds; not exposed to the app.
    pub fn set_beacon_release(&self, release: BeaconRelease) {
        *self.beacon_release.lock().expect("release lock") = release;
    }

    fn beacon_ssh(&self, host_id: &str) -> Result<Ssh, CoreError> {
        let host = self.saved_host(host_id)?;
        ssh_for_saved_host(&self.store, self.vault.as_ref(), host)
    }
}

/// Rebuild credentials for each SSH authentication, including jump hosts.
/// Watches keep sealed credentials, never a reusable unlocked private key.
fn ssh_for_saved_host(
    store: &Store,
    vault: &dyn KeyVault,
    host: SavedHost,
) -> Result<Ssh, CoreError> {
    let key = |id: Option<&str>| -> Result<PrivateKey, CoreError> {
        let (protection, sealed) = match id {
            None | Some(DEFAULT_KEY_ID) => {
                let identity = store.read_identity()?.ok_or(CoreError::NoKey)?;
                (identity.protection, identity.sealed)
            }
            Some(id) => {
                if !store.read_keys()?.iter().any(|k| k.id == id) {
                    return Err(CoreError::UnknownKey { id: id.to_owned() });
                }
                store.read_key_sealed(id)?
            }
        };
        let pem = vault.open(sealed, protection)?;
        PrivateKey::from_openssh(pem).map_err(|e| CoreError::Vault {
            detail: e.to_string(),
        })
    };
    match host.endpoint {
        Endpoint::Ssh {
            address,
            port,
            user,
        } => Ok(Ssh {
            address,
            port,
            user,
            key: key(host.key_id.as_deref())?,
            trusted: host.host_key,
            jumps: host
                .jumps
                .into_iter()
                .map(|j| {
                    Ok(SshHop {
                        key: key(j.key_id.as_deref())?,
                        address: j.address,
                        port: j.port,
                        user: j.user,
                        trusted: j.host_key,
                    })
                })
                .collect::<Result<_, CoreError>>()?,
        }),
        Endpoint::Local { .. } => Err(CoreError::BeaconNeedsSsh),
    }
}

/// A live connection to one Host's herdr-core.
#[derive(uniffi::Object)]
pub struct HostConnection {
    client: Arc<HcClient>,
    names: Arc<tokio::sync::Mutex<structure::Names>>,
    recent: launch::RecentCwds,
    saved: launch::SavedLaunches,
}

#[uniffi::export]
impl HostConnection {
    /// herdr-core version the Host reported in `hello`.
    pub fn core_version(&self) -> String {
        self.client.core_version().to_owned()
    }

    /// The Host's Terminals (live ones, as `terminal.list` returns them).
    pub async fn terminals(&self) -> Result<Vec<TerminalInfo>, CoreError> {
        let client = self.client.clone();
        let result =
            on_runtime(async move { client.call("terminal.list", serde_json::json!({})).await })
                .await?;
        let list = result["terminals"].as_array().cloned().unwrap_or_default();
        Ok(list.iter().map(terminal_info).collect())
    }

    /// Attaches to the Terminal at `address` (`t7`, `machine/t7`) and
    /// streams its screen. With `observe` the desktop keeps its size and the
    /// view follows the Terminal's grid when the desktop resizes it.
    pub async fn attach_terminal(
        &self,
        address: String,
        options: AttachOptions,
    ) -> Result<Arc<TerminalView>, CoreError> {
        let client = self.client.clone();
        on_runtime(async move {
            TerminalView::attach(client, address, options)
                .await
                .map(Arc::new)
        })
        .await
    }

    /// Watches the Host's Terminals and their Agent status live.
    pub async fn watch_terminals(&self) -> Result<Arc<TerminalWatch>, CoreError> {
        let client = self.client.clone();
        on_runtime(async move { TerminalWatch::start(client).await.map(Arc::new) }).await
    }

    /// The Host's Spaces, Tabs and Panes as herdr-app arranges them, read
    /// from its `herdr-app` document (never written). Falls back to
    /// `herdr.space` / `herdr.tab` labels when the document is unusable.
    pub async fn structure(&self) -> Result<HostStructure, CoreError> {
        let client = self.client.clone();
        let names = self.names.clone();
        on_runtime(async move { read_structure(&client, &names).await }).await
    }

    /// Delivers the structure now and again whenever the desktop changes the
    /// document or Terminals come, go or are relabelled. Ends (with
    /// `on_closed`) when the connection does or `stop` is called.
    pub async fn watch_structure(
        &self,
        listener: Arc<dyn StructureListener>,
    ) -> Result<Arc<StructureWatch>, CoreError> {
        let client = self.client.clone();
        let names = self.names.clone();
        let task = on_runtime(async move {
            // Subscribe before reading, so no change falls in between.
            let (_, mut events) = client.subscribe_events(serde_json::json!({})).await?;
            listener.on_structure(read_structure(&client, &names).await?);
            Ok(tokio::spawn(async move {
                while let Some(msg) = events.recv().await {
                    let mut changed = changes_structure(&msg);
                    while let Ok(more) = events.try_recv() {
                        changed |= changes_structure(&more);
                    }
                    if !changed {
                        continue;
                    }
                    match read_structure(&client, &names).await {
                        Ok(s) => listener.on_structure(s),
                        Err(_) => break,
                    }
                }
                listener.on_closed();
            }))
        })
        .await?;
        Ok(Arc::new(StructureWatch { task }))
    }

    /// Marks the Agent at `address` seen up to `turn` (the turn whose Done the
    /// user confirmed) in the shared `herdr.seen` label, so every client,
    /// desktop included, shows it Idle. Never moves `herdr.seen` backwards.
    /// `address` is used whole (`t7` or `machine/t7`). Returns the Terminal
    /// as it is after the change.
    pub async fn mark_seen(&self, address: String, turn: u64) -> Result<TerminalInfo, CoreError> {
        let client = self.client.clone();
        on_runtime(async move {
            let now = client
                .call("terminal.get", serde_json::json!({ "terminal": address }))
                .await?;
            if host_model::seen_turn(&now) >= turn {
                return Ok(terminal_info(&now));
            }
            let labels =
                serde_json::json!({ aries_protocol::agent_status::SEEN_LABEL: turn.to_string() });
            set_labels(&client, &address, labels).await
        })
        .await
    }

    /// Pins or unpins the Agent at `address` for every client through the
    /// shared `herdr.pinned` label. Returns the Terminal after the change.
    pub async fn set_pinned(
        &self,
        address: String,
        pinned: bool,
    ) -> Result<TerminalInfo, CoreError> {
        let client = self.client.clone();
        // An empty value removes the label.
        let value = if pinned { "1" } else { "" };
        let labels = serde_json::json!({ aries_protocol::agent_status::PINNED_LABEL: value });
        on_runtime(async move { set_labels(&client, &address, labels).await }).await
    }

    /// Starts a new Terminal running `request.command` (see `launch.rs`),
    /// placed in the requested Space/Tab by label; the document is never
    /// written. herdr-core's refusal (`spawn_failed` for a missing program or
    /// cwd, ...) comes back as [`CoreError::Rpc`] with its `kind`. On success
    /// the cwd joins [`HostConnection::recent_cwds`].
    pub async fn launch(&self, request: LaunchRequest) -> Result<TerminalInfo, CoreError> {
        let client = self.client.clone();
        let params = launch::run_params(&request);
        let result = on_runtime(async move { client.call("terminal.run", params).await }).await?;
        if let Some(cwd) = launch::cwd_to_remember(&request) {
            // The Terminal runs either way; a full disk only loses the hint.
            let _ = self.recent.remember(cwd);
        }
        Ok(terminal_info(&result))
    }

    /// Working directories recently launched in on this Host, newest first,
    /// at most [`RECENT_CWDS_KEPT`]. Kept on the phone, per Host.
    pub fn recent_cwds(&self) -> Vec<String> {
        self.recent.list()
    }

    /// The launch configs saved for this Host, oldest first. Kept on the
    /// phone, per Host; run one with [`HostConnection::launch`] on its
    /// `request`.
    pub fn saved_launches(&self) -> Vec<SavedLaunch> {
        self.saved.list()
    }

    /// Saves `launch` under its (trimmed) name: a new one when its `id` is
    /// empty, else replaces the one with that id. Returns it as stored.
    pub fn save_launch(&self, launch: SavedLaunch) -> Result<SavedLaunch, CoreError> {
        self.saved.save(launch)
    }

    /// Deletes the saved launch `id`; succeeds if it is already gone.
    pub fn delete_saved_launch(&self, id: String) -> Result<(), CoreError> {
        self.saved.delete(&id)
    }

    /// Types `text` into the Agent's Terminal at `address` and submits it, as
    /// a Chat message. Never forced: a Blocked Agent refuses it
    /// ([`SendOutcome::Blocked`]). `listener` hears when the send is held
    /// because a person at the Host is typing.
    pub async fn send_message(
        &self,
        address: String,
        text: String,
        listener: Arc<dyn SendListener>,
    ) -> Result<SendOutcome, CoreError> {
        let client = self.client.clone();
        on_runtime(async move { send::send(&client, &address, &text, || listener.on_held()).await })
            .await
    }

    pub async fn disconnect(&self) {
        let client = self.client.clone();
        let _ = on_runtime(async move {
            client.close().await;
            Ok(())
        })
        .await;
    }
}

/// Receives a Host's structure from [`HostConnection::watch_structure`].
#[uniffi::export(with_foreign)]
pub trait StructureListener: Send + Sync {
    fn on_structure(&self, structure: HostStructure);
    /// No more updates: the connection ended or the watch was stopped.
    fn on_closed(&self);
}

/// A running [`HostConnection::watch_structure`].
#[derive(uniffi::Object)]
pub struct StructureWatch {
    task: tokio::task::JoinHandle<()>,
}

#[uniffi::export]
impl StructureWatch {
    pub fn stop(&self) {
        self.task.abort();
    }
}

impl Drop for StructureWatch {
    fn drop(&mut self) {
        self.task.abort();
    }
}

async fn set_labels(
    client: &HcClient,
    address: &str,
    labels: serde_json::Value,
) -> Result<TerminalInfo, CoreError> {
    let t = client
        .call(
            "terminal.label",
            serde_json::json!({ "terminal": address, "labels": labels }),
        )
        .await?;
    Ok(terminal_info(&t))
}

async fn read_structure(
    client: &HcClient,
    names: &tokio::sync::Mutex<structure::Names>,
) -> Result<HostStructure, CoreError> {
    let doc = client
        .call(
            "doc.get",
            serde_json::json!({ "name": structure::DOCUMENT }),
        )
        .await?;
    let list = client.call("terminal.list", serde_json::json!({})).await?;
    let terminals = list["terminals"].as_array().cloned().unwrap_or_default();
    let mut structure = structure::build(&doc, &terminals);
    names.lock().await.apply(client, &mut structure).await;
    Ok(structure)
}

/// Whether an event-stream message can change what `read_structure` returns.
fn changes_structure(msg: &serde_json::Value) -> bool {
    let params = &msg["params"];
    match msg["method"].as_str() {
        Some("missed") => true,
        Some("event") => match params["type"].as_str() {
            Some("doc") => {
                params["data"]["name"] == structure::DOCUMENT && params.get("machine").is_none()
            }
            Some("created" | "removed" | "restarted" | "finished" | "labels" | "title") => true,
            _ => false,
        },
        _ => false,
    }
}

pub(crate) fn terminal_info(v: &serde_json::Value) -> TerminalInfo {
    let s = |k: &str| v[k].as_str().map(str::to_owned);
    let (agent_status, needs_attention) = host_model::status_of(v);
    let address = s("address").unwrap_or_default();
    TerminalInfo {
        machine: address.rsplit_once('/').map(|(m, _)| m.to_owned()),
        pinned: host_model::pinned(v),
        address,
        parent: s("parent"),
        name: s("name"),
        title: s("title").unwrap_or_default(),
        argv: v["argv"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|x| x.as_str().map(str::to_owned))
                    .collect()
            })
            .unwrap_or_default(),
        cwd: s("cwd"),
        state: s("state").unwrap_or_default(),
        agent_status,
        needs_attention,
        turn: v["turn"].as_u64().unwrap_or(0),
    }
}

fn public_key_info(openssh: &str) -> Result<PublicKeyInfo, CoreError> {
    let key = russh::keys::PublicKey::from_openssh(openssh).map_err(|e| CoreError::Storage {
        detail: e.to_string(),
    })?;
    Ok(PublicKeyInfo {
        openssh: openssh.to_owned(),
        fingerprint: key.fingerprint(HashAlg::Sha256).to_string(),
    })
}

/// A new ed25519 key: (OpenSSH private PEM, OpenSSH public line).
fn generate_key(comment: &str) -> Result<(String, String), CoreError> {
    let vault = |e: russh::keys::ssh_key::Error| CoreError::Vault {
        detail: e.to_string(),
    };
    let mut key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).map_err(vault)?;
    key.set_comment(comment);
    let private = key.to_openssh(LineEnding::LF).map_err(vault)?;
    let public = key.public_key().to_openssh().map_err(vault)?;
    Ok((private.to_string(), public))
}

fn key_info(k: &SavedKey) -> Result<KeyInfo, CoreError> {
    let public = public_key_info(&k.public)?;
    Ok(KeyInfo {
        id: k.id.clone(),
        label: k.label.clone(),
        openssh: public.openssh,
        fingerprint: public.fingerprint,
    })
}

fn to_host(h: &SavedHost) -> Host {
    Host {
        id: h.id.clone(),
        name: h.name.clone(),
        endpoint: h.endpoint.clone(),
        host_key: h.host_key.clone(),
    }
}

fn new_id() -> String {
    use rand::RngExt;
    let bytes: [u8; 8] = rand::rng().random();
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
