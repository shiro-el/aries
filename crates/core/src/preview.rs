//! Screen-owned loopback preview; never opens a second SSH connection.
use crate::{CoreError, HostConnection, error::protocol, on_runtime};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt};

pub(crate) struct Discovery {
    checked: Instant,
    ports: Vec<u16>,
}

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

    /// Discover HTTP listeners. Only successful scans are cached; a failed scan
    /// remains distinguishable from a Host with no development servers.
    pub async fn preview_listening_ports(&self) -> Result<Vec<u16>, CoreError> {
        let mut closed = self.client.preview_closed();
        tokio::select! {
            biased;
            _ = closed.wait_for(|ended| *ended) => Err(CoreError::Closed),
            result = self.discover_preview_ports() => result,
        }
    }

    async fn discover_preview_ports(&self) -> Result<Vec<u16>, CoreError> {
        // Coalesce concurrent Agent screens; never scan on every output update.
        let mut cache = self.preview_cache.lock().await;
        if let Some(found) = cache.as_ref()
            && found.checked.elapsed() < Duration::from_secs(30)
        {
            return Ok(found.ports.clone());
        }
        let candidates = self.listening_ports().await?;
        let session = self.client.ssh.as_ref().and_then(std::sync::Weak::upgrade);
        let ports = on_runtime(async move {
            let Some(session) = session else {
                return Ok(Vec::new());
            };
            let mut accepted = Vec::new();
            // Bound work even on shared Hosts with hundreds of listeners.
            for batch in candidates.chunks(8).take(8) {
                let mut probes = tokio::task::JoinSet::new();
                for &port in batch {
                    let session = session.clone();
                    probes.spawn(async move {
                        let http = probe_http(&session, port).await;
                        (port, http)
                    });
                }
                while let Some(result) = probes.join_next().await {
                    if let Ok((port, true)) = result {
                        accepted.push(port);
                    }
                }
            }
            Ok(candidates
                .into_iter()
                .filter(|port| accepted.contains(port))
                .collect::<Vec<_>>())
        })
        .await?;
        *cache = Some(Discovery {
            checked: Instant::now(),
            ports: ports.clone(),
        });
        Ok(ports)
    }

    async fn listening_ports(&self) -> Result<Vec<u16>, CoreError> {
        let mut ports = Vec::new();
        let out = self
            .machine_exec(vec!["ss".into(), "-H".into(), "-ltn".into()], None, 3)
            .await;
        let out = match out {
            Ok(out) => Some(out),
            // macOS does not ship ss; lsof is the supported fallback.
            Err(CoreError::Rpc { kind, .. }) if kind == "spawn_failed" => None,
            Err(error) => return Err(error),
        };
        if let Some(out) = out
            && out.code == Some(0)
            && !out.timed_out
            && !out.truncated
        {
            for line in String::from_utf8_lossy(&out.stdout).lines() {
                let endpoint = line
                    .split_whitespace()
                    .nth(3)
                    .ok_or_else(|| protocol("invalid ss listener output"))?;
                add_listening_port(endpoint, &mut ports);
            }
            return Ok(ports);
        }
        let out = self
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
            .await?;
        // lsof exits 1 with no output when there are no matching listeners.
        let empty = out.code == Some(1) && out.stdout.is_empty() && out.stderr.is_empty();
        if (out.code != Some(0) && !empty) || out.timed_out || out.truncated {
            return Err(protocol("preview listener discovery failed"));
        }
        for line in String::from_utf8_lossy(&out.stdout).lines() {
            if let Some(endpoint) = line.strip_prefix('n') {
                add_listening_port(endpoint, &mut ports);
            } else if !line.starts_with('p') && !line.starts_with('f') {
                return Err(protocol("invalid lsof listener output"));
            }
        }
        Ok(ports)
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

/// HEAD detects even 404/405/auth responses without downloading the page or
/// following redirects off the Host. No dependency on curl/python on the Host.
async fn probe_http(session: &crate::transport::ssh::Session, port: u16) -> bool {
    tokio::time::timeout(Duration::from_millis(750), async {
        let channel = session
            .channel_open_direct_tcpip("127.0.0.1", u32::from(port), "127.0.0.1", 0)
            .await
            .ok()?;
        let mut stream = channel.into_stream();
        stream
            .write_all(
                format!("HEAD / HTTP/1.1\r\nHost: localhost:{port}\r\nConnection: close\r\n\r\n")
                    .as_bytes(),
            )
            .await
            .ok()?;
        // Read only a bounded status line, never the page body.
        let mut line = Vec::new();
        let mut reader = tokio::io::BufReader::new(stream);
        while line.len() < 256 {
            let bytes = reader.fill_buf().await.ok()?;
            if bytes.is_empty() {
                return None;
            }
            let count = bytes
                .iter()
                .position(|b| *b == b'\n')
                .map_or(bytes.len(), |i| i + 1)
                .min(256 - line.len());
            line.extend_from_slice(&bytes[..count]);
            reader.consume(count);
            if line.ends_with(b"\n") {
                break;
            }
        }
        let line = std::str::from_utf8(&line).ok()?;
        let mut fields = line.split_whitespace();
        let version = fields.next()?;
        let status = fields.next()?;
        Some(
            matches!(version, "HTTP/1.0" | "HTTP/1.1")
                && status.len() == 3
                && status
                    .parse::<u16>()
                    .is_ok_and(|code| (100..600).contains(&code)),
        )
    })
    .await
    .ok()
    .flatten()
    .unwrap_or(false)
}
