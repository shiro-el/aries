//! The Chat cache: what each Terminal's Chat last showed, so it opens
//! without the Host and resumes after its cursor when the Host is back.
//!
//! One SQLite file, `chat-cache.sqlite` in the data dir. Each item is sealed
//! independently; changed items and the resume cursor commit atomically.
//! IDs are HMAC lookup keys. Item ciphertext authenticates its Chat and order.
//! Legacy whole-Chat rows migrate on first read without extending retention.
//! The cache key is random, sealed by the
//! [`KeyVault`] under [`KeyProtection::Plain`] in `chat-cache.key`: the
//! cache must open on a locked phone and offline. If that key cannot be
//! opened (Keystore key gone), the cache starts over empty.

use std::collections::HashMap;
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
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS chats (
    host TEXT NOT NULL,
    chat BLOB NOT NULL,
    updated INTEGER NOT NULL,
    data BLOB NOT NULL,
    PRIMARY KEY (host, chat)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS chats_updated ON chats (updated);
CREATE TABLE IF NOT EXISTS chat_items (
    host TEXT NOT NULL,
    chat BLOB NOT NULL,
    item BLOB NOT NULL,
    position INTEGER NOT NULL,
    image_bytes INTEGER NOT NULL,
    data BLOB NOT NULL,
    PRIMARY KEY (host, chat, item),
    FOREIGN KEY (host, chat) REFERENCES chats(host, chat) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS chat_items_order ON chat_items(host, chat, position);
CREATE INDEX IF NOT EXISTS chat_items_images ON chat_items(host, chat, position DESC) WHERE image_bytes > 0;
CREATE TABLE IF NOT EXISTS removed_hosts (digest BLOB PRIMARY KEY) WITHOUT ROWID;";

/// A cached Chat: the Beacon's items in order and the cursor after them.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct CachedChat {
    #[serde(default)]
    pub usage: Option<aries_protocol::chat::AgentUsage>,
    pub items: Vec<ChatItem>,
    pub cursor: Option<String>,
    #[serde(skip)]
    pub positions: Vec<u64>,
}

/// Pending changes coalesce by ID, never by dropping an intermediate delta.
#[derive(Default, Debug)]
pub(crate) struct CacheChanges {
    pub usage: Option<aries_protocol::chat::AgentUsage>,
    pub reset: bool,
    pub items: HashMap<String, Option<(u64, Arc<ChatItem>)>>,
    pub cursor: Option<String>,
    /// Shared bodies for recovery if retention/clear removed the base row.
    pub baseline: Vec<(u64, Arc<ChatItem>)>,
}

impl CacheChanges {
    pub fn merge(&mut self, newer: Self) {
        if newer.reset {
            self.items.clear();
            self.reset = true;
        }
        self.items.extend(newer.items);
        self.cursor = newer.cursor;
        self.usage = newer.usage;
        self.baseline = newer.baseline;
    }
}

fn cap_images(item: &mut ChatItem, remaining: &mut usize) -> usize {
    let initial = *remaining;
    let images = match &mut item.body {
        ChatBody::User { images, .. } | ChatBody::Tool { images, .. } => images,
        _ => return 0,
    };
    for image in images.iter_mut().rev() {
        if let Some(data) = &image.data {
            if data.len() <= *remaining {
                *remaining -= data.len();
            } else {
                image.data = None;
            }
        }
    }
    initial - *remaining
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
            // Metadata, rows and cursor must come from the same SQLite revision,
            // including when another core handle is writing concurrently.
            let tx = o.conn.unchecked_transaction().map_err(storage)?;
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
            let opened = unseal(o, host, &chat, data)
                .and_then(|plain| serde_json::from_slice::<CachedChat>(&plain).ok());
            let Some(mut cached) = opened else {
                o.conn.execute("DELETE FROM chats WHERE host = ?1 AND chat = ?2", params![host, chat]).map_err(storage)?;
                tx.commit().map_err(storage)?;
                return Ok(None);
            };
            if !cached.items.is_empty() {
                // Old versions kept one encrypted BLOB. Convert in one transaction,
                // preserving its update time and cursor even across a crash.
                let changes = CacheChanges {
                    reset: true,
                    items: cached.items.drain(..).enumerate().map(|(i, item)| (item.id.clone(), Some((i as u64, Arc::new(item))))).collect(),
                    cursor: cached.cursor.clone(),
                    usage: cached.usage.clone(),
                    ..Default::default()
                };
                let updated: i64 = o.conn.query_row("SELECT updated FROM chats WHERE host=?1 AND chat=?2", params![host, chat], |r| r.get(0)).map_err(storage)?;
                apply_changes(o, &tx, host, &chat, &changes, updated)?;
            }
            let mut query = o.conn.prepare("SELECT item, position, data FROM chat_items WHERE host=?1 AND chat=?2 ORDER BY position").map_err(storage)?;
            let rows = query.query_map(params![host, chat], |r| Ok((r.get::<_, Vec<u8>>(0)?, r.get::<_, i64>(1)? as u64, r.get::<_, Vec<u8>>(2)?))).map_err(storage)?;
            for row in rows {
                let (id, position, data) = row.map_err(storage)?;
                let item = unseal(o, host, &item_aad(&chat, &id, position), data)
                    .and_then(|plain| serde_json::from_slice::<ChatItem>(&plain).ok());
                let Some(item) = item else {
                    // Never resume past a corrupt part of a transcript.
                    o.conn.execute("DELETE FROM chats WHERE host=?1 AND chat=?2", params![host, chat]).map_err(storage)?;
                    tx.commit().map_err(storage)?;
                    return Ok(None);
                };
                cached.positions.push(position);
                cached.items.push(item);
            }
            drop(query);
            tx.commit().map_err(storage)?;
            Ok(Some(cached))
        })
    }

    pub fn save(
        &self,
        host: &str,
        terminal: &str,
        changes: &CacheChanges,
    ) -> Result<(), CoreError> {
        self.with(|o| {
            write_changes(
                o,
                host,
                &chat_id(o, host, terminal),
                changes,
                self.now() as i64,
            )
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

/// Data and cursor must be visible together; a failed transaction leaves the
/// preceding resume point intact. No unchanged body is serialized or encrypted.
fn write_changes(
    o: &Open,
    host: &str,
    chat: &[u8],
    changes: &CacheChanges,
    updated: i64,
) -> Result<(), CoreError> {
    let tx = o.conn.unchecked_transaction().map_err(storage)?;
    apply_changes(o, &tx, host, chat, changes, updated)?;
    tx.commit().map_err(storage)
}

fn apply_changes(
    o: &Open,
    tx: &rusqlite::Transaction<'_>,
    host: &str,
    chat: &[u8],
    changes: &CacheChanges,
    updated: i64,
) -> Result<(), CoreError> {
    let removed: bool = tx
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM removed_hosts WHERE digest=?1)",
            [host_digest(host)],
            |r| r.get(0),
        )
        .map_err(storage)?;
    if removed {
        return Ok(());
    }
    let exists: bool = tx
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM chats WHERE host=?1 AND chat=?2)",
            params![host, chat],
            |r| r.get(0),
        )
        .map_err(storage)?;
    let recovered;
    let changes = if !exists && !changes.reset {
        recovered = CacheChanges {
            reset: true,
            items: changes
                .baseline
                .iter()
                .map(|(p, item)| (item.id.clone(), Some((*p, item.clone()))))
                .collect(),
            cursor: changes.cursor.clone(),
            usage: changes.usage.clone(),
            ..Default::default()
        };
        &recovered
    } else {
        changes
    };
    if changes.reset {
        tx.execute(
            "DELETE FROM chat_items WHERE host=?1 AND chat=?2",
            params![host, chat],
        )
        .map_err(storage)?;
    }
    let metadata = CachedChat {
        usage: changes.usage.clone(),
        items: vec![],
        cursor: changes.cursor.clone(),
        positions: vec![],
    };
    let data = seal(
        o,
        host,
        chat,
        serde_json::to_vec(&metadata).map_err(storage)?,
    )?;
    // UPDATE rather than REPLACE: replacing would cascade-delete unchanged items.
    tx.execute(
        "INSERT INTO chats(host,chat,updated,data) VALUES (?1,?2,?3,?4)
        ON CONFLICT(host,chat) DO UPDATE SET updated=excluded.updated,data=excluded.data",
        params![host, chat, updated, data],
    )
    .map_err(storage)?;
    for (id, change) in &changes.items {
        let id = hmac::sign(&o.lookup, id.as_bytes()).as_ref().to_vec();
        match change {
            Some((position, item)) => {
                let mut item = item.as_ref().clone();
                let mut budget = IMAGE_BUDGET;
                let bytes = cap_images(&mut item, &mut budget);
                let data = seal(
                    o,
                    host,
                    &item_aad(chat, &id, *position),
                    serde_json::to_vec(&item).map_err(storage)?,
                )?;
                tx.execute("INSERT INTO chat_items(host,chat,item,position,image_bytes,data) VALUES (?1,?2,?3,?4,?5,?6)
                    ON CONFLICT(host,chat,item) DO UPDATE SET position=excluded.position,image_bytes=excluded.image_bytes,data=excluded.data", params![host, chat, id, *position as i64, bytes as i64, data]).map_err(storage)?;
            }
            None => {
                tx.execute(
                    "DELETE FROM chat_items WHERE host=?1 AND chat=?2 AND item=?3",
                    params![host, chat, id],
                )
                .map_err(storage)?;
            }
        }
    }
    // Read sizes only for the image budget; decrypt only evicted image-bearing
    // items. Text-only history never participates in this work.
    let mut query = tx.prepare("SELECT item,position,image_bytes FROM chat_items WHERE host=?1 AND chat=?2 AND image_bytes>0 ORDER BY position DESC").map_err(storage)?;
    let rows = query
        .query_map(params![host, chat], |r| {
            Ok((
                r.get::<_, Vec<u8>>(0)?,
                r.get::<_, i64>(1)? as u64,
                r.get::<_, i64>(2)? as usize,
            ))
        })
        .map_err(storage)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage)?;
    drop(query);
    let mut remaining = IMAGE_BUDGET;
    for (id, position, bytes) in rows {
        if bytes <= remaining {
            remaining -= bytes;
            continue;
        }
        let data = tx
            .query_row(
                "SELECT data FROM chat_items WHERE host=?1 AND chat=?2 AND item=?3",
                params![host, chat, id],
                |r| r.get(0),
            )
            .map_err(storage)?;
        let aad = item_aad(chat, &id, position);
        let plain = unseal(o, host, &aad, data).ok_or_else(|| storage("invalid cached item"))?;
        let mut item: ChatItem = serde_json::from_slice(&plain).map_err(storage)?;
        let bytes = cap_images(&mut item, &mut remaining);
        let data = seal(o, host, &aad, serde_json::to_vec(&item).map_err(storage)?)?;
        tx.execute(
            "UPDATE chat_items SET data=?4,image_bytes=?5 WHERE host=?1 AND chat=?2 AND item=?3",
            params![host, chat, id, data, bytes as i64],
        )
        .map_err(storage)?;
    }
    Ok(())
}

fn item_aad(chat: &[u8], item: &[u8], position: u64) -> Vec<u8> {
    [chat, item, &position.to_le_bytes()].concat()
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
