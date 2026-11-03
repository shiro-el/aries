//! Aries phone core. [`AriesCore`] is the only boundary the app calls,
//! through uniffi (Kotlin today, Swift later).
//!
//! Modules: `transport` (SSH / local child process), `hc` (herdr-core
//! client), `store` (what survives a relaunch). The Host model, terminal,
//! chat, push, calls and beacon manager modules arrive with their tickets.

mod beacon;
mod error;
mod hc;
mod host_model;
mod removal;
mod store;
mod structure;
mod transport;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use russh::keys::ssh_key::LineEnding;
use russh::keys::{Algorithm, HashAlg, PrivateKey};
use serde::{Deserialize, Serialize};

pub use beacon::{
    ALLOWED_BEACON, BeaconArtifact, BeaconFetcher, BeaconInstall, BeaconRelease, BeaconState,
    FetchError, Linger, pinned_release,
};
pub use error::{CoreError, Incompatibility, VaultError};
pub use hc::{GENERATION, MIN_CORE_VERSION, REQUIRED_METHODS};
pub use host_model::{AgentStatus, BlockedReason, TerminalWatch};
pub use structure::{HostStructure, PaneInfo, SpaceInfo, StructureProblem, TabInfo};

use hc::HcClient;
use store::{SavedHost, SavedKey, Store};
use transport::{LocalProcess, PasswordLogin, Ssh, SshHop, Transport};

/// Id of the key [`AriesCore::ensure_key`] creates, in [`AriesCore::keys`].
pub const DEFAULT_KEY_ID: &str = "default";

uniffi::setup_scaffolding!();

/// Seals secrets at rest. On Android this is an AES-GCM key held by the
/// Android Keystore; tests use a stand-in.
#[uniffi::export(with_foreign)]
pub trait KeyVault: Send + Sync {
    fn seal(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, VaultError>;
    fn open(&self, sealed: Vec<u8>) -> Result<Vec<u8>, VaultError>;
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
    /// Blocked or Error: the Agent needs the user.
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
}

#[uniffi::export]
impl AriesCore {
    /// Opens the core over `data_dir` (the app's private files dir).
    #[uniffi::constructor]
    pub fn new(data_dir: String, vault: Arc<dyn KeyVault>) -> Result<Arc<Self>, CoreError> {
        let store = Store::open(&PathBuf::from(data_dir))?;
        let saved = store.load()?;
        Ok(Arc::new(Self {
            store,
            vault,
            hosts: Mutex::new(saved.hosts),
            beacon_release: Mutex::new(pinned_release()),
        }))
    }

    /// The app's SSH public key, if one was created.
    pub fn public_key(&self) -> Result<Option<PublicKeyInfo>, CoreError> {
        let Some((_, public)) = self.store.read_identity()? else {
            return Ok(None);
        };
        Ok(Some(public_key_info(&public)?))
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
        let sealed = self.vault.seal(private.as_bytes().to_vec())?;
        self.store.write_identity(&sealed, &public)?;
        public_key_info(&public)
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
    /// account of their own.
    pub fn create_key(&self, label: String) -> Result<KeyInfo, CoreError> {
        let (private, public) = generate_key(&label)?;
        let sealed = self.vault.seal(private.into_bytes())?;
        let saved = SavedKey {
            id: new_id(),
            label,
            public,
        };
        self.store.add_key(&saved, &sealed)?;
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
        let sealed = match key_id {
            None | Some(DEFAULT_KEY_ID) => self.store.read_identity()?.ok_or(CoreError::NoKey)?.0,
            Some(id) => {
                self.check_key_id(Some(id))?;
                self.store.read_key_sealed(id)?
            }
        };
        let pem = self.vault.open(sealed)?;
        let pem = String::from_utf8(pem).map_err(|e| CoreError::Vault {
            detail: e.to_string(),
        })?;
        PrivateKey::from_openssh(&pem).map_err(|e| CoreError::Vault {
            detail: e.to_string(),
        })
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
        on_runtime(async move { beacon::uninstall(&ssh).await }).await
    }
}

impl AriesCore {
    /// Replaces the pinned Beacon release. For tests and development
    /// builds; not exposed to the app.
    pub fn set_beacon_release(&self, release: BeaconRelease) {
        *self.beacon_release.lock().expect("release lock") = release;
    }

    fn beacon_ssh(&self, host_id: &str) -> Result<Ssh, CoreError> {
        let host = self.saved_host(host_id)?;
        match host.endpoint {
            Endpoint::Ssh {
                address,
                port,
                user,
            } => Ok(Ssh {
                address,
                port,
                user,
                key: self.private_key(host.key_id.as_deref())?,
                trusted: host.host_key,
                jumps: self.ssh_hops(host.jumps)?,
            }),
            Endpoint::Local { .. } => Err(CoreError::BeaconNeedsSsh),
        }
    }
}

/// A live connection to one Host's herdr-core.
#[derive(uniffi::Object)]
pub struct HostConnection {
    client: Arc<HcClient>,
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
        on_runtime(async move { read_structure(&client).await }).await
    }

    /// Delivers the structure now and again whenever the desktop changes the
    /// document or Terminals come, go or are relabelled. Ends (with
    /// `on_closed`) when the connection does or `stop` is called.
    pub async fn watch_structure(
        &self,
        listener: Arc<dyn StructureListener>,
    ) -> Result<Arc<StructureWatch>, CoreError> {
        let client = self.client.clone();
        let task = on_runtime(async move {
            // Subscribe before reading, so no change falls in between.
            let (_, mut events) = client.subscribe_events(serde_json::json!({})).await?;
            listener.on_structure(read_structure(&client).await?);
            Ok(tokio::spawn(async move {
                while let Some(msg) = events.recv().await {
                    let mut changed = changes_structure(&msg);
                    while let Ok(more) = events.try_recv() {
                        changed |= changes_structure(&more);
                    }
                    if !changed {
                        continue;
                    }
                    match read_structure(&client).await {
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

async fn read_structure(client: &HcClient) -> Result<HostStructure, CoreError> {
    let doc = client
        .call(
            "doc.get",
            serde_json::json!({ "name": structure::DOCUMENT }),
        )
        .await?;
    let list = client.call("terminal.list", serde_json::json!({})).await?;
    let terminals = list["terminals"].as_array().cloned().unwrap_or_default();
    Ok(structure::build(&doc, &terminals))
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
