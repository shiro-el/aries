//! Editing Host identity without replacing any Host-bound state.
use std::collections::HashMap;
use std::sync::{Arc, Mutex, Weak};

use crate::{
    AriesCore, CallWatch, ChatWatch, CoreError, Endpoint, Host, hc::HcClient, store::SavedHost,
    to_host,
};

#[derive(Default)]
pub(crate) struct Sessions {
    revision: u64,
    removed: bool,
    clients: Vec<Weak<HcClient>>,
    chats: Vec<Weak<ChatWatch>>,
    calls: Vec<Weak<CallWatch>>,
}

pub(crate) type HostSessions = Arc<Mutex<HashMap<String, Sessions>>>;

impl Sessions {
    /// Caller holds the registry lock through settings publication or removal.
    /// Keep the revision/tombstone so a late handshake cannot register again.
    pub(crate) fn invalidate(&mut self, removed: bool) {
        self.revision += 1;
        self.removed = removed;
        for client in std::mem::take(&mut self.clients)
            .into_iter()
            .filter_map(|c| c.upgrade())
        {
            client.close_now();
        }
        for chat in std::mem::take(&mut self.chats)
            .into_iter()
            .filter_map(|w| w.upgrade())
        {
            chat.stop();
        }
        for calls in std::mem::take(&mut self.calls)
            .into_iter()
            .filter_map(|w| w.upgrade())
        {
            calls.stop();
        }
    }

    pub(crate) fn check(&self, revision: u64) -> Result<(), CoreError> {
        if self.removed || self.revision != revision {
            Err(CoreError::Closed)
        } else {
            Ok(())
        }
    }
}

pub(crate) fn validate(
    hosts: &[SavedHost],
    except: Option<&str>,
    name: &str,
    endpoint: &Endpoint,
) -> Result<(), CoreError> {
    let invalid = |detail: &str| CoreError::InvalidHost {
        detail: detail.into(),
    };
    if name.trim().is_empty() || name.chars().any(char::is_control) {
        return Err(invalid(
            "Host name must not be empty or contain control characters",
        ));
    }
    if hosts
        .iter()
        .any(|h| Some(h.id.as_str()) != except && h.name.trim() == name.trim())
    {
        return Err(invalid("Host name is already in use"));
    }
    if let Endpoint::Ssh {
        address,
        port,
        user,
    } = endpoint
    {
        let token =
            |s: &str| !s.is_empty() && !s.chars().any(|c| c.is_whitespace() || c.is_control());
        if !token(address) || !token(user) || *port == 0 {
            return Err(invalid(
                "SSH address and user must be nonempty without whitespace; port must be 1..65535",
            ));
        }
    }
    Ok(())
}

#[uniffi::export]
impl AriesCore {
    /// Edits a Host in place. Address/port changes forget its trusted Host key.
    /// Endpoint changes close connections and Beacon watches. Name-only edits keep
    /// them. All state addressed by Host id stays in place.
    pub async fn edit_host(
        &self,
        host_id: String,
        name: String,
        endpoint: Endpoint,
    ) -> Result<Host, CoreError> {
        let host = {
            let mut hosts = self.hosts.lock().expect("hosts lock");
            let index = hosts.iter().position(|h| h.id == host_id).ok_or_else(|| {
                CoreError::UnknownHost {
                    id: host_id.clone(),
                }
            })?;
            let name = name.trim().to_owned();
            validate(&hosts, Some(&host_id), &name, &endpoint)?;
            let mut next = hosts.clone();
            let host = &mut next[index];
            host.name = name;
            if !same_host_key_endpoint(&host.endpoint, &endpoint) {
                host.host_key = None;
            }
            host.endpoint = endpoint;
            let result = to_host(host);
            self.publish_hosts(&mut hosts, next)?;
            result
        };
        Ok(host)
    }
}

impl AriesCore {
    /// The Host lock is already held. Save before invalidating so failed edits
    /// leave both settings and live sessions intact. Registration and local
    /// handle writes cannot cross publication while this registry lock is held.
    pub(crate) fn publish_hosts(
        &self,
        hosts: &mut Vec<SavedHost>,
        next: Vec<SavedHost>,
    ) -> Result<(), CoreError> {
        let mut sessions = self.sessions.lock().expect("sessions lock");
        self.store.save(&crate::store::Saved {
            hosts: next.clone(),
        })?;
        for old in hosts.iter() {
            let new = next.iter().find(|h| h.id == old.id);
            let changed = new.is_none_or(|new| {
                old.endpoint != new.endpoint
                    || old.jumps != new.jumps
                    || old.key_id != new.key_id
                    || old.host_key != new.host_key
            });
            if changed {
                sessions
                    .entry(old.id.clone())
                    .or_default()
                    .invalidate(new.is_none());
            }
        }
        *hosts = next;
        Ok(())
    }

    // Capture before reading endpoint/key settings; registration rejects any
    // edit that raced with opening a transport, even an edit back to the old address.
    pub(crate) fn session_revision(&self, id: &str) -> u64 {
        self.sessions
            .lock()
            .expect("sessions lock")
            .entry(id.to_owned())
            .or_default()
            .revision
    }

    pub(crate) fn register_client(&self, id: &str, revision: u64, client: &Arc<HcClient>) -> bool {
        let mut sessions = self.sessions.lock().expect("sessions lock");
        let session = sessions.entry(id.to_owned()).or_default();
        if session.check(revision).is_err() {
            return false;
        }
        session.clients.retain(|c| c.strong_count() > 0);
        session.clients.push(Arc::downgrade(client));
        true
    }

    pub(crate) fn register_chat(
        &self,
        id: &str,
        revision: u64,
        watch: Arc<ChatWatch>,
    ) -> Result<Arc<ChatWatch>, CoreError> {
        let mut sessions = self.sessions.lock().expect("sessions lock");
        let session = sessions.entry(id.to_owned()).or_default();
        if session.check(revision).is_err() {
            watch.stop();
            return Err(CoreError::Closed);
        }
        session.chats.retain(|w| w.strong_count() > 0);
        session.chats.push(Arc::downgrade(&watch));
        Ok(watch)
    }

    pub(crate) fn register_calls(
        &self,
        id: &str,
        revision: u64,
        watch: Arc<CallWatch>,
    ) -> Result<Arc<CallWatch>, CoreError> {
        let mut sessions = self.sessions.lock().expect("sessions lock");
        let session = sessions.entry(id.to_owned()).or_default();
        if session.check(revision).is_err() {
            watch.stop();
            return Err(CoreError::Closed);
        }
        session.calls.retain(|w| w.strong_count() > 0);
        session.calls.push(Arc::downgrade(&watch));
        Ok(watch)
    }
}

/// SSH server identity is address:port, independent of the account used to log in.
fn same_host_key_endpoint(old: &Endpoint, new: &Endpoint) -> bool {
    match (old, new) {
        (
            Endpoint::Ssh {
                address: a,
                port: p,
                ..
            },
            Endpoint::Ssh {
                address: b,
                port: q,
                ..
            },
        ) => a == b && p == q,
        _ => old == new,
    }
}
