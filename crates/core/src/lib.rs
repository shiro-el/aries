//! Aries phone core. [`AriesCore`] is the only boundary the app calls,
//! through uniffi (Kotlin today, Swift later).
//!
//! Modules: `transport` (SSH / local child process), `hc` (herdr-core
//! client), `store` (what survives a relaunch). The Host model, terminal,
//! chat, push, calls and beacon manager modules arrive with their tickets.

mod error;
mod hc;
mod launch;
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

pub use error::{CoreError, Incompatibility, VaultError};
pub use hc::{GENERATION, MIN_CORE_VERSION, REQUIRED_METHODS};
pub use launch::{LaunchRequest, RECENT_CWDS_KEPT};
pub use structure::{HostStructure, PaneInfo, SpaceInfo, StructureProblem, TabInfo};

use hc::HcClient;
use store::{SavedHost, Store};
use transport::{LocalProcess, PasswordLogin, Ssh, Transport};

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
        let mut key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).map_err(|e| {
            CoreError::Vault {
                detail: e.to_string(),
            }
        })?;
        key.set_comment("aries");
        let private = key
            .to_openssh(LineEnding::LF)
            .map_err(|e| CoreError::Vault {
                detail: e.to_string(),
            })?;
        let public = key
            .public_key()
            .to_openssh()
            .map_err(|e| CoreError::Vault {
                detail: e.to_string(),
            })?;
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

    /// Logs in to an SSH Host once with `password` and adds the app's public
    /// key to its `~/.ssh/authorized_keys`, so later connections use the key.
    /// The password is not stored. The Host key is checked like `connect`
    /// does, so the first call may return [`CoreError::HostKeyUnknown`].
    pub async fn install_key_with_password(
        &self,
        host_id: String,
        password: String,
    ) -> Result<KeyInstall, CoreError> {
        let host = self.saved_host(host_id)?;
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
        let public = self.ensure_key()?.openssh;
        let login = PasswordLogin {
            address,
            port,
            user,
            password,
            trusted: host.host_key,
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
        let host = self.saved_host(host_id)?;
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
                key: self.private_key()?,
                trusted: host.host_key,
            }),
        };
        let client =
            on_runtime(async move { HcClient::handshake(transport.open().await?).await }).await?;
        Ok(Arc::new(HostConnection {
            client: Arc::new(client),
            recent: launch::RecentCwds::in_host_dir(self.store.host_dir(&host.id)),
        }))
    }
}

impl AriesCore {
    fn saved_host(&self, host_id: String) -> Result<SavedHost, CoreError> {
        self.hosts
            .lock()
            .expect("hosts lock")
            .iter()
            .find(|h| h.id == host_id)
            .cloned()
            .ok_or(CoreError::UnknownHost { id: host_id })
    }

    fn private_key(&self) -> Result<PrivateKey, CoreError> {
        let (sealed, _) = self.store.read_identity()?.ok_or(CoreError::NoKey)?;
        let pem = self.vault.open(sealed)?;
        let pem = String::from_utf8(pem).map_err(|e| CoreError::Vault {
            detail: e.to_string(),
        })?;
        PrivateKey::from_openssh(&pem).map_err(|e| CoreError::Vault {
            detail: e.to_string(),
        })
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

/// A live connection to one Host's herdr-core.
#[derive(uniffi::Object)]
pub struct HostConnection {
    client: Arc<HcClient>,
    recent: launch::RecentCwds,
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
    TerminalInfo {
        address: s("address").unwrap_or_default(),
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
