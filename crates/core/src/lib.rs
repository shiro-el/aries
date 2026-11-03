//! Aries phone core. [`AriesCore`] is the only boundary the app calls,
//! through uniffi (Kotlin today, Swift later).
//!
//! Modules: `transport` (SSH / local child process), `hc` (herdr-core
//! client), `store` (what survives a relaunch). The Host model, terminal,
//! chat, push, calls and beacon manager modules arrive with their tickets.

mod error;
mod hc;
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
use store::{SavedHost, SavedKey, Store};
use transport::{LocalProcess, Ssh, SshHop, Transport};

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
                key: self.private_key(host.key_id.as_deref())?,
                trusted: host.host_key,
                jumps: host
                    .jumps
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
                    .collect::<Result<_, CoreError>>()?,
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
