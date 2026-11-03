//! Removing a Host ([`crate::AriesCore::remove_host`]).
//!
//! # Cleanup ownership rule
//!
//! Whatever a ticket creates for one Host (pairing, Chat cache, ...), that
//! ticket also removes here, and tests the removal at the core API seam. To
//! hook in, add one entry to [`STEPS`]: a function that deletes your
//! resource for `removal.host`.
//!
//! A step must be idempotent: it may run again after an earlier attempt
//! failed part way, and must succeed when the resource was never created.
//! Steps run in order; the first failure stops removal and leaves the Host
//! registered, so the user can retry. Keep per-Host files under
//! [`Store::host_dir`]: `remove_local_state` already deletes that tree.
//! Work that needs the network (revoking a pairing on the relay) should not
//! make an offline Host impossible to remove: queue it, or ignore the
//! failure, and record which in the step's doc.
//!
//! The Host record itself (connection settings and trusted Host key
//! fingerprint in `hosts.json`) is dropped last, after every step succeeded.

use crate::error::CoreError;
use crate::store::{SavedHost, Store};

/// What a cleanup step gets to work with.
pub(crate) struct Removal<'a> {
    pub host: &'a SavedHost,
    pub store: &'a Store,
}

pub(crate) type Step = fn(&Removal) -> Result<(), CoreError>;

/// Cleanup steps, in order. Tickets append theirs (see the module doc).
pub(crate) const STEPS: &[Step] = &[queue_push_revocation, remove_local_state, remove_chat_cache];

/// Runs every step for `host`. The caller drops the Host record afterwards.
pub(crate) fn run(host: &SavedHost, store: &Store) -> Result<(), CoreError> {
    let removal = Removal { host, store };
    for step in STEPS {
        step(&removal)?;
    }
    Ok(())
}

/// Deletes the Host's directory under the data dir (`hosts/<id>/`).
fn remove_local_state(r: &Removal) -> Result<(), CoreError> {
    r.store.remove_host_dir(&r.host.id)
}

/// Ticket 27: queues the push pairing's revocation on the Relay (no network
/// here; the app sends the queue with `flush_push_revocations` right after).
/// Must run before [`remove_local_state`], which deletes the pairing file.
fn queue_push_revocation(r: &Removal) -> Result<(), CoreError> {
    crate::push::queue_revocation(r.store, &r.host.id)
}

/// Deletes the Host's cached Chats (ticket 44). Works without the cache key.
fn remove_chat_cache(r: &Removal) -> Result<(), CoreError> {
    crate::chat_cache::remove_host(r.store.root(), &r.host.id)
}
