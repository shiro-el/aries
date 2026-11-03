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
use tokio::sync::{Mutex, oneshot};
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
const KIND_DATA: u8 = 0;

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Hello {
    pub generation: u32,
    pub version: String,
    #[serde(default)]
    pub methods: Vec<String>,
    #[serde(default)]
    pub framed: bool,
}

type Pending = Arc<std::sync::Mutex<Option<HashMap<u64, oneshot::Sender<Result<Value, CoreError>>>>>>;

/// One framed connection to a Host's herdr-core.
pub(crate) struct HcClient {
    hello: Hello,
    writer: Mutex<Box<dyn tokio::io::AsyncWrite + Send + Unpin>>,
    pending: Pending,
    next_id: AtomicU64,
    reader: JoinHandle<()>,
    _guard: Box<dyn Send + Sync>,
}

impl HcClient {
    /// Says `hello`, checks compatibility and switches to framed mode.
    pub async fn handshake(stream: Stream) -> Result<Self, CoreError> {
        let Stream { reader, mut writer, guard } = stream;
        let hello = json!({
            "jsonrpc": "2.0",
            "id": 0,
            "method": "hello",
            "params": { "generation": GENERATION, "framed": true },
        });
        let mut line = serde_json::to_vec(&hello).map_err(|e| protocol(e.to_string()))?;
        line.push(b'\n');
        writer.write_all(&line).await.map_err(|e| protocol(format!("send hello: {e}")))?;
        writer.flush().await.map_err(|e| protocol(format!("send hello: {e}")))?;

        let mut reader = BufReader::new(reader);
        let reply = tokio::time::timeout(HELLO_TIMEOUT, read_hello_reply(&mut reader))
            .await
            .map_err(|_| protocol("herdr-core did not answer hello in time"))??;
        let hello = check_hello(reply)?;

        let pending: Pending = Arc::new(std::sync::Mutex::new(Some(HashMap::new())));
        let reader = tokio::spawn(read_frames(reader, pending.clone()));
        Ok(Self {
            hello,
            writer: Mutex::new(writer),
            pending,
            next_id: AtomicU64::new(1),
            reader,
            _guard: guard,
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
        let mut frame = Vec::with_capacity(HEADER + payload.len());
        frame.extend_from_slice(&0u32.to_le_bytes());
        frame.push(KIND_DATA);
        frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        frame.extend_from_slice(&payload);
        {
            let mut w = self.writer.lock().await;
            if w.write_all(&frame).await.is_err() || w.flush().await.is_err() {
                self.forget(id);
                return Err(CoreError::Closed);
            }
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

    fn forget(&self, id: u64) {
        if let Some(map) = self.pending.lock().expect("pending lock").as_mut() {
            map.remove(&id);
        }
    }

    pub async fn close(&self) {
        let _ = self.writer.lock().await.shutdown().await;
        self.reader.abort();
        fail_all(&self.pending);
    }
}

impl Drop for HcClient {
    fn drop(&mut self) {
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
                format!(" (it printed: {:?})", noise.chars().take(200).collect::<String>())
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
        Incompatibility::AppTooOld { host_generation: host }
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

async fn read_frames<R: tokio::io::AsyncRead + Unpin>(mut reader: BufReader<R>, pending: Pending) {
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
        if channel != 0 || kind != KIND_DATA {
            continue; // attach and event channels arrive with their tickets
        }
        let Ok(msg) = serde_json::from_slice::<Value>(&payload) else {
            continue;
        };
        let Some(id) = msg.get("id").and_then(Value::as_u64) else {
            continue; // notifications
        };
        let tx = pending.lock().expect("pending lock").as_mut().and_then(|m| m.remove(&id));
        if let Some(tx) = tx {
            let result = match msg.get("error") {
                Some(err) => Err(rpc_error(err)),
                None => Ok(msg.get("result").cloned().unwrap_or(Value::Null)),
            };
            let _ = tx.send(result);
        }
    }
    fail_all(&pending);
}

fn fail_all(pending: &Pending) {
    // Dropping the senders wakes every waiter with Closed.
    pending.lock().expect("pending lock").take();
}
