//! A plain-mode JSON-RPC connection to the user's local herdr-core
//! (`herdr-core connect`), for request/response calls.

use std::io::{BufRead, BufReader, Lines, Write};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

use serde_json::{Value, json};

use crate::chat::{TerminalFacts, TerminalLookup};

pub struct CoreLink {
    child: Child,
    stdin: ChildStdin,
    lines: Lines<BufReader<ChildStdout>>,
    next_id: u64,
    /// herdr-core version from `hello`.
    pub version: String,
}

impl CoreLink {
    pub fn connect() -> Result<Self, String> {
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
            lines: BufReader::new(stdout).lines(),
            next_id: 0,
            version: String::new(),
        };
        let hello = link.call("hello", json!({"generation": 1}))?;
        link.version = hello["version"].as_str().unwrap_or_default().to_owned();
        Ok(link)
    }

    /// Sends one request and waits for its reply. `Err` for a transport
    /// failure or an error reply (its `data.kind` or message).
    pub fn call(&mut self, method: &str, params: Value) -> Result<Value, String> {
        let id = self.next_id;
        self.next_id += 1;
        let req = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        writeln!(self.stdin, "{req}").map_err(|e| format!("send {method}: {e}"))?;
        loop {
            let line = self
                .lines
                .next()
                .ok_or_else(|| format!("herdr-core closed before answering {method}"))?
                .map_err(|e| format!("read {method}: {e}"))?;
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
