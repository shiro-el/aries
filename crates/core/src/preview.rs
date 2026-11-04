//! Screen-owned loopback preview; never opens a second SSH connection.
use crate::{CoreError, HostConnection, error::protocol, on_runtime};
use std::sync::Arc;

/// Extract localhost endpoints from Agent prose/Terminal output without
/// interpreting unrelated numbers, lookalike hostnames or truncated ports.
pub fn preview_ports(output: String) -> Vec<u16> {
    let mut ports = Vec::new();
    for (at, _) in output.char_indices() {
        if at > 0 && output[..at].chars().next_back().is_some_and(host_char) {
            continue;
        }
        let rest = &output[at..];
        let Some(rest) = ["localhost:", "127.0.0.1:", "0.0.0.0:"]
            .iter()
            .find_map(|host| rest.strip_prefix(host))
        else {
            continue;
        };
        let end = rest.bytes().take_while(u8::is_ascii_digit).count();
        if rest[end..].chars().next().is_some_and(host_char) {
            continue;
        }
        if let Ok(port) = rest[..end].parse::<u16>()
            && port != 0
            && !ports.contains(&port)
        {
            ports.push(port);
        }
    }
    ports
}

fn host_char(c: char) -> bool {
    c.is_alphanumeric() || matches!(c, '.' | '_' | '-' | ':')
}

/// Owns the listener and all accepted SSH channels. Stop is idempotent;
/// dropping the last handle also aborts everything without disconnecting SSH.
#[derive(uniffi::Object)]
pub struct PreviewForward {
    url: String,
    task: std::sync::Mutex<Option<tokio::task::JoinHandle<()>>>,
}

#[uniffi::export]
impl PreviewForward {
    pub fn url(&self) -> String {
        self.url.clone()
    }

    pub async fn stop(&self) {
        let task = self.task.lock().expect("preview task lock").take();
        if let Some(task) = task {
            task.abort();
            let _ = task.await;
        }
    }
}

impl Drop for PreviewForward {
    fn drop(&mut self) {
        if let Some(task) = self.task.get_mut().expect("preview task lock").take() {
            task.abort();
        }
    }
}

#[uniffi::export]
impl HostConnection {
    /// Parse output through the same core boundary as port discovery/forwarding.
    pub fn preview_ports(&self, output: String) -> Vec<u16> {
        preview_ports(output)
    }

    /// Forwards exactly Host 127.0.0.1:port through its authenticated SSH
    /// session. There is no user-supplied bind address or remote hostname.
    pub async fn open_preview(&self, port: u16) -> Result<Arc<PreviewForward>, CoreError> {
        if port == 0 {
            return Err(protocol("preview port must be between 1 and 65535"));
        }
        let session = self
            .client
            .ssh
            .as_ref()
            .and_then(std::sync::Weak::upgrade)
            .ok_or_else(|| protocol("preview requires SSH"))?;
        let mut closed = self.client.preview_closed();
        on_runtime(async move {
            if *closed.borrow() { return Err(CoreError::Closed); }
            let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
                .await.map_err(crate::transport::ssh::unreachable_err)?;
            let url = format!("http://{}/", listener.local_addr().map_err(crate::transport::ssh::unreachable_err)?);
            let task = tokio::spawn(async move {
                let mut streams = tokio::task::JoinSet::new();
                loop {
                    tokio::select! {
                        biased;
                        _ = closed.wait_for(|ended| *ended) => break,
                        Some(_) = streams.join_next(), if !streams.is_empty() => {},
                        accepted = listener.accept(), if streams.len() < 32 => {
                            let Ok((mut local, peer)) = accepted else { break };
                            let session = session.clone();
                            streams.spawn(async move {
                                let channel = tokio::time::timeout(std::time::Duration::from_secs(10),
                                    session.channel_open_direct_tcpip("127.0.0.1", u32::from(port), "127.0.0.1", u32::from(peer.port()))).await;
                                if let Ok(Ok(channel)) = channel {
                                    let _ = tokio::io::copy_bidirectional(&mut local, &mut channel.into_stream()).await;
                                }
                            });
                        }
                    }
                }
                // JoinSet drop aborts active streams, including pending channel opens.
            });
            Ok(Arc::new(PreviewForward { url, task: std::sync::Mutex::new(Some(task)) }))
        }).await
    }

    /// Optional discovery source. Missing tools, denied exec and malformed
    /// output yield no ports; they must not interrupt the Agent screen.
    pub async fn preview_listening_ports(&self) -> Vec<u16> {
        let mut ports = Vec::new();
        if let Ok(out) = self
            .machine_exec(vec!["ss".into(), "-H".into(), "-ltn".into()], None, 3)
            .await
            && out.code == Some(0)
            && !out.timed_out
        {
            for line in String::from_utf8_lossy(&out.stdout).lines() {
                if let Some(endpoint) = line.split_whitespace().nth(3) {
                    add_listening_port(endpoint, &mut ports);
                }
            }
            return ports;
        }
        if let Ok(out) = self
            .machine_exec(
                vec![
                    "lsof".into(),
                    "-nP".into(),
                    "-iTCP".into(),
                    "-sTCP:LISTEN".into(),
                    "-Fn".into(),
                ],
                None,
                3,
            )
            .await
            && out.code == Some(0)
            && !out.timed_out
        {
            for line in String::from_utf8_lossy(&out.stdout).lines() {
                if let Some(endpoint) = line.strip_prefix('n') {
                    add_listening_port(endpoint, &mut ports);
                }
            }
        }
        ports
    }
}

fn add_listening_port(endpoint: &str, ports: &mut Vec<u16>) {
    if let Some((host, port)) = endpoint.rsplit_once(':')
        && matches!(host, "127.0.0.1" | "0.0.0.0" | "*" | "[::]" | "[::1]")
        && let Ok(port) = port.parse::<u16>()
        && port != 0
        && !ports.contains(&port)
    {
        ports.push(port);
    }
}
