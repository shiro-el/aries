//! A plain-mode JSON-RPC connection to the user's local herdr-core
//! (`herdr-core connect`), for request/response calls.

use std::io::{BufRead, BufReader, Read, Write};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

use serde_json::{Value, json};

use crate::chat::{TerminalFacts, TerminalInput, TerminalLookup};

pub struct CoreLink {
    child: Child,
    stdin: ChildStdin,
    reader: BufReader<ChildStdout>,
    framed: bool,
    next_id: u64,
    /// herdr-core version from `hello`.
    pub version: String,
}

impl CoreLink {
    pub fn connect() -> Result<Self, String> {
        Self::connect_mode(false)
    }

    fn connect_mode(framed: bool) -> Result<Self, String> {
        let bin = crate::herdr_core().ok_or("herdr-core not found on PATH or in ~/.local/bin")?;
        let mut child = Command::new(&bin)
            .arg("connect")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("{}: {e}", bin.display()))?;
        let stdin = child.stdin.take().expect("piped stdin");
        let stdout = child.stdout.take().expect("piped stdout");
        let mut link = Self {
            child,
            stdin,
            reader: BufReader::new(stdout),
            framed: false,
            next_id: 0,
            version: String::new(),
        };
        let hello = link.call("hello", json!({"generation": 1, "framed": framed}))?;
        link.framed = framed;
        link.version = hello["version"].as_str().unwrap_or_default().to_owned();
        Ok(link)
    }

    /// Sends one request and waits for its reply. `Err` for a transport
    /// failure or an error reply (its `data.kind` or message).
    pub fn call(&mut self, method: &str, params: Value) -> Result<Value, String> {
        let id = self.next_id;
        self.next_id += 1;
        let req = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        if self.framed {
            self.frame(0, 0, req.to_string().as_bytes())?;
        } else {
            writeln!(self.stdin, "{req}").map_err(|e| format!("send {method}: {e}"))?;
        }
        loop {
            let line = if self.framed {
                let mut header = [0; 9];
                self.reader
                    .read_exact(&mut header)
                    .map_err(|e| e.to_string())?;
                let len = u32::from_le_bytes(header[5..9].try_into().unwrap()) as usize;
                if len > 16 * 1024 * 1024 {
                    return Err("oversized frame".into());
                }
                let mut payload = vec![0; len];
                self.reader
                    .read_exact(&mut payload)
                    .map_err(|e| e.to_string())?;
                if header[..4] != [0; 4] || header[4] != 0 {
                    continue;
                }
                String::from_utf8(payload).map_err(|e| e.to_string())?
            } else {
                let mut line = String::new();
                if self
                    .reader
                    .read_line(&mut line)
                    .map_err(|e| e.to_string())?
                    == 0
                {
                    return Err(format!("herdr-core closed before answering {method}"));
                }
                line
            };
            let Ok(v) = serde_json::from_str::<Value>(&line) else {
                continue; // login noise
            };
            if v["id"] != id {
                continue; // notifications
            }
            if let Some(e) = v.get("error") {
                return Err(RpcError(e.clone()).to_string());
            }
            return Ok(v["result"].clone());
        }
    }
    fn frame(&mut self, channel: u32, kind: u8, bytes: &[u8]) -> Result<(), String> {
        self.stdin
            .write_all(&channel.to_le_bytes())
            .and_then(|()| self.stdin.write_all(&[kind]))
            .and_then(|()| self.stdin.write_all(&(bytes.len() as u32).to_le_bytes()))
            .and_then(|()| self.stdin.write_all(bytes))
            .and_then(|()| self.stdin.flush())
            .map_err(|e| e.to_string())
    }
}

struct RpcError(Value);

impl std::fmt::Display for RpcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let kind = self.0["data"]["kind"].as_str();
        let msg = self.0["message"].as_str().unwrap_or("error");
        match kind {
            Some(k) => write!(f, "rpc:{k}: {msg}"),
            None => write!(f, "rpc: {msg}"),
        }
    }
}

impl Drop for CoreLink {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// [`TerminalLookup`] over a [`CoreLink`] that reconnects once if the core
/// went away (a `server restart`).
#[derive(Default)]
pub struct CoreLookup {
    link: Option<CoreLink>,
}

impl TerminalLookup for CoreLookup {
    fn screen(&mut self, terminal: &str) -> Result<String, String> {
        if self.link.is_none() {
            self.link = Some(CoreLink::connect()?);
        }
        let result = self
            .link
            .as_mut()
            .unwrap()
            .call("terminal.read", json!({"terminal": terminal, "lines": 0}))?;
        Ok(result["text"].as_str().unwrap_or_default().to_owned())
    }

    fn get(&mut self, terminal: &str) -> Result<Option<TerminalFacts>, String> {
        for attempt in 0..2 {
            if self.link.is_none() {
                self.link = Some(CoreLink::connect()?);
            }
            let link = self.link.as_mut().expect("link");
            match link.call("terminal.get", json!({"terminal": terminal})) {
                Ok(v) => return Ok(Some(TerminalFacts::from_json(&v))),
                // An error reply: no such Terminal (or not one we may read).
                Err(e) if e.starts_with("rpc") => return Ok(None),
                Err(e) => {
                    self.link = None;
                    if attempt == 1 {
                        return Err(e);
                    }
                }
            }
        }
        unreachable!()
    }
}

impl TerminalInput for CoreLookup {
    /// Literal UTF-8 DATA on an attach stream: no paste wrapper or submit.
    /// The channel-0 round trip drains the write before the next action.
    fn text(&mut self, terminal: &str, text: &str) -> Result<(), String> {
        let mut link = CoreLink::connect_mode(true)?;
        let facts = link.call("terminal.get", json!({"terminal": terminal}))?;
        let attached = link.call("terminal.attach", json!({"terminal": terminal, "cols": facts["cols"].as_u64().unwrap_or(80), "rows": facts["rows"].as_u64().unwrap_or(24)}))?;
        let channel = attached["channel"]
            .as_u64()
            .ok_or("missing attach channel")? as u32;
        link.frame(channel, 0, text.as_bytes())?;
        link.call("terminal.get", json!({"terminal": terminal}))?;
        link.frame(channel, 2, &[])?;
        // Let the Agent's editor drain its typing/paste-burst buffer before
        // a following Enter. This is a delay, never a resend.
        std::thread::sleep(std::time::Duration::from_millis(100));
        Ok(())
    }

    /// Not retried: a press that may have landed must not land twice.
    fn keys(&mut self, terminal: &str, keys: &[String]) -> Result<(), String> {
        if self.link.is_none() {
            self.link = Some(CoreLink::connect()?);
        }
        let link = self.link.as_mut().expect("link");
        link.call("terminal.keys", json!({"terminal": terminal, "keys": keys}))
            .map(|_| ())
    }
}
