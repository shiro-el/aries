//! The Chat cache: what each Terminal's Chat last showed, so it opens
//! without the Host and resumes after its cursor when the Host is back.
//!
//! One SQLite file, `chat-cache.sqlite` in the data dir, one row per
//! (Host, Terminal). Rows are sealed before they are written: nothing of a
//! Chat is ever stored in the clear. The row holds the Host id (to delete
//! per Host), an HMAC of the Terminal (the lookup key), the last write time
//! (for retention) and the sealed Chat (ChaCha20-Poly1305 over the Beacon's
//! own items plus the cursor). The cache key is random, sealed by the
//! [`KeyVault`] under [`KeyProtection::Plain`] in `chat-cache.key`: the
//! cache must open on a locked phone and offline. If that key cannot be
//! opened (Keystore key gone), the cache starts over empty.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use aries_protocol::chat::{ChatBody, ChatItem};
use ring::aead::{self, Aad, LessSafeKey, Nonce, UnboundKey};
use ring::rand::{SecureRandom, SystemRandom};
use ring::{hkdf, hmac};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, storage};
use crate::{KeyProtection, KeyVault};

/// Days a Chat stays cached after it was last updated, unless the user
/// picks another period.
pub const DEFAULT_RETENTION_DAYS: u32 = 30;

const DB: &str = "chat-cache.sqlite";
const KEY: &str = "chat-cache.key";
const DAY: u64 = 24 * 60 * 60;
/// How often a followed Chat is written at most.
pub(crate) const SAVE_EVERY: Duration = Duration::from_secs(1);
/// Persist at most 16 MiB of base64 image payload per Chat, newest first.
const IMAGE_BUDGET: usize = 16 * 1024 * 1024;

const SCHEMA: &str = "PRAGMA secure_delete = ON;
CREATE TABLE IF NOT EXISTS chats (
    host TEXT NOT NULL,
    chat BLOB NOT NULL,
    updated INTEGER NOT NULL,
    data BLOB NOT NULL,
    PRIMARY KEY (host, chat)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS chats_updated ON chats (updated);
CREATE TABLE IF NOT EXISTS removed_hosts (digest BLOB PRIMARY KEY) WITHOUT ROWID;";

/// A cached Chat: the Beacon's items in order and the cursor after them.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct CachedChat {
    #[serde(default)]
    pub usage: Option<aries_protocol::chat::AgentUsage>,
    pub items: Vec<ChatItem>,
    pub cursor: Option<String>,
}

impl CachedChat {
    fn cap_images(&mut self) -> bool {
        let mut remaining = IMAGE_BUDGET;
        let mut changed = false;
        for item in self.items.iter_mut().rev() {
            let images = match &mut item.body {
                ChatBody::User { images, .. } | ChatBody::Tool { images, .. } => images,
                _ => continue,
            };
            for image in images.iter_mut().rev() {
                if let Some(data) = &image.data {
                    if data.len() <= remaining {
                        remaining -= data.len();
                    } else {
                        image.data = None;
                        changed = true;
                    }
                }
            }
        }
        changed
    }
}

struct Open {
    conn: Connection,
    seal: LessSafeKey,
    lookup: hmac::Key,
}

/// The core's handle on the cache. Cheap to clone; opens the file and the
/// key on first use (blocking: call from a blocking task).
#[derive(Clone)]
pub(crate) struct ChatCache {
    dir: PathBuf,
    vault: Arc<dyn KeyVault>,
    open: Arc<Mutex<Option<Open>>>,
    retention_days: Arc<AtomicU32>,
    /// Added to the wall clock (tests).
    shift_secs: Arc<AtomicU64>,
}

impl ChatCache {
    pub fn new(dir: &Path, vault: Arc<dyn KeyVault>, retention_days: u32) -> Self {
        Self {
            dir: dir.to_owned(),
            vault,
            open: Arc::new(Mutex::new(None)),
            retention_days: Arc::new(AtomicU32::new(retention_days.max(1))),
            shift_secs: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn retention_days(&self) -> u32 {
        self.retention_days.load(Ordering::Relaxed)
    }

    pub fn set_retention_days(&self, days: u32) {
        self.retention_days.store(days.max(1), Ordering::Relaxed);
    }

    pub fn shift_clock(&self, by: Duration) {
        self.shift_secs.fetch_add(by.as_secs(), Ordering::Relaxed);
    }

    fn now(&self) -> u64 {
        let wall = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        wall + self.shift_secs.load(Ordering::Relaxed)
    }

    fn with<T>(&self, f: impl FnOnce(&Open) -> Result<T, CoreError>) -> Result<T, CoreError> {
        let mut open = self.open.lock().expect("chat cache lock");
        if open.is_none() {
            *open = Some(self.open_cache()?);
        }
        let open = open.as_ref().expect("opened");
        let cutoff = self
            .now()
            .saturating_sub(u64::from(self.retention_days()) * DAY);
        open.conn
            .execute("DELETE FROM chats WHERE updated < ?1", [cutoff as i64])
            .map_err(storage)?;
        f(open)
    }

    fn open_cache(&self) -> Result<Open, CoreError> {
        let conn = connect(&self.dir.join(DB))?;
        let master = match std::fs::read(self.dir.join(KEY)) {
            Ok(sealed) => self
                .vault
                .open(sealed, KeyProtection::Plain)
                .ok()
                .filter(|k| k.len() == 32),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
            Err(e) => return Err(storage(e)),
        };
        let master = match master {
            Some(k) => k,
            None => {
                // No usable key: whatever rows exist can never be read.
                conn.execute("DELETE FROM chats", []).map_err(storage)?;
                let mut k = vec![0u8; 32];
                SystemRandom::new()
                    .fill(&mut k)
                    .map_err(|_| storage("no randomness"))?;
                let sealed = self.vault.seal(k.clone(), KeyProtection::Plain)?;
                let tmp = self.dir.join(format!(".{KEY}.tmp"));
                std::fs::write(&tmp, sealed).map_err(storage)?;
                std::fs::rename(&tmp, self.dir.join(KEY)).map_err(storage)?;
                k
            }
        };
        let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, b"aries chat cache v1").extract(&master);
        let seal: UnboundKey = prk
            .expand(&[b"seal"], &aead::CHACHA20_POLY1305)
            .map_err(|_| storage("hkdf"))?
            .into();
        let lookup: hmac::Key = prk
            .expand(&[b"lookup"], hmac::HMAC_SHA256)
            .map_err(|_| storage("hkdf"))?
            .into();
        Ok(Open {
            conn,
            seal: LessSafeKey::new(seal),
            lookup,
        })
    }

    /// The cached Chat of `terminal` on `host`, if there is one that is
    /// still within the retention period and opens with the cache key.
    pub fn load(&self, host: &str, terminal: &str) -> Result<Option<CachedChat>, CoreError> {
        self.with(|o| {
            let chat = chat_id(o, host, terminal);
            let data: Option<Vec<u8>> = o
                .conn
                .query_row(
                    "SELECT data FROM chats WHERE host = ?1 AND chat = ?2",
                    params![host, chat],
                    |r| r.get(0),
                )
                .optional()
                .map_err(storage)?;
            let Some(data) = data else { return Ok(None) };
            let mut opened = unseal(o, host, &chat, data)
                .and_then(|plain| serde_json::from_slice::<CachedChat>(&plain).ok());
            if let Some(cached) = &mut opened
                && cached.cap_images()
            {
                // Apply the cap to rows written before this policy too,
                // without extending their retention period.
                let data = seal(o, host, &chat, serde_json::to_vec(cached).map_err(storage)?)?;
                o.conn
                    .execute(
                        "UPDATE chats SET data = ?3 WHERE host = ?1 AND chat = ?2",
                        params![host, chat, data],
                    )
                    .map_err(storage)?;
            }
            if opened.is_none() {
                o.conn
                    .execute(
                        "DELETE FROM chats WHERE host = ?1 AND chat = ?2",
                        params![host, chat],
                    )
                    .map_err(storage)?;
            }
            Ok(opened)
        })
    }

    pub fn save(&self, host: &str, terminal: &str, chat: &CachedChat) -> Result<(), CoreError> {
        let mut chat = chat.clone();
        chat.cap_images();
        let plain = serde_json::to_vec(&chat).map_err(storage)?;
        let now = self.now();
        self.with(|o| {
            let id = chat_id(o, host, terminal);
            let data = seal(o, host, &id, plain)?;
            o.conn
                .execute(
                    "INSERT OR REPLACE INTO chats (host, chat, updated, data)
                     SELECT ?1, ?2, ?3, ?4 WHERE NOT EXISTS
                     (SELECT 1 FROM removed_hosts WHERE digest = ?5)",
                    params![host, id, now as i64, data, host_digest(host)],
                )
                .map_err(storage)?;
            Ok(())
        })
    }

    /// Deletes every cached Chat of `host`.
    pub fn clear(&self, host: &str) -> Result<(), CoreError> {
        self.with(|o| {
            o.conn
                .execute("DELETE FROM chats WHERE host = ?1", [host])
                .map_err(storage)?;
            Ok(())
        })
    }
}

/// Deletes `host`'s cached Chats without the cache key (Host removal must
/// work even when the key cannot be opened). Overwrites the deleted rows.
pub(crate) fn remove_host(dir: &Path, host: &str) -> Result<(), CoreError> {
    let path = dir.join(DB);
    let mut conn = connect(&path)?;
    let tx = conn.transaction().map_err(storage)?;
    // A durable tombstone also rejects writers that had not opened the
    // cache yet. The write predicate and deletion serialize in SQLite.
    tx.execute(
        "INSERT OR IGNORE INTO removed_hosts (digest) VALUES (?1)",
        [host_digest(host)],
    )
    .map_err(storage)?;
    tx.execute("DELETE FROM chats WHERE host = ?1", [host])
        .map_err(storage)?;
    tx.commit().map_err(storage)
}

fn host_digest(host: &str) -> Vec<u8> {
    ring::digest::digest(&ring::digest::SHA256, host.as_bytes())
        .as_ref()
        .to_vec()
}

fn connect(path: &Path) -> Result<Connection, CoreError> {
    let conn = Connection::open(path).map_err(storage)?;
    conn.busy_timeout(Duration::from_secs(5)).map_err(storage)?;
    conn.execute_batch(SCHEMA).map_err(storage)?;
    Ok(conn)
}

fn chat_id(o: &Open, host: &str, terminal: &str) -> Vec<u8> {
    let mut msg = host.as_bytes().to_vec();
    msg.push(0);
    msg.extend_from_slice(terminal.as_bytes());
    hmac::sign(&o.lookup, &msg).as_ref().to_vec()
}

fn aad(host: &str, chat: &[u8]) -> Vec<u8> {
    let mut a = host.as_bytes().to_vec();
    a.push(0);
    a.extend_from_slice(chat);
    a
}

fn seal(o: &Open, host: &str, chat: &[u8], mut plain: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    let mut nonce = [0u8; aead::NONCE_LEN];
    SystemRandom::new()
        .fill(&mut nonce)
        .map_err(|_| storage("no randomness"))?;
    o.seal
        .seal_in_place_append_tag(
            Nonce::assume_unique_for_key(nonce),
            Aad::from(aad(host, chat)),
            &mut plain,
        )
        .map_err(|_| storage("seal"))?;
    let mut out = nonce.to_vec();
    out.extend(plain);
    Ok(out)
}

fn unseal(o: &Open, host: &str, chat: &[u8], data: Vec<u8>) -> Option<Vec<u8>> {
    if data.len() < aead::NONCE_LEN {
        return None;
    }
    let (nonce, sealed) = data.split_at(aead::NONCE_LEN);
    let mut buf = sealed.to_vec();
    let nonce = Nonce::try_assume_unique_for_key(nonce).ok()?;
    let plain = o
        .seal
        .open_in_place(nonce, Aad::from(aad(host, chat)), &mut buf)
        .ok()?;
    Some(plain.to_vec())
}
