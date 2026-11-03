//! Authenticated envelope IDs only: no notification content or pairing secrets.
//! Per-Host files are removed by the existing Host cleanup step.
//!
//! SHA-256 of the complete HPKE envelope identifies an exact redelivery without
//! changing the v1 wire format. Authenticated message timestamps set the 24-hour
//! acceptance window, allowing five minutes of Host clock skew into the future.
//! Each accepted delivery prunes expired records; at capacity the oldest received
//! ID is evicted. Such an evicted envelope can be accepted again while still fresh.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::error::{CoreError, storage};
use crate::store::Store;
use crate::{PushContent, PushNotice};

const MAX_AGE_MS: u64 = 24 * 60 * 60 * 1_000;
const FUTURE_SKEW_MS: u64 = 5 * 60 * 1_000;
const MAX_ENTRIES: usize = 4_096;

#[derive(Serialize, Deserialize)]
struct Entry {
    id: String,
    sent_at_ms: u64,
}

#[derive(Default)]
pub(crate) struct Ledger {
    /// Rust-only clock seam, shared with the Chat retention tests.
    clock_shift_ms: AtomicU64,
}

impl Ledger {
    pub fn shift_clock(&self, by: Duration) {
        self.clock_shift_ms
            .fetch_add(by.as_millis() as u64, Ordering::Relaxed);
    }

    /// Persist the ID before returning a notice. A file lock also serializes
    /// independent core handles (including background delivery after a restart).
    pub fn record(
        &self,
        store: &Store,
        notice: &PushNotice,
        envelope: &[u8],
    ) -> Result<(), CoreError> {
        let wall = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(storage)?
            .as_millis() as u64;
        let now = wall.saturating_add(self.clock_shift_ms.load(Ordering::Relaxed));
        let sent_at = match &notice.content {
            PushContent::Test { sent_at } => sent_at.saturating_mul(1_000),
            PushContent::Status { at, .. } | PushContent::Progress { at, .. } => {
                at.saturating_mul(1_000)
            }
            PushContent::Call { at_ms, .. } => *at_ms,
        };
        if sent_at > now.saturating_add(FUTURE_SKEW_MS) {
            return Err(CoreError::PushRejected {
                reason: "push timestamp is in the future".into(),
            });
        }
        if now.saturating_sub(sent_at) > MAX_AGE_MS {
            return Err(CoreError::PushRejected {
                reason: "push envelope expired".into(),
            });
        }
        let lock = std::fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(store.root().join("push-replay.lock"))
            .map_err(storage)?;
        lock.lock().map_err(storage)?;
        let dir = store.host_dir(&notice.host_id);
        let path = dir.join("push-replay.json");
        let mut entries: Vec<Entry> = match std::fs::read(&path) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(storage)?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
            Err(e) => return Err(storage(e)),
        };
        entries.retain(|entry| now.saturating_sub(entry.sent_at_ms) <= MAX_AGE_MS);
        let id: String = Sha256::digest(envelope)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        if entries.iter().any(|entry| entry.id == id) {
            return Err(CoreError::PushRejected {
                reason: "push envelope already received".into(),
            });
        }
        entries.push(Entry {
            id,
            sent_at_ms: sent_at,
        });
        if entries.len() > MAX_ENTRIES {
            entries.drain(..entries.len() - MAX_ENTRIES);
        }
        let tmp = dir.join(".push-replay.json.tmp");
        std::fs::write(&tmp, serde_json::to_vec(&entries).map_err(storage)?).map_err(storage)?;
        std::fs::File::open(&tmp)
            .and_then(|f| f.sync_all())
            .map_err(storage)?;
        std::fs::rename(&tmp, path).map_err(storage)?;
        std::fs::File::open(&dir)
            .and_then(|f| f.sync_all())
            .map_err(storage)
    }
}
