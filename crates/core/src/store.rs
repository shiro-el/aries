//! What the app remembers between launches, under the app's data dir:
//! `hosts.json` (Hosts and their trusted Host key fingerprints),
//! `identity.sealed` (the SSH private key, sealed by the [`KeyVault`] under
//! the key's [`KeyProtection`]) and `identity.pub`; extra SSH keys in
//! `keys.json` (id, label, public half) and `key-<id>.sealed` (same format
//! as `identity.sealed`); app settings in `settings.json`. Per-Host local
//! state goes under `hosts/<id>/`
//! ([`Store::host_dir`]), which Host removal deletes.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, storage};
use crate::{Endpoint, JumpHost, KeyProtection};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub(crate) struct Saved {
    #[serde(default)]
    pub hosts: Vec<SavedHost>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct SavedHost {
    pub id: String,
    pub name: String,
    pub endpoint: Endpoint,
    #[serde(default)]
    pub host_key: Option<String>,
    /// Hops in order, the first one reached directly.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub jumps: Vec<JumpHost>,
    /// SSH key for the Host itself; `None` is the default key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct SavedKey {
    pub id: String,
    pub label: String,
    pub public: String,
}

/// `settings.json`: app-wide preferences. Missing fields take their default.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub(crate) struct Settings {
    /// Connecting updates an out-of-date Beacon (ticket 26).
    pub beacon_auto_update: bool,
    /// Days a cached Chat is kept after its last update (ticket 44).
    pub chat_cache_days: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            beacon_auto_update: true,
            chat_cache_days: crate::chat_cache::DEFAULT_RETENTION_DAYS,
        }
    }
}

pub(crate) struct Store {
    dir: PathBuf,
}

impl Store {
    pub fn open(dir: &Path) -> Result<Self, CoreError> {
        std::fs::create_dir_all(dir).map_err(storage)?;
        Ok(Self {
            dir: dir.to_owned(),
        })
    }

    pub fn load(&self) -> Result<Saved, CoreError> {
        match std::fs::read(self.dir.join("hosts.json")) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(storage),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Saved::default()),
            Err(e) => Err(storage(e)),
        }
    }

    pub fn save(&self, saved: &Saved) -> Result<(), CoreError> {
        let bytes = serde_json::to_vec_pretty(saved).map_err(storage)?;
        self.write_atomic("hosts.json", &bytes)
    }

    /// The sealed SSH private key, the protection it was sealed under, and
    /// the public key.
    pub fn read_identity(&self) -> Result<Option<Identity>, CoreError> {
        let bytes = match std::fs::read(self.dir.join("identity.sealed")) {
            Ok(b) => b,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(storage(e)),
        };
        let (protection, sealed) = decode_sealed(bytes)?;
        let public = std::fs::read_to_string(self.dir.join("identity.pub")).map_err(storage)?;
        Ok(Some(Identity {
            protection,
            sealed,
            public: public.trim().to_owned(),
        }))
    }

    /// Writes the identity. `identity.sealed` is replaced in one rename, so
    /// the sealed bytes and their protection never disagree.
    pub fn write_identity(&self, id: &Identity) -> Result<(), CoreError> {
        self.write_atomic("identity.pub", format!("{}\n", id.public).as_bytes())?;
        self.write_atomic("identity.sealed", &encode_sealed(id.protection, &id.sealed))
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Where per-Host local state (caches, pairing, ...) lives. Not created
    /// here; the owner creates it on first use.
    pub fn host_dir(&self, host_id: &str) -> PathBuf {
        self.dir.join("hosts").join(host_id)
    }

    /// Deletes [`Store::host_dir`]; succeeds if it never existed.
    pub fn remove_host_dir(&self, host_id: &str) -> Result<(), CoreError> {
        // Ids come from `new_id` (hex); refuse anything that could escape.
        if host_id.is_empty() || !host_id.chars().all(|c| c.is_ascii_alphanumeric()) {
            return Err(CoreError::UnknownHost {
                id: host_id.to_owned(),
            });
        }
        match std::fs::remove_dir_all(self.host_dir(host_id)) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(storage(e)),
        }
    }

    pub fn read_keys(&self) -> Result<Vec<SavedKey>, CoreError> {
        match std::fs::read(self.dir.join("keys.json")) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(storage),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
            Err(e) => Err(storage(e)),
        }
    }

    /// The sealed private half of extra key `id` and the protection it was
    /// sealed under.
    pub fn read_key_sealed(&self, id: &str) -> Result<(KeyProtection, Vec<u8>), CoreError> {
        let bytes = std::fs::read(self.dir.join(format!("key-{id}.sealed"))).map_err(storage)?;
        decode_sealed(bytes)
    }

    /// Replaces the sealed private half of extra key `id` in one rename.
    pub fn write_key_sealed(
        &self,
        id: &str,
        protection: KeyProtection,
        sealed: &[u8],
    ) -> Result<(), CoreError> {
        self.write_atomic(
            &format!("key-{id}.sealed"),
            &encode_sealed(protection, sealed),
        )
    }

    /// Adds a key: the sealed private half first, so `keys.json` never lists
    /// a key whose private half is missing.
    pub fn add_key(
        &self,
        key: &SavedKey,
        protection: KeyProtection,
        sealed: &[u8],
    ) -> Result<(), CoreError> {
        let mut keys = self.read_keys()?;
        self.write_key_sealed(&key.id, protection, sealed)?;
        keys.push(key.clone());
        let bytes = serde_json::to_vec_pretty(&keys).map_err(storage)?;
        self.write_atomic("keys.json", &bytes)
    }

    /// App settings; defaults if `settings.json` is missing or unreadable.
    pub fn read_settings(&self) -> Settings {
        std::fs::read(self.dir.join("settings.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    pub fn write_settings(&self, settings: &Settings) -> Result<(), CoreError> {
        let bytes = serde_json::to_vec_pretty(settings).map_err(storage)?;
        self.write_atomic("settings.json", &bytes)
    }

    fn write_atomic(&self, name: &str, bytes: &[u8]) -> Result<(), CoreError> {
        let tmp = self.dir.join(format!(".{name}.tmp"));
        std::fs::write(&tmp, bytes).map_err(storage)?;
        std::fs::rename(&tmp, self.dir.join(name)).map_err(storage)
    }
}

pub(crate) struct Identity {
    pub protection: KeyProtection,
    pub sealed: Vec<u8>,
    pub public: String,
}

/// `identity.sealed` starts with this, then one protection byte, then the
/// vault's bytes. Files without it predate key policies and are `Plain`.
const SEALED_MAGIC: &[u8] = b"ARIESKEY1";

fn encode_sealed(protection: KeyProtection, sealed: &[u8]) -> Vec<u8> {
    let tag = match protection {
        KeyProtection::Plain => 0,
        KeyProtection::Biometric => 1,
    };
    let mut out = SEALED_MAGIC.to_vec();
    out.push(tag);
    out.extend_from_slice(sealed);
    out
}

fn decode_sealed(bytes: Vec<u8>) -> Result<(KeyProtection, Vec<u8>), CoreError> {
    let Some(rest) = bytes.strip_prefix(SEALED_MAGIC) else {
        return Ok((KeyProtection::Plain, bytes));
    };
    let protection = match rest.first() {
        Some(0) => KeyProtection::Plain,
        Some(1) => KeyProtection::Biometric,
        other => return Err(storage(format!("unknown key protection {other:?}"))),
    };
    Ok((protection, rest[1..].to_vec()))
}
