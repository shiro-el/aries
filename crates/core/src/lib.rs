//! Aries phone core. [`AriesCore`] is the only boundary the app calls,
//! through uniffi (Kotlin today, Swift later).
//!
//! Modules: `transport` (SSH / local child process), `hc` (herdr-core
//! client), `store` (what survives a relaunch). The Host model, terminal,
//! chat, push, calls and beacon manager modules arrive with their tickets.

mod error;
mod hc;
mod removal;
mod store;
mod transport;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use russh::keys::ssh_key::LineEnding;
use russh::keys::{Algorithm, HashAlg, PrivateKey};
use serde::{Deserialize, Serialize};

pub use error::{CoreError, Incompatibility, VaultError};
pub use hc::{GENERATION, MIN_CORE_VERSION, REQUIRED_METHODS};

use hc::HcClient;
use store::{SavedHost, Store};
use transport::{LocalProcess, Ssh, Transport};

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
        let Some(id) = self.store.read_identity()? else {
            return Ok(None);
        };
        Ok(Some(public_key_info(&id.public)?))
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

    /// How the app's SSH key is unlocked, or `None` before it exists.
    pub fn key_protection(&self) -> Result<Option<KeyProtection>, CoreError> {
        Ok(self.store.read_identity()?.map(|id| id.protection))
    }

    /// Re-seals the same SSH key under `protection` (the public key and
    /// every Host's `authorized_keys` stay valid). Entering or leaving
    /// [`KeyProtection::Biometric`] asks the user to authenticate, so call it
    /// from the foreground and off the main thread. On failure the old
    /// protection stays.
    pub fn set_key_protection(&self, protection: KeyProtection) -> Result<(), CoreError> {
        let id = self.store.read_identity()?.ok_or(CoreError::NoKey)?;
        if id.protection == protection {
            return Ok(());
        }
        let private = self.vault.open(id.sealed, id.protection)?;
        let sealed = self.vault.seal(private, protection)?;
        self.store.write_identity(&store::Identity {
            protection,
            sealed,
            public: id.public,
        })
    }

    /// Whether a Choice for this Host can be answered from a notification
    /// without opening the app: signing in to the Host must not need a
    /// biometric unlock. When `false`, show "Open app" instead of Choices.
    pub fn can_answer_in_background(&self, host_id: String) -> Result<bool, CoreError> {
        let endpoint = self
            .hosts
            .lock()
            .expect("hosts lock")
            .iter()
            .find(|h| h.id == host_id)
            .map(|h| h.endpoint.clone())
            .ok_or(CoreError::UnknownHost { id: host_id })?;
        Ok(match endpoint {
            Endpoint::Local { .. } => true,
            Endpoint::Ssh { .. } => self.key_protection()? == Some(KeyProtection::Plain),
        })
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
        let host = self
            .hosts
            .lock()
            .expect("hosts lock")
            .iter()
            .find(|h| h.id == host_id)
            .cloned()
            .ok_or(CoreError::UnknownHost { id: host_id })?;
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
        }))
    }
}

impl AriesCore {
    fn private_key(&self) -> Result<PrivateKey, CoreError> {
        let id = self.store.read_identity()?.ok_or(CoreError::NoKey)?;
        let pem = self.vault.open(id.sealed, id.protection)?;
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

    pub async fn disconnect(&self) {
        let client = self.client.clone();
        let _ = on_runtime(async move {
            client.close().await;
            Ok(())
        })
        .await;
    }
}

fn terminal_info(v: &serde_json::Value) -> TerminalInfo {
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
