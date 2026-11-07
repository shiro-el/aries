use super::claude::hook::Pending;
use super::{Agent, Interpreter, Located};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ToolStatus};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::Path;

pub(super) fn hash(value: impl Serialize) -> String {
    bytes_hash(&serde_json::to_vec(&value).expect("claim identity"))
}
fn bytes_hash(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}
fn shape(name: &str, input: &serde_json::Value) -> String {
    let mut input = input.clone();
    if name == "AskUserQuestion"
        && let Some(input) = input.as_object_mut()
    {
        input.remove("answers");
    }
    hash((name, input))
}

#[derive(Clone, Serialize, Deserialize)]
struct Gate {
    nonce: String,
    shape: String,
    offset: u64,
    prefix: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
pub(super) struct Identity {
    version: u8,
    source: String,
    aliases: Vec<String>,
    call: Option<String>,
    shape: Option<String>,
    gate: Option<Gate>,
}
impl Identity {
    pub(super) fn new(
        agent: &dyn Agent,
        located: &Located,
        item: &ChatItem,
        gate: Option<&Pending>,
        correlated: Option<&str>,
    ) -> Self {
        let alias = |id: &str| {
            hash((
                agent.name(),
                &located.session,
                located.path.as_os_str().as_encoded_bytes(),
                id,
            ))
        };
        let mut aliases = vec![alias(&item.id)];
        if let Some(pending) = gate {
            aliases.push(alias(&pending.id));
        }
        if let Some(call) = correlated {
            aliases.push(alias(call));
        }
        aliases.sort();
        aliases.dedup();
        let call = correlated
            .or_else(|| gate.is_none().then_some(item.id.as_str()))
            .map(hash);
        let item_shape = match &item.body {
            ChatBody::Tool { name, input, .. } => Some(shape(name, input)),
            _ => None,
        };
        let gate = gate.map(|p| Gate {
            nonce: hash(&p.nonce),
            shape: shape(&p.request.tool_name, &p.request.tool_input),
            offset: p.transcript_offset,
            prefix: prefix(&located.path, p.transcript_offset),
        });
        Self {
            version: 1,
            source: hash((
                agent.name(),
                &located.session,
                agent.logical_source(located).as_os_str().as_encoded_bytes(),
            )),
            aliases,
            call,
            shape: item_shape,
            gate,
        }
    }
    fn conflicts(&self, old: &Self, located: &Located) -> bool {
        if self.source != old.source {
            return false;
        }
        if self.call.is_some() && self.call == old.call {
            return true;
        }
        if self.aliases.iter().any(|a| old.aliases.contains(a)) {
            return true;
        }
        if let (Some(left), Some(right)) = (&self.gate, &old.gate)
            && left.nonce == right.nonce
        {
            return true;
        }
        if old.call.is_some() {
            return false;
        }
        let Some(call) = &self.call else {
            return false;
        };
        let Some(gate) = &old.gate else {
            return false;
        };
        if self.shape.as_ref() != Some(&gate.shape) {
            return false;
        }
        let Some(expected) = &gate.prefix else {
            return true;
        };
        if prefix(&located.path, gate.offset).as_ref() != Some(expected) {
            return true;
        }
        // A gate before its tool_use exists claims the matching occurrence in
        // this exact transcript prefix, even after hook cleanup or a crash.
        deferred_call(&located.path, gate).is_none_or(|witness| witness.as_ref() == Some(call))
    }
    pub(super) fn check(&self, dir: &Path, located: &Located) -> Result<(), String> {
        for alias in &self.aliases {
            if dir.join(alias).exists() {
                return Err(attempted());
            }
        }
        for entry in std::fs::read_dir(dir).map_err(|e| e.to_string())? {
            let entry = entry.map_err(|e| e.to_string())?;
            if entry.file_name() == "input.lock" {
                continue;
            }
            let bytes = std::fs::read(entry.path()).map_err(|e| e.to_string())?;
            if bytes.is_empty() {
                continue;
            }
            let old: Self =
                serde_json::from_slice(&bytes).map_err(|e| format!("invalid answer claim: {e}"))?;
            if old.version != 1 || self.conflicts(&old, located) {
                return Err(attempted());
            }
        }
        Ok(())
    }
    pub(super) fn release(&self, dir: &Path) -> Result<(), String> {
        for alias in &self.aliases {
            std::fs::remove_file(dir.join(alias)).map_err(|e| e.to_string())?;
        }
        File::open(dir)
            .and_then(|dir| dir.sync_all())
            .map_err(|e| e.to_string())
    }
    pub(super) fn claim(&self, dir: &Path, located: &Located) -> Result<(), String> {
        self.check(dir, located)?;
        let record = serde_json::to_vec(self).map_err(|e| e.to_string())?;
        for alias in &self.aliases {
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let mut file = options.open(dir.join(alias)).map_err(|e| {
                if e.kind() == std::io::ErrorKind::AlreadyExists {
                    attempted()
                } else {
                    format!("could not claim answer: {e}")
                }
            })?;
            file.write_all(&record)
                .and_then(|_| file.sync_all())
                .map_err(|e| e.to_string())?;
        }
        File::open(dir)
            .and_then(|dir| dir.sync_all())
            .map_err(|e| e.to_string())
    }
}
fn attempted() -> String {
    "answer already attempted; delivery is uncertain".into()
}
fn prefix(path: &Path, offset: u64) -> Option<String> {
    use std::io::Read;
    if offset == 0 {
        return Some(bytes_hash(&[]));
    }
    let mut bytes = Vec::new();
    File::open(path)
        .ok()?
        .take(offset)
        .read_to_end(&mut bytes)
        .ok()?;
    (bytes.len() as u64 == offset).then(|| bytes_hash(&bytes))
}
fn deferred_call(path: &Path, gate: &Gate) -> Option<Option<String>> {
    let bytes = std::fs::read(path).ok()?;
    let mut interpreter = super::claude::ClaudeInterpreter::default();
    let mut end = 0u64;
    let mut turn = None;
    let mut seen = std::collections::HashSet::new();
    let mut call = None;
    for line in bytes.split_inclusive(|b| *b == b'\n') {
        end += line.len() as u64;
        if line.last() != Some(&b'\n') {
            break;
        }
        // Delivery/queue/branch records define turns; raw user rows do not.
        for event in interpreter.line(std::str::from_utf8(line).ok()?) {
            match event {
                ChatEvent::Item { item } => match &item.body {
                    ChatBody::User { queued: false, .. } => {
                        let next = hash(&item.id);
                        if end > gate.offset && turn.as_ref().is_some_and(|old| old != &next) {
                            return Some(call);
                        }
                        turn = Some(next);
                    }
                    ChatBody::Tool {
                        name,
                        input,
                        status,
                        ..
                    } => {
                        let first = seen.insert(item.id.clone());
                        if first
                            && call.is_none()
                            && end > gate.offset
                            && *status == ToolStatus::Running
                            && shape(name, input) == gate.shape
                        {
                            call = Some(hash(&item.id));
                        }
                    }
                    _ => {}
                },
                ChatEvent::Reset { .. } if end > gate.offset => return None,
                _ => {}
            }
        }
    }
    Some(call)
}
