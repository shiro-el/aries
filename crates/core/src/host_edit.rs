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
    clients: Vec<Weak<HcClient>>,
    chats: Vec<Weak<ChatWatch>>,
    calls: Vec<Weak<CallWatch>>,
}

pub(crate) type HostSessions = Mutex<HashMap<String, Sessions>>;

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
    /// Edits a Host in place. Endpoint changes forget its trusted Host key
    /// and close its connections and Beacon watches. Name-only edits keep
    /// them. All state addressed by Host id stays in place.
    pub async fn edit_host(
        &self,
        host_id: String,
        name: String,
        endpoint: Endpoint,
    ) -> Result<Host, CoreError> {
        let (host, clients) = {
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
            let changed = host.endpoint != endpoint;
            host.name = name;
            host.endpoint = endpoint;
            if changed {
                host.host_key = None;
            }
            let result = to_host(host);
            // Publish only after saving succeeds. Holding the session lock
            // across publication prevents a stale handshake from registering.
            let mut sessions = self.sessions.lock().expect("sessions lock");
            self.store.save(&crate::store::Saved {
                hosts: next.clone(),
            })?;
            *hosts = next;
            let mut clients = Vec::new();
            if changed {
                let session = sessions.entry(host_id).or_default();
                session.revision += 1;
                clients = std::mem::take(&mut session.clients)
                    .into_iter()
                    .filter_map(|c| c.upgrade())
                    .collect();
                for chat in std::mem::take(&mut session.chats)
                    .into_iter()
                    .filter_map(|w| w.upgrade())
                {
                    chat.stop();
                }
                for calls in std::mem::take(&mut session.calls)
                    .into_iter()
                    .filter_map(|w| w.upgrade())
                {
                    calls.stop();
                }
            }
            (result, clients)
        };
        crate::on_runtime(async move {
            for client in clients {
                client.close().await;
            }
            Ok(())
        })
        .await?;
        Ok(host)
    }
}

impl AriesCore {
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
        if session.revision != revision {
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
        if session.revision != revision {
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
        if session.revision != revision {
            watch.stop();
            return Err(CoreError::Closed);
        }
        session.calls.retain(|w| w.strong_count() > 0);
        session.calls.push(Arc::downgrade(&watch));
        Ok(watch)
    }
}
