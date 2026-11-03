use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client;
use russh::keys::{HashAlg, PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};

use super::{OpenFuture, Stream, Transport};
use crate::error::CoreError;

/// What runs on the Host. No PTY is requested, so the stdio carries only the
/// protocol. Same lookup herdr-core itself uses for remote machines.
const REMOTE_COMMAND: &str =
    r#"sh -c 'p=$(command -v herdr-core) || p="$HOME/.local/bin/herdr-core"; exec "$p" connect'"#;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// Outcome of comparing the Host's key with the trusted fingerprint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum HostKeyCheck {
    Trusted,
    Unknown { fingerprint: String },
    Changed { expected: String, actual: String },
}

/// SSH exec channel to `herdr-core connect` with public-key auth and
/// trust-on-first-use Host key checking, optionally through jump hosts.
pub(crate) struct Ssh {
    pub address: String,
    pub port: u16,
    pub user: String,
    pub key: PrivateKey,
    /// Trusted `SHA256:...` fingerprint, `None` before the first connection.
    pub trusted: Option<String>,
    /// Jump hosts in order: the first is reached directly, each next one
    /// (and finally the Host) through a `direct-tcpip` channel of the last.
    pub jumps: Vec<SshHop>,
}

/// One jump host, with its own key and trusted fingerprint.
pub(crate) struct SshHop {
    pub address: String,
    pub port: u16,
    pub user: String,
    pub key: PrivateKey,
    pub trusted: Option<String>,
}

struct Checker {
    trusted: Option<String>,
    outcome: Arc<Mutex<Option<HostKeyCheck>>>,
}

impl client::Handler for Checker {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        let fingerprint = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.fingerprint(HashAlg::Sha256),
            PublicKeyOrCertificate::Certificate(cert) => {
                cert.public_key().fingerprint(HashAlg::Sha256)
            }
        }
        .to_string();
        let check = match &self.trusted {
            None => HostKeyCheck::Unknown { fingerprint },
            Some(t) if *t == fingerprint => HostKeyCheck::Trusted,
            Some(t) => HostKeyCheck::Changed {
                expected: t.clone(),
                actual: fingerprint,
            },
        };
        let ok = check == HostKeyCheck::Trusted;
        *self.outcome.lock().expect("outcome lock") = Some(check);
        Ok(ok)
    }
}

/// Why one hop (a jump host or the Host) failed.
enum HopFailure {
    Check(HostKeyCheck),
    AuthRejected,
    Unreachable(String),
}

/// Who the SSH session is with, so failures name the right hop.
#[derive(Clone, Copy)]
enum Role {
    Jump(u32),
    Host,
}

impl HopFailure {
    fn into_error(self, role: Role, address: &str) -> CoreError {
        let address = address.to_owned();
        match (role, self) {
            (Role::Host, HopFailure::Check(HostKeyCheck::Unknown { fingerprint })) => {
                CoreError::HostKeyUnknown { fingerprint }
            }
            (Role::Host, HopFailure::Check(HostKeyCheck::Changed { expected, actual })) => {
                CoreError::HostKeyChanged { expected, actual }
            }
            (Role::Host, HopFailure::AuthRejected) => CoreError::AuthRejected,
            (Role::Host, HopFailure::Unreachable(detail)) => CoreError::Unreachable { detail },
            (Role::Jump(hop), HopFailure::Check(HostKeyCheck::Unknown { fingerprint })) => {
                CoreError::JumpHostKeyUnknown {
                    hop,
                    address,
                    fingerprint,
                }
            }
            (Role::Jump(hop), HopFailure::Check(HostKeyCheck::Changed { expected, actual })) => {
                CoreError::JumpHostKeyChanged {
                    hop,
                    address,
                    expected,
                    actual,
                }
            }
            (Role::Jump(hop), HopFailure::AuthRejected) => {
                CoreError::JumpAuthRejected { hop, address }
            }
            (Role::Jump(hop), HopFailure::Unreachable(detail)) => CoreError::Unreachable {
                detail: format!("jump host {} ({address}): {detail}", hop + 1),
            },
            (_, HopFailure::Check(HostKeyCheck::Trusted)) => CoreError::Unreachable {
                detail: format!("{address}: connection refused after the key check"),
            },
        }
    }
}

type Session = client::Handle<Checker>;

/// Opens an authenticated session to one hop, directly or through `via`.
async fn reach(
    config: Arc<client::Config>,
    via: Option<&Session>,
    address: &str,
    port: u16,
    user: &str,
    key: &PrivateKey,
    trusted: Option<String>,
) -> Result<Session, HopFailure> {
    let outcome = Arc::new(Mutex::new(None));
    let checker = Checker {
        trusted,
        outcome: outcome.clone(),
    };
    let unreachable = |e: russh::Error| HopFailure::Unreachable(e.to_string());
    let connected = match via {
        None => {
            tokio::time::timeout(
                CONNECT_TIMEOUT,
                client::connect(config, (address, port), checker),
            )
            .await
        }
        Some(prev) => {
            let channel = prev
                .channel_open_direct_tcpip(address, u32::from(port), "127.0.0.1", 0)
                .await
                .map_err(unreachable)?;
            tokio::time::timeout(
                CONNECT_TIMEOUT,
                client::connect_stream(config, channel.into_stream(), checker),
            )
            .await
        }
    }
    .map_err(|_| HopFailure::Unreachable("timed out".into()))?;
    let mut session = match connected {
        Ok(s) => s,
        Err(e) => {
            return Err(match outcome.lock().expect("outcome lock").take() {
                Some(check @ (HostKeyCheck::Unknown { .. } | HostKeyCheck::Changed { .. })) => {
                    HopFailure::Check(check)
                }
                _ => unreachable(e),
            });
        }
    };
    let auth = session
        .authenticate_publickey(
            user.to_owned(),
            PrivateKeyWithHashAlg::new(Arc::new(key.clone()), None),
        )
        .await
        .map_err(unreachable)?;
    if !auth.success() {
        return Err(HopFailure::AuthRejected);
    }
    Ok(session)
}

impl Ssh {
    async fn open_inner(&self) -> Result<Stream, CoreError> {
        let config = Arc::new(client::Config {
            keepalive_interval: Some(Duration::from_secs(15)),
            keepalive_max: 4,
            ..Default::default()
        });
        let mut sessions: Vec<Session> = Vec::with_capacity(self.jumps.len() + 1);
        for (i, hop) in self.jumps.iter().enumerate() {
            let session = reach(
                config.clone(),
                sessions.last(),
                &hop.address,
                hop.port,
                &hop.user,
                &hop.key,
                hop.trusted.clone(),
            )
            .await
            .map_err(|f| f.into_error(Role::Jump(i as u32), &hop.address))?;
            sessions.push(session);
        }
        let session = reach(
            config,
            sessions.last(),
            &self.address,
            self.port,
            &self.user,
            &self.key,
            self.trusted.clone(),
        )
        .await
        .map_err(|f| f.into_error(Role::Host, &self.address))?;
        let unreachable = |e: russh::Error| CoreError::Unreachable {
            detail: e.to_string(),
        };
        let channel = session.channel_open_session().await.map_err(unreachable)?;
        channel
            .exec(true, REMOTE_COMMAND)
            .await
            .map_err(unreachable)?;
        sessions.push(session);
        let (reader, writer) = tokio::io::split(channel.into_stream());
        Ok(Stream {
            reader: Box::new(reader),
            writer: Box::new(writer),
            // The jump sessions carry the Host's; keep them all alive.
            guard: Box::new(Mutex::new(sessions)),
        })
    }
}

impl Transport for Ssh {
    fn open(&self) -> OpenFuture<'_> {
        Box::pin(self.open_inner())
    }
}
