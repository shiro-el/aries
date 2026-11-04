//! Byte streams to a Host's `herdr-core connect`.
//!
//! [`Transport`] is the swap point: SSH for real Hosts, a local child process
//! for tests and development, and later a relay-based transport.

mod bootstrap;
mod local;
pub(crate) mod ssh;
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
    pub ssh: Option<std::sync::Arc<ssh::Session>>,
    pub command_exit: Option<tokio::sync::oneshot::Receiver<CommandExit>>,
}

pub(crate) type OpenFuture<'a> =
    Pin<Box<dyn Future<Output = Result<Stream, CoreError>> + Send + 'a>>;

pub(crate) trait Transport: Send + Sync {
    /// Opens a stream whose other end is `herdr-core connect` on the Host.
    fn open(&self) -> OpenFuture<'_>;
}

/// Bounded command diagnostics, kept separate from protocol stdout.
#[derive(Default)]
pub(crate) struct CommandExit {
    pub status: Option<u32>,
    pub stderr: Vec<u8>,
}
impl CommandExit {
    pub fn missing_core(&self) -> bool {
        let text = String::from_utf8_lossy(&self.stderr).to_ascii_lowercase();
        self.status == Some(127)
            || text.contains("herdr-core: not found")
            || text.contains("herdr-core: command not found")
            || text.contains("command not found: herdr-core")
            || text.contains("herdr-core: no such file or directory")
    }
    pub fn stderr(&mut self, bytes: &[u8]) {
        let remaining = 4096_usize.saturating_sub(self.stderr.len());
        self.stderr
            .extend_from_slice(&bytes[..bytes.len().min(remaining)]);
    }
}

/// Cancelling a stream must also retire its command pump/child.
pub(crate) struct CommandTask(pub tokio::task::AbortHandle);
impl Drop for CommandTask {
    fn drop(&mut self) {
        self.0.abort();
    }
}
