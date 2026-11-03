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

pub(super) struct Checker {
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

/// Opens an SSH session to `address:port` and checks the Host key against
/// `trusted` (trust on first use). Authentication is up to the caller.
pub(super) async fn handshake(
    address: &str,
    port: u16,
    trusted: Option<String>,
) -> Result<client::Handle<Checker>, CoreError> {
    let config = Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(15)),
        keepalive_max: 4,
        ..Default::default()
    });
    let outcome = Arc::new(Mutex::new(None));
    let checker = Checker {
        trusted,
        outcome: outcome.clone(),
    };
    let connected = tokio::time::timeout(
        CONNECT_TIMEOUT,
        client::connect(config, (address, port), checker),
    )
    .await
    .map_err(|_| CoreError::Unreachable {
        detail: "timed out".into(),
    })?;
    connected.map_err(|e| match outcome.lock().expect("outcome lock").take() {
        Some(HostKeyCheck::Unknown { fingerprint }) => CoreError::HostKeyUnknown { fingerprint },
        Some(HostKeyCheck::Changed { expected, actual }) => {
            CoreError::HostKeyChanged { expected, actual }
        }
        _ => unreachable_err(e),
    })
}

pub(super) fn unreachable_err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Unreachable {
        detail: e.to_string(),
    }
}

/// What a command run with [`Ssh::exec`] produced.
pub(crate) struct ExecOutput {
    /// `None` when the Host did not report an exit status.
    pub status: Option<u32>,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
}

impl ExecOutput {
    pub fn success(&self) -> bool {
        self.status == Some(0)
    }
}

impl Ssh {
    /// Connects, checks the Host key and authenticates with the app's key.
    async fn session(&self) -> Result<client::Handle<Checker>, CoreError> {
        let mut session = handshake(&self.address, self.port, self.trusted.clone()).await?;
        let auth = session
            .authenticate_publickey(
                self.user.clone(),
                PrivateKeyWithHashAlg::new(Arc::new(self.key.clone()), None),
            )
            .await
            .map_err(unreachable_err)?;
        if !auth.success() {
            return Err(CoreError::AuthRejected);
        }
        Ok(session)
    }

    /// Runs `command` on the Host (no PTY), feeds it `stdin` then EOF, and
    /// collects its output.
    pub async fn exec(&self, command: &str, stdin: &[u8]) -> Result<ExecOutput, CoreError> {
        let session = self.session().await?;
        let mut channel = session
            .channel_open_session()
            .await
            .map_err(unreachable_err)?;
        channel.exec(true, command).await.map_err(unreachable_err)?;
        if !stdin.is_empty() {
            channel.data(stdin).await.map_err(unreachable_err)?;
        }
        channel.eof().await.map_err(unreachable_err)?;
        let mut out = ExecOutput {
            status: None,
            stdout: Vec::new(),
            stderr: Vec::new(),
        };
        while let Some(msg) = channel.wait().await {
            match msg {
                russh::ChannelMsg::Data { data } => out.stdout.extend_from_slice(&data),
                russh::ChannelMsg::ExtendedData { data, ext: 1 } => {
                    out.stderr.extend_from_slice(&data)
                }
                russh::ChannelMsg::ExitStatus { exit_status } => out.status = Some(exit_status),
                _ => {}
            }
        }
        let _ = session
            .disconnect(russh::Disconnect::ByApplication, "", "")
            .await;
        Ok(out)
    }

    async fn open_inner(&self) -> Result<Stream, CoreError> {
        let session = self.session().await?;
        let channel = session
            .channel_open_session()
            .await
            .map_err(unreachable_err)?;
        channel
            .exec(true, REMOTE_COMMAND)
            .await
            .map_err(unreachable_err)?;
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
