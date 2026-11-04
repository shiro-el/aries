//! herdr-core client: plain JSON-RPC `hello`, then framed mode.
//!
//! Written from herdr-core's public protocol docs (`schema/README.md`,
//! `schema/hc.openrpc.json`, Apache-2.0). Frame layout:
//! `[channel u32 LE][kind u8][len u32 LE][payload]`, channel 0 = JSON-RPC.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::sync::{Mutex, mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::error::{CoreError, Incompatibility, protocol};
use crate::transport::Stream;

/// Protocol generation this app speaks.
pub const GENERATION: u32 = 1;
/// Oldest herdr-core release this app supports.
pub const MIN_CORE_VERSION: &str = "0.1.17";
/// Methods this app calls; a Host lacking any of them is refused.
pub const REQUIRED_METHODS: &[&str] = &[
    "terminal.list",
    "terminal.get",
    "terminal.run",
    "terminal.attach",
    "terminal.send",
    "terminal.put",
    "terminal.keys",
    "terminal.interrupt",
    "terminal.read",
    "terminal.label",
    "doc.get",
    "events.subscribe",
];

/// Bytes of non-protocol output (login banners, shell rc noise) tolerated
/// before the `hello` reply.
const MAX_PREAMBLE: u64 = 64 * 1024;
const HELLO_TIMEOUT: Duration = Duration::from_secs(20);
const CALL_TIMEOUT: Duration = Duration::from_secs(30);

const HEADER: usize = 9;
const MAX_PAYLOAD: usize = 16 << 20;
pub(crate) const KIND_DATA: u8 = 0;
pub(crate) const KIND_CREDIT: u8 = 1;
pub(crate) const KIND_CLOSE: u8 = 2;
pub(crate) const KIND_RESIZE: u8 = 3;

/// One frame the core sent on a stream channel (any kind but CREDIT).
#[derive(Debug)]
pub(crate) struct Frame {
    pub kind: u8,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Hello {
    pub generation: u32,
    pub version: String,
    #[serde(default)]
    pub methods: Vec<String>,
    #[serde(default)]
    pub framed: bool,
}

type Pending =
    Arc<std::sync::Mutex<Option<HashMap<u64, oneshot::Sender<Result<Value, CoreError>>>>>>;
type Writer = Arc<Mutex<Box<dyn tokio::io::AsyncWrite + Send + Unpin>>>;

/// Stream channels (non-zero) and frames that arrived for a channel before
/// its opener registered it (the reply and the first frames race).
#[derive(Default)]
struct Channels {
    open: HashMap<u32, mpsc::UnboundedSender<Frame>>,
    early: HashMap<u32, Vec<Frame>>,
    closed: bool,
}
type SharedChannels = Arc<std::sync::Mutex<Channels>>;

/// One framed connection to a Host's herdr-core.
pub(crate) struct HcClient {
    hello: Hello,
    writer: Writer,
    pending: Pending,
    channels: SharedChannels,
    next_id: AtomicU64,
    reader: JoinHandle<()>,
    _guard: Box<dyn Send + Sync>,
    pub(crate) ssh: Option<Arc<crate::transport::ssh::Session>>,
    pub(crate) closed: tokio::sync::watch::Sender<bool>,
}

impl HcClient {
    /// Says `hello`, checks compatibility and switches to framed mode.
    pub async fn handshake(stream: Stream) -> Result<Self, CoreError> {
        let Stream {
            reader,
            mut writer,
            guard,
            ssh,
        } = stream;
        let hello = json!({
            "jsonrpc": "2.0",
            "id": 0,
            "method": "hello",
            "params": { "generation": GENERATION, "framed": true },
        });
        let mut line = serde_json::to_vec(&hello).map_err(|e| protocol(e.to_string()))?;
        line.push(b'\n');
        writer
            .write_all(&line)
            .await
            .map_err(|e| protocol(format!("send hello: {e}")))?;
        writer
            .flush()
            .await
            .map_err(|e| protocol(format!("send hello: {e}")))?;

        let mut reader = BufReader::new(reader);
        let reply = tokio::time::timeout(HELLO_TIMEOUT, read_hello_reply(&mut reader))
            .await
            .map_err(|_| protocol("herdr-core did not answer hello in time"))??;
        let hello = check_hello(reply)?;

        let pending: Pending = Arc::new(std::sync::Mutex::new(Some(HashMap::new())));
        let channels = SharedChannels::default();
        let (closed, _) = tokio::sync::watch::channel(false);
        let ended = closed.clone();
        let frames = read_frames(reader, pending.clone(), channels.clone());
        let reader = tokio::spawn(async move {
            frames.await;
            ended.send_replace(true);
        });
        Ok(Self {
            hello,
            writer: Arc::new(Mutex::new(writer)),
            pending,
            channels,
            next_id: AtomicU64::new(1),
            reader,
            _guard: guard,
            ssh,
            closed,
        })
    }

    pub fn core_version(&self) -> &str {
        &self.hello.version
    }

    /// One JSON-RPC call on channel 0.
    pub async fn call(&self, method: &str, params: Value) -> Result<Value, CoreError> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        {
            let mut guard = self.pending.lock().expect("pending lock");
            let map = guard.as_mut().ok_or(CoreError::Closed)?;
            map.insert(id, tx);
        }
        let msg = json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params });
        let payload = serde_json::to_vec(&msg).map_err(|e| protocol(e.to_string()))?;
        if send_frame(&self.writer, 0, KIND_DATA, &payload)
            .await
            .is_err()
        {
            self.forget(id);
            return Err(CoreError::Closed);
        }
        match tokio::time::timeout(CALL_TIMEOUT, rx).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(CoreError::Closed),
            Err(_) => {
                self.forget(id);
                Err(protocol(format!("{method} timed out")))
            }
        }
    }

    /// `events.subscribe` with `params`: returns the reply (`seq`,
    /// `terminals`, `channel`) and the notifications that follow, each a whole
    /// JSON-RPC message (`{method: "event" | "heartbeat" | "missed", params}`).
    /// The receiver closes when the connection ends; dropping it stops
    /// crediting the stream.
    pub async fn subscribe_events(
        &self,
        params: Value,
    ) -> Result<(Value, mpsc::UnboundedReceiver<Value>), CoreError> {
        let reply = self.call("events.subscribe", params).await?;
        let channel = reply["channel"]
            .as_u64()
            .ok_or_else(|| protocol("events.subscribe returned no channel"))?
            as u32;
        let mut frames = self.stream(channel);
        let (tx, rx) = mpsc::unbounded_channel();
        let writer = self.writer.clone();
        tokio::spawn(async move {
            let mut buf: Vec<u8> = Vec::new();
            while let Some(Frame {
                kind,
                payload: chunk,
            }) = frames.recv().await
            {
                if kind != KIND_DATA {
                    break;
                }
                let credit = (chunk.len() as u32).to_le_bytes();
                buf.extend_from_slice(&chunk);
                while let Some(end) = buf.iter().position(|&b| b == b'\n') {
                    let line: Vec<u8> = buf.drain(..=end).collect();
                    if let Ok(msg) = serde_json::from_slice::<Value>(&line)
                        && tx.send(msg).is_err()
                    {
                        return;
                    }
                }
                if send_frame(&writer, channel, KIND_CREDIT, &credit)
                    .await
                    .is_err()
                {
                    return;
                }
            }
        });
        Ok((reply, rx))
    }

    /// The frames of stream `channel` (as a method reply named it), starting
    /// with any that arrived before this call. A CLOSE is the last frame; the
    /// receiver also ends when the connection does.
    pub fn stream(&self, channel: u32) -> mpsc::UnboundedReceiver<Frame> {
        let (tx, rx) = mpsc::unbounded_channel();
        let mut ch = self.channels.lock().expect("channels lock");
        let early = ch.early.remove(&channel).unwrap_or_default();
        let closed = early.iter().any(|f| f.kind == KIND_CLOSE);
        for frame in early {
            let _ = tx.send(frame);
        }
        if !ch.closed && !closed {
            ch.open.insert(channel, tx);
        }
        rx
    }

    /// Sends one frame on a stream channel (CREDIT, DATA, RESIZE, CLOSE...).
    pub async fn send(&self, channel: u32, kind: u8, payload: &[u8]) -> Result<(), CoreError> {
        if self.channels.lock().expect("channels lock").closed {
            return Err(CoreError::Closed);
        }
        send_frame(&self.writer, channel, kind, payload)
            .await
            .map_err(|_| CoreError::Closed)
    }

    fn forget(&self, id: u64) {
        if let Some(map) = self.pending.lock().expect("pending lock").as_mut() {
            map.remove(&id);
        }
    }

    /// Revoke the client synchronously, including from Host settings/removal.
    /// Transport shutdown runs on the core runtime; no caller has to block it.
    pub fn close_now(&self) {
        self.closed.send_replace(true);
        self.reader.abort();
        fail_all(&self.pending);
        // Aborting the reader skips its normal stream cleanup. Wake every
        // Terminal/structure watch as well as pending RPC callers.
        let mut channels = self.channels.lock().expect("channels lock");
        channels.closed = true;
        channels.open.clear();
        channels.early.clear();
        drop(channels);
        let writer = self.writer.clone();
        crate::runtime().spawn(async move {
            let _ = writer.lock().await.shutdown().await;
        });
    }

    pub async fn close(&self) {
        self.close_now();
        let _ = self.writer.lock().await.shutdown().await;
    }
}

impl Drop for HcClient {
    fn drop(&mut self) {
        self.closed.send_replace(true);
        self.reader.abort();
    }
}

/// Reads lines until the reply to `hello` (id 0). Lines that are not
/// JSON-RPC, and any junk before a `{` on the reply line, are skipped, so a
/// login shell that prints a banner does not break the protocol. Too much
/// junk or an early end is a clear [`CoreError::Protocol`].
async fn read_hello_reply<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut BufReader<R>,
) -> Result<Value, CoreError> {
    let mut skipped: u64 = 0;
    let mut noise = String::new();
    loop {
        let mut line = Vec::new();
        // Room for the remaining junk allowance plus the reply line itself.
        let budget = MAX_PREAMBLE.saturating_sub(skipped) + 64 * 1024;
        let n = (&mut *reader)
            .take(budget)
            .read_until(b'\n', &mut line)
            .await
            .map_err(|e| protocol(format!("read hello reply: {e}")))?;
        if n == 0 {
            let hint = if noise.is_empty() {
                String::new()
            } else {
                format!(
                    " (it printed: {:?})",
                    noise.chars().take(200).collect::<String>()
                )
            };
            return Err(protocol(format!(
                "herdr-core connect ended before answering hello; is herdr-core installed on the Host?{hint}"
            )));
        }
        if let Some(start) = line.iter().position(|&b| b == b'{')
            && let Ok(v) = serde_json::from_slice::<Value>(&line[start..])
            && v.get("id") == Some(&json!(0))
            && (v.get("result").is_some() || v.get("error").is_some())
        {
            return Ok(v);
        }
        skipped += n as u64;
        if noise.len() < 200 {
            noise.push_str(&String::from_utf8_lossy(&line));
        }
        if skipped > MAX_PREAMBLE {
            return Err(protocol(format!(
                "the Host printed more than {MAX_PREAMBLE} bytes that are not the herdr-core protocol; check the login shell's startup files"
            )));
        }
    }
}

fn check_hello(reply: Value) -> Result<Hello, CoreError> {
    if let Some(err) = reply.get("error") {
        let kind = err["data"]["kind"].as_str().unwrap_or("");
        if kind == "unsupported_generation" {
            let host = err["data"]["generation"].as_u64().unwrap_or(0) as u32;
            return Err(generation_mismatch(host, "unknown"));
        }
        return Err(rpc_error(err));
    }
    let hello: Hello = serde_json::from_value(reply["result"].clone())
        .map_err(|e| protocol(format!("unexpected hello reply: {e}")))?;
    if hello.generation != GENERATION {
        return Err(generation_mismatch(hello.generation, &hello.version));
    }
    if version_tuple(&hello.version) < version_tuple(MIN_CORE_VERSION) {
        return Err(CoreError::Incompatible {
            reason: Incompatibility::CoreTooOld {
                version: hello.version,
                minimum: MIN_CORE_VERSION.into(),
            },
        });
    }
    let missing: Vec<String> = REQUIRED_METHODS
        .iter()
        .filter(|m| !hello.methods.iter().any(|h| h == *m))
        .map(|m| (*m).to_owned())
        .collect();
    if !missing.is_empty() {
        return Err(CoreError::Incompatible {
            reason: Incompatibility::MissingMethods { methods: missing },
        });
    }
    if !hello.framed {
        return Err(protocol("herdr-core refused framed mode"));
    }
    Ok(hello)
}

fn generation_mismatch(host: u32, version: &str) -> CoreError {
    let reason = if host > GENERATION {
        Incompatibility::AppTooOld {
            host_generation: host,
        }
    } else {
        Incompatibility::CoreTooOld {
            version: version.to_owned(),
            minimum: MIN_CORE_VERSION.into(),
        }
    };
    CoreError::Incompatible { reason }
}

/// `"0.1.17"` -> `[0, 1, 17]`; a pre-release suffix is ignored.
fn version_tuple(v: &str) -> Vec<u64> {
    v.split(['-', '+'])
        .next()
        .unwrap_or("")
        .split('.')
        .map(|p| p.parse().unwrap_or(0))
        .collect()
}

fn rpc_error(err: &Value) -> CoreError {
    CoreError::Rpc {
        kind: err["data"]["kind"].as_str().unwrap_or("unknown").to_owned(),
        detail: err["message"].as_str().unwrap_or("").to_owned(),
    }
}

async fn send_frame(
    writer: &Writer,
    channel: u32,
    kind: u8,
    payload: &[u8],
) -> std::io::Result<()> {
    let mut frame = Vec::with_capacity(HEADER + payload.len());
    frame.extend_from_slice(&channel.to_le_bytes());
    frame.push(kind);
    frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    frame.extend_from_slice(payload);
    let mut w = writer.lock().await;
    w.write_all(&frame).await?;
    w.flush().await
}

async fn read_frames<R: tokio::io::AsyncRead + Unpin>(
    mut reader: BufReader<R>,
    pending: Pending,
    channels: SharedChannels,
) {
    let mut header = [0u8; HEADER];
    loop {
        if reader.read_exact(&mut header).await.is_err() {
            break;
        }
        let channel = u32::from_le_bytes(header[0..4].try_into().expect("4 bytes"));
        let kind = header[4];
        let len = u32::from_le_bytes(header[5..9].try_into().expect("4 bytes")) as usize;
        if len > MAX_PAYLOAD {
            break;
        }
        let mut payload = vec![0u8; len];
        if reader.read_exact(&mut payload).await.is_err() {
            break;
        }
        if channel != 0 {
            let mut ch = channels.lock().expect("channels lock");
            if kind == KIND_CREDIT {
                continue;
            }
            let frame = Frame { kind, payload };
            match ch.open.get(&channel) {
                Some(tx) => {
                    let _ = tx.send(frame);
                }
                None => ch.early.entry(channel).or_default().push(frame),
            }
            if kind == KIND_CLOSE {
                ch.open.remove(&channel);
            }
            continue;
        }
        if kind != KIND_DATA {
            continue;
        }
        let Ok(msg) = serde_json::from_slice::<Value>(&payload) else {
            continue;
        };
        let Some(id) = msg.get("id").and_then(Value::as_u64) else {
            continue; // notifications
        };
        let tx = pending
            .lock()
            .expect("pending lock")
            .as_mut()
            .and_then(|m| m.remove(&id));
        if let Some(tx) = tx {
            let result = match msg.get("error") {
                Some(err) => Err(rpc_error(err)),
                None => Ok(msg.get("result").cloned().unwrap_or(Value::Null)),
            };
            let _ = tx.send(result);
        }
    }
    fail_all(&pending);
    let mut ch = channels.lock().expect("channels lock");
    ch.closed = true;
    ch.open.clear();
}

fn fail_all(pending: &Pending) {
    // Dropping the senders wakes every waiter with Closed.
    pending.lock().expect("pending lock").take();
}
