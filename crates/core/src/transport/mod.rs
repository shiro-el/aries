//! Byte streams to a Host's `herdr-core connect`.
//!
//! [`Transport`] is the swap point: SSH for real Hosts, a local child process
//! for tests and development, and later a relay-based transport.

mod bootstrap;
mod local;
mod ssh;
pub(crate) use ssh::handshake;

use std::future::Future;
use std::pin::Pin;

use tokio::io::{AsyncRead, AsyncWrite};

use crate::error::CoreError;

pub(crate) use bootstrap::PasswordLogin;
pub(crate) use local::LocalProcess;
pub(crate) use ssh::{ExecOutput, Ssh, SshHop};

/// A duplex byte stream plus whatever must stay alive while it is used
/// (child process, SSH session).
pub(crate) struct Stream {
    pub reader: Box<dyn AsyncRead + Send + Unpin>,
    pub writer: Box<dyn AsyncWrite + Send + Unpin>,
    pub guard: Box<dyn Send + Sync>,
}

pub(crate) type OpenFuture<'a> =
    Pin<Box<dyn Future<Output = Result<Stream, CoreError>> + Send + 'a>>;

pub(crate) trait Transport: Send + Sync {
    /// Opens a stream whose other end is `herdr-core connect` on the Host.
    fn open(&self) -> OpenFuture<'_>;
}
