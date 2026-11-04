use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client;
use russh::keys::{HashAlg, PrivateKeyWithHashAlg, PublicKeyOrCertificate};

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
    pub key: crate::key_unlock::SshKey,
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
    pub key: crate::key_unlock::SshKey,
    pub trusted: Option<String>,
}

pub(crate) struct Checker {
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
    Key(CoreError),
    Check(HostKeyCheck),
    AuthRejected,
    Denied(String),
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
            (_, HopFailure::Key(error)) => error,
            (_, HopFailure::Denied(detail)) => CoreError::Rpc {
                kind: "permission_denied".into(),
                detail: format!("{address}: {detail}"),
            },
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

/// Owns the SSH pump as well as its command channels. russh's Handle does
/// not disconnect on drop; explicitly retire it when an opening future or
/// the final stream is dropped, including cancellation during authentication.
pub(crate) struct Session {
    handle: Option<client::Handle<Checker>>,
    _socket: Option<SocketOwner>,
}

/// Closing the transport locally also stops a russh pump stalled in I/O;
/// merely queuing an SSH disconnect cannot guarantee that.
struct SocketOwner(std::net::TcpStream);
impl Drop for SocketOwner {
    fn drop(&mut self) {
        let _ = self.0.shutdown(std::net::Shutdown::Both);
    }
}

impl std::ops::Deref for Session {
    type Target = client::Handle<Checker>;
    fn deref(&self) -> &Self::Target {
        self.handle.as_ref().expect("SSH session")
    }
}
impl std::ops::DerefMut for Session {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.handle.as_mut().expect("SSH session")
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        if let Some(session) = self.handle.take() {
            crate::runtime().spawn(async move {
                let _ = crate::network_timeout::timeout(
                    Duration::from_secs(2),
                    session.disconnect(russh::Disconnect::ByApplication, "", ""),
                )
                .await;
            });
        }
    }
}

fn client_config() -> Arc<client::Config> {
    Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(15)),
        keepalive_max: 4,
        // Keystrokes are tiny writes: with Nagle on, each one after the
        // first waits for the previous one's (delayed) ACK, ~40-60 ms.
        nodelay: true,
        ..Default::default()
    })
}

/// Opens an SSH session to one hop, directly or through `via`, and checks
/// its Host key against `trusted` (trust on first use). Not authenticated.
async fn handshake_hop(
    config: Arc<client::Config>,
    via: Option<&Session>,
    address: &str,
    port: u16,
    trusted: Option<String>,
) -> Result<Session, HopFailure> {
    let outcome = Arc::new(Mutex::new(None));
    let checker = Checker {
        trusted,
        outcome: outcome.clone(),
    };
    let unreachable = |e: russh::Error| {
        if matches!(
            e,
            russh::Error::ChannelOpenFailure(russh::ChannelOpenFailure::AdministrativelyProhibited)
        ) {
            HopFailure::Denied(e.to_string())
        } else {
            HopFailure::Unreachable(e.to_string())
        }
    };
    let mut socket = None;

    let connected = match via {
        None => {
            crate::network_timeout::timeout(CONNECT_TIMEOUT, async {
                let tcp = tokio::net::TcpStream::connect((address, port)).await?;
                tcp.set_nodelay(true)?;
                let tcp = tcp.into_std()?;
                socket = Some(SocketOwner(tcp.try_clone()?));
                client::connect_stream(config, tokio::net::TcpStream::from_std(tcp)?, checker).await
            })
            .await
        }
        Some(prev) => {
            let channel = prev
                .channel_open_direct_tcpip(address, u32::from(port), "127.0.0.1", 0)
                .await
                .map_err(unreachable)?;
            crate::network_timeout::timeout(
                CONNECT_TIMEOUT,
                client::connect_stream(config, channel.into_stream(), checker),
            )
            .await
        }
    }
    .map_err(|_| HopFailure::Unreachable("timed out".into()))?;
    connected
        .map(|session| Session {
            handle: Some(session),
            _socket: socket.take(),
        })
        .map_err(|e| match outcome.lock().expect("outcome lock").take() {
            Some(check @ (HostKeyCheck::Unknown { .. } | HostKeyCheck::Changed { .. })) => {
                HopFailure::Check(check)
            }
            _ => unreachable(e),
        })
}

/// Opens a session to one hop, like [`handshake_hop`], and signs in with `key`.
async fn reach(
    config: Arc<client::Config>,
    via: Option<&Session>,
    address: &str,
    port: u16,
    user: &str,
    key: &crate::key_unlock::SshKey,
    trusted: Option<String>,
) -> Result<Session, HopFailure> {
    let mut session = handshake_hop(config, via, address, port, trusted).await?;
    let lease = key.open().await.map_err(HopFailure::Key)?;
    let key = &lease.key;
    // RSA keys imported from OpenSSH need RSA-SHA2 on modern sshd. `None`
    // would select legacy SHA-1, which those servers reject. Negotiate for
    // each hop; absent extension information, prefer SHA-256.
    let hash_alg = if key.algorithm().is_rsa() {
        session
            .best_supported_rsa_hash()
            .await
            .map_err(|e| HopFailure::Unreachable(e.to_string()))?
            .unwrap_or(Some(HashAlg::Sha256))
    } else {
        None
    };
    let auth = session
        .authenticate_publickey(
            user.to_owned(),
            PrivateKeyWithHashAlg::new(key.clone(), hash_alg),
        )
        .await
        .map_err(|e| HopFailure::Unreachable(e.to_string()))?;
    if !auth.success() {
        return Err(HopFailure::AuthRejected);
    }
    Ok(session)
}

/// Signs in to every jump host in order. The Host is reached through the
/// last session; all of them must stay alive while it is used.
async fn through_jumps(
    config: &Arc<client::Config>,
    jumps: &[SshHop],
) -> Result<Vec<Session>, CoreError> {
    let mut sessions: Vec<Session> = Vec::with_capacity(jumps.len() + 1);
    for (i, hop) in jumps.iter().enumerate() {
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
    Ok(sessions)
}

/// Opens an SSH session to the Host at `address:port` through `jumps` and
/// checks its Host key against `trusted`. Authentication to the Host is up
/// to the caller. Returns the jump sessions, which must outlive the Host's.
pub(crate) async fn handshake(
    jumps: &[SshHop],
    address: &str,
    port: u16,
    trusted: Option<String>,
) -> Result<(Vec<Session>, Session), CoreError> {
    crate::network_timeout::timeout(CONNECT_TIMEOUT, async {
        let config = client_config();
        let sessions = through_jumps(&config, jumps).await?;
        let session = handshake_hop(config, sessions.last(), address, port, trusted)
            .await
            .map_err(|f| f.into_error(Role::Host, address))?;
        Ok((sessions, session))
    })
    .await
    .map_err(|_| unreachable_err("SSH handshake timed out"))?
}

pub(crate) fn unreachable_err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Unreachable {
        detail: e.to_string(),
    }
}

/// A reachable SSH peer may forbid command channels or forwarding. Preserve
/// that denial across UniFFI using the existing typed RPC error contract.
fn ssh_error(error: russh::Error) -> CoreError {
    if matches!(
        error,
        russh::Error::ChannelOpenFailure(russh::ChannelOpenFailure::AdministrativelyProhibited)
    ) {
        CoreError::Rpc {
            kind: "permission_denied".into(),
            detail: error.to_string(),
        }
    } else {
        unreachable_err(error)
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
    /// Connects through the jump hosts, checks every Host key and signs in
    /// with each hop's key. Returns the jump sessions, which carry the
    /// Host's and must outlive it, and the Host session.
    async fn session_unbounded(&self) -> Result<(Vec<Session>, Session), CoreError> {
        let config = client_config();
        let jumps = through_jumps(&config, &self.jumps).await?;
        let session = reach(
            config,
            jumps.last(),
            &self.address,
            self.port,
            &self.user,
            &self.key,
            self.trusted.clone(),
        )
        .await
        .map_err(|f| f.into_error(Role::Host, &self.address))?;
        Ok((jumps, session))
    }

    async fn session(&self) -> Result<(Vec<Session>, Session), CoreError> {
        crate::network_timeout::timeout(CONNECT_TIMEOUT, self.session_unbounded())
            .await
            .map_err(|_| unreachable_err("SSH sign-in timed out"))?
    }

    /// Runs `command` on the Host (no PTY), feeds it `stdin` then EOF, and
    /// collects its output.
    pub async fn exec(&self, command: &str, stdin: &[u8]) -> Result<ExecOutput, CoreError> {
        crate::network_timeout::timeout(
            Duration::from_secs(60),
            self.exec_unbounded(command, stdin),
        )
        .await
        .map_err(|_| crate::error::protocol("SSH command timed out; its result is unknown"))?
    }

    async fn exec_unbounded(&self, command: &str, stdin: &[u8]) -> Result<ExecOutput, CoreError> {
        let (_jumps, session) = self.session().await?;
        let mut channel = session.channel_open_session().await.map_err(ssh_error)?;
        channel.exec(true, command).await.map_err(ssh_error)?;
        if !stdin.is_empty() {
            channel.data(stdin).await.map_err(ssh_error)?;
        }
        channel.eof().await.map_err(ssh_error)?;
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

    /// Runs `command` on the Host (no PTY) and streams its stdio. Dropping
    /// the stream closes the channel.
    pub async fn exec_stream(&self, command: &str) -> Result<Stream, CoreError> {
        self.exec_stream_within(command, CONNECT_TIMEOUT).await
    }

    /// One budget covers all hops, authentication, channel opening and exec
    /// submission. After submission a timeout must not imply safe replay.
    pub async fn exec_stream_within(
        &self,
        command: &str,
        sign_in: Duration,
    ) -> Result<Stream, CoreError> {
        let mut submitted = false;
        crate::network_timeout::timeout(sign_in, async {
            let (sessions, session) = self.session_unbounded().await?;
            Self::stream_on(sessions, session, command, &mut submitted).await
        })
        .await
        .map_err(|_| {
            if submitted {
                crate::error::protocol("SSH exec timed out; its result is unknown")
            } else {
                unreachable_err(format!(
                    "{}: no answer within {} s",
                    self.address,
                    sign_in.as_secs_f32()
                ))
            }
        })?
    }

    async fn stream_on(
        sessions: Vec<Session>,
        session: Session,
        command: &str,
        submitted: &mut bool,
    ) -> Result<Stream, CoreError> {
        let mut channel = session.channel_open_session().await.map_err(ssh_error)?;
        *submitted = true;
        channel
            .exec(true, command)
            .await
            .map_err(|e| crate::error::protocol(format!("SSH exec result is unknown: {e}")))?;
        // russh's exec() only enqueues the request. Wait for the Host's
        // acknowledgement under the same opening deadline. Some Hosts send
        // stdout first; that also proves execution, and must be replayed.
        let mut outcome = super::CommandExit::default();
        let prefix = loop {
            match channel.wait().await {
                Some(russh::ChannelMsg::Success) => break Vec::new(),
                Some(russh::ChannelMsg::Data { data }) => break data.to_vec(),
                Some(russh::ChannelMsg::Failure) => {
                    return Err(CoreError::Rpc {
                        kind: "permission_denied".into(),
                        detail: "SSH exec was refused".into(),
                    });
                }
                Some(russh::ChannelMsg::ExtendedData { data, ext: 1 }) => outcome.stderr(&data),
                Some(russh::ChannelMsg::ExitStatus { exit_status }) => {
                    outcome.status = Some(exit_status)
                }
                Some(russh::ChannelMsg::Close) | None => {
                    if command == REMOTE_COMMAND && outcome.missing_core() {
                        return Err(CoreError::HerdrCoreMissing);
                    }
                    return Err(crate::error::protocol(
                        "SSH exec ended before acknowledgement; its result is unknown",
                    ));
                }
                _ => {}
            }
        };
        let session = Arc::new(session);
        let (reader, writer, command_exit, task) = if command == REMOTE_COMMAND {
            // into_stream discards stderr and exit status. Preserve those until
            // hello succeeds, so a missing executable is not retried as a network loss.
            let (mut incoming, outgoing) = channel.split();
            let writer =
                Box::new(outgoing.make_writer()) as Box<dyn tokio::io::AsyncWrite + Send + Unpin>;
            let (reader, mut output) = tokio::io::duplex(64 * 1024);
            let (tx, rx) = tokio::sync::oneshot::channel();
            let task = tokio::spawn(async move {
                use tokio::io::AsyncWriteExt;
                while let Some(msg) = incoming.wait().await {
                    match msg {
                        russh::ChannelMsg::Data { data } => {
                            if output.write_all(&data).await.is_err() {
                                return;
                            }
                        }
                        russh::ChannelMsg::ExtendedData { data, ext: 1 } => outcome.stderr(&data),
                        russh::ChannelMsg::ExitStatus { exit_status } => {
                            outcome.status = Some(exit_status)
                        }
                        russh::ChannelMsg::Close => break,
                        _ => {}
                    }
                }
                let _ = tx.send(outcome);
            });
            (
                Box::new(reader) as Box<dyn tokio::io::AsyncRead + Send + Unpin>,
                writer,
                Some(rx),
                Some(super::CommandTask(task.abort_handle())),
            )
        } else {
            let (reader, writer) = tokio::io::split(channel.into_stream());
            (
                Box::new(reader) as Box<dyn tokio::io::AsyncRead + Send + Unpin>,
                Box::new(writer) as Box<dyn tokio::io::AsyncWrite + Send + Unpin>,
                None,
                None,
            )
        };
        use tokio::io::AsyncReadExt;
        Ok(Stream {
            reader: Box::new(std::io::Cursor::new(prefix).chain(reader)),
            writer: Box::new(writer),
            // The jump sessions carry the Host's; keep them all alive.
            guard: Box::new((Mutex::new(sessions), session.clone(), task)),
            command_exit,
            ssh: Some(session),
        })
    }
}

impl Transport for Ssh {
    fn open(&self) -> OpenFuture<'_> {
        Box::pin(self.exec_stream(REMOTE_COMMAND))
    }
}
