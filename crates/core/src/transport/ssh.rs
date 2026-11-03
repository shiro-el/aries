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
/// trust-on-first-use Host key checking.
pub(crate) struct Ssh {
    pub address: String,
    pub port: u16,
    pub user: String,
    pub key: PrivateKey,
    /// Trusted `SHA256:...` fingerprint, `None` before the first connection.
    pub trusted: Option<String>,
}

struct Checker {
    trusted: Option<String>,
    outcome: Arc<Mutex<Option<HostKeyCheck>>>,
}

impl client::Handler for Checker {
    type Error = russh::Error;

    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        let fingerprint = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.fingerprint(HashAlg::Sha256),
            PublicKeyOrCertificate::Certificate(cert) => cert.public_key().fingerprint(HashAlg::Sha256),
        }
        .to_string();
        let check = match &self.trusted {
            None => HostKeyCheck::Unknown { fingerprint },
            Some(t) if *t == fingerprint => HostKeyCheck::Trusted,
            Some(t) => HostKeyCheck::Changed { expected: t.clone(), actual: fingerprint },
        };
        let ok = check == HostKeyCheck::Trusted;
        *self.outcome.lock().expect("outcome lock") = Some(check);
        Ok(ok)
    }
}

impl Ssh {
    async fn open_inner(&self) -> Result<Stream, CoreError> {
        let config = Arc::new(client::Config {
            keepalive_interval: Some(Duration::from_secs(15)),
            keepalive_max: 4,
            ..Default::default()
        });
        let outcome = Arc::new(Mutex::new(None));
        let checker = Checker { trusted: self.trusted.clone(), outcome: outcome.clone() };
        let addr = (self.address.as_str(), self.port);
        let connected = tokio::time::timeout(CONNECT_TIMEOUT, client::connect(config, addr, checker))
            .await
            .map_err(|_| CoreError::Unreachable { detail: "timed out".into() })?;
        let mut session = match connected {
            Ok(s) => s,
            Err(e) => {
                return Err(match outcome.lock().expect("outcome lock").take() {
                    Some(HostKeyCheck::Unknown { fingerprint }) => CoreError::HostKeyUnknown { fingerprint },
                    Some(HostKeyCheck::Changed { expected, actual }) => {
                        CoreError::HostKeyChanged { expected, actual }
                    }
                    _ => CoreError::Unreachable { detail: e.to_string() },
                });
            }
        };
        let auth = session
            .authenticate_publickey(
                self.user.clone(),
                PrivateKeyWithHashAlg::new(Arc::new(self.key.clone()), None),
            )
            .await
            .map_err(|e| CoreError::Unreachable { detail: e.to_string() })?;
        if !auth.success() {
            return Err(CoreError::AuthRejected);
        }
        let channel = session
            .channel_open_session()
            .await
            .map_err(|e| CoreError::Unreachable { detail: e.to_string() })?;
        channel
            .exec(true, REMOTE_COMMAND)
            .await
            .map_err(|e| CoreError::Unreachable { detail: e.to_string() })?;
        let (reader, writer) = tokio::io::split(channel.into_stream());
        Ok(Stream {
            reader: Box::new(reader),
            writer: Box::new(writer),
            guard: Box::new(Mutex::new(session)),
        })
    }
}

impl Transport for Ssh {
    fn open(&self) -> OpenFuture<'_> {
        Box::pin(self.open_inner())
    }
}
