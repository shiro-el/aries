//! Per-terminal PermissionRequest rendezvous. A decision is only an attempted
//! delivery: callers must observe the transcript or terminal before reporting Sent.
use std::collections::HashSet;
use std::io::{self, BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::chat::{TerminalLookup, answer::TerminalInput};

pub const TIMEOUT: Duration = Duration::from_secs(1795);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Request {
    pub session_id: String,
    pub transcript_path: PathBuf,
    pub cwd: String,
    pub permission_mode: String,
    pub hook_event_name: String,
    pub tool_name: String,
    pub tool_input: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Pending {
    pub request: Request,
    pub id: String,
    pub transcript_offset: u64,
    pub created_at: u64,
    pub nonce: String,
}

/// Short, traversal-free names; terminal addresses are otherwise opaque.
pub fn socket_path(state: &Path, runtime: Option<&Path>, terminal: &str) -> io::Result<PathBuf> {
    let name = if !terminal.is_empty()
        && terminal.len() <= 32
        && terminal
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        terminal.to_owned()
    } else {
        Sha256::digest(terminal.as_bytes())[..16]
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    };
    let base = runtime
        .map(|p| p.join("aries-beacon/claude"))
        .unwrap_or_else(|| state.join("claude"));
    let path = base.join(format!("{name}.sock"));
    // Also works on Darwin's shorter sockaddr_un (104 bytes).
    if path.as_os_str().as_encoded_bytes().len() >= 104 {
        return Err(io::Error::other(
            "Claude socket path exceeds Unix socket limit",
        ));
    }
    Ok(path)
}

pub fn runtime_dir() -> Option<PathBuf> {
    std::env::var_os("XDG_RUNTIME_DIR")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
}

fn private_dir(path: &Path) -> io::Result<()> {
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(path)?;
    let meta = std::fs::symlink_metadata(path)?;
    if !meta.is_dir() || meta.uid() != unsafe { libc::geteuid() } {
        return Err(io::Error::other(
            "Claude socket directory is not privately owned",
        ));
    }
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))
}

fn records(path: &Path) -> Vec<Value> {
    let Ok(file) = std::fs::File::open(path) else {
        return vec![];
    };
    BufReader::new(file)
        .lines()
        .map_while(Result::ok)
        .filter_map(|s| serde_json::from_str(&s).ok())
        .collect()
}

/// Only tool uses and results appended after this request began can resolve it.
/// Older Claude versions can instead resolve through the blocked-status transition.
pub fn completed(pending: &Pending) -> bool {
    ResolutionScan::new(pending.transcript_offset).poll(pending)
}

/// Tail the transcript without reparsing history or discarding an unfinished
/// JSONL record when a writer pauses in the middle of a line.
struct ResolutionScan {
    offset: u64,
    reader: Option<BufReader<std::fs::File>>,
    partial: Vec<u8>,
    ids: HashSet<String>,
}

impl ResolutionScan {
    fn new(offset: u64) -> Self {
        Self {
            offset,
            reader: None,
            partial: Vec::new(),
            ids: HashSet::new(),
        }
    }

    fn poll(&mut self, pending: &Pending) -> bool {
        if self.reader.is_none() {
            let Ok(mut file) = std::fs::File::open(&pending.request.transcript_path) else {
                return false;
            };
            if file.seek(SeekFrom::Start(self.offset)).is_err() {
                return false;
            }
            self.reader = Some(BufReader::new(file));
        }
        let reader = self.reader.as_mut().unwrap();
        loop {
            match reader.read_until(b'\n', &mut self.partial) {
                Ok(0) | Err(_) => return false,
                Ok(_) => {}
            }
            if self.partial.last() != Some(&b'\n') {
                return false;
            }
            let row = serde_json::from_slice::<Value>(&self.partial);
            self.partial.clear();
            let Ok(row) = row else { continue };
            let Some(blocks) = row["message"]["content"].as_array() else {
                continue;
            };
            for block in blocks {
                if block["type"] == "tool_use"
                    && block["name"] == pending.request.tool_name
                    && same_input(
                        &pending.request.tool_name,
                        &block["input"],
                        &pending.request.tool_input,
                    )
                    && let Some(id) = block["id"].as_str()
                {
                    self.ids.insert(id.to_owned());
                }
            }
            if blocks.iter().any(|block| {
                block["type"] == "tool_result"
                    && block["tool_use_id"]
                        .as_str()
                        .is_some_and(|id| self.ids.contains(id))
            }) {
                return true;
            }
        }
    }
}

fn same_input(name: &str, left: &Value, right: &Value) -> bool {
    let normalize = |v: &Value| {
        let mut v = v.clone();
        // Claude persists updatedInput, including the answers supplied by us.
        if name == "AskUserQuestion"
            && let Some(o) = v.as_object_mut()
        {
            o.remove("answers");
        }
        v
    };
    normalize(left) == normalize(right)
}

pub fn pending(path: &Path, session: &str, transcript: &Path) -> Option<Pending> {
    if !path.exists() {
        return None;
    }
    let p: Pending =
        serde_json::from_slice(&std::fs::read(path.with_extension("json")).ok()?).ok()?;
    (p.request.session_id == session && p.request.transcript_path == transcript && !completed(&p))
        .then_some(p)
}

struct Cleanup(PathBuf);
impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
        let _ = std::fs::remove_file(self.0.with_extension("json"));
    }
}

struct HookLock {
    path: PathBuf,
    // Keep the inode locked until after unlink, including during Drop.
    _file: std::fs::File,
}

impl HookLock {
    fn acquire(path: PathBuf) -> io::Result<Option<Self>> {
        loop {
            let file = std::fs::OpenOptions::new()
                .create(true)
                .truncate(false)
                .write(true)
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW)
                .open(&path)?;
            // Never steal a live hook, even for a duplicate event.
            if file.try_lock().is_err() {
                return Ok(None);
            }
            // Another hook may have unlinked this inode after we opened it.
            // Only the lock currently named by the path can own the socket.
            if Self::is_current(&file, &path)? {
                return Ok(Some(Self { path, _file: file }));
            }
        }
    }

    fn is_current(file: &std::fs::File, path: &Path) -> io::Result<bool> {
        let held = file.metadata()?;
        match std::fs::symlink_metadata(path) {
            Ok(named) => Ok(held.dev() == named.dev() && held.ino() == named.ino()),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(e),
        }
    }
}

impl Drop for HookLock {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// None means somebody else resolved the request, or the deadline elapsed.
pub fn wait<L: TerminalLookup>(
    path: &Path,
    terminal: &str,
    request: Request,
    timeout: Duration,
    link: &mut L,
) -> io::Result<Option<Value>> {
    private_dir(path.parent().unwrap())?;
    let Some(_lock) = HookLock::acquire(path.with_extension("lock"))? else {
        return Ok(None);
    };
    let _cleanup = Cleanup(path.to_path_buf());
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(path.with_extension("json"));
    let listener = UnixListener::bind(path)?;
    listener.set_nonblocking(true)?;
    let started = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let digest = Sha256::digest(serde_json::to_vec(&json!([
        request.session_id,
        request.tool_name,
        request.tool_input,
        started.as_nanos().to_string()
    ]))?);
    let pending = Pending {
        transcript_offset: std::fs::metadata(&request.transcript_path).map_or(0, |m| m.len()),
        request,
        id: format!(
            "claude-hook:{}",
            digest[..8]
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>()
        ),
        created_at: started.as_secs(),
        nonce: format!("{:032x}", rand::random::<u128>()),
    };
    let mut file = tempfile::NamedTempFile::new_in(path.parent().unwrap())?;
    serde_json::to_writer(&mut file, &pending)?;
    file.flush()?;
    file.persist(path.with_extension("json"))
        .map_err(|e| e.error)?;
    let deadline = Instant::now() + timeout;
    let mut blocked = false;
    let mut scan = ResolutionScan::new(pending.transcript_offset);
    let mut next_status = Instant::now();
    while Instant::now() < deadline {
        if scan.poll(&pending) {
            return Ok(None);
        }
        if Instant::now() >= next_status {
            if let Ok(Some(facts)) = link.get(terminal)
                && let Some(status) = facts.status
            {
                if status.state == "blocked" {
                    blocked = true;
                } else if blocked {
                    return Ok(None);
                }
            }
            next_status = Instant::now() + Duration::from_millis(250);
        }
        match listener.accept() {
            Ok((mut stream, _)) => {
                stream.set_read_timeout(Some(Duration::from_millis(250)))?;
                let mut line = String::new();
                if BufReader::new(&mut stream)
                    .take(1024 * 1024)
                    .read_line(&mut line)
                    .is_ok()
                    && let Ok(message) = serde_json::from_str::<Value>(&line)
                    && message["nonce"] == pending.nonce
                    && let Some(decision) = decision(&pending.request, &message["answer"])
                {
                    // The TUI may win while we read a socket message.
                    if scan.poll(&pending) {
                        return Ok(None);
                    }
                    return Ok(Some(decision));
                }
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {}
            Err(e) => return Err(e),
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    Ok(None)
}

pub fn decision(request: &Request, answer: &Value) -> Option<Value> {
    let decision = if request.tool_name == "AskUserQuestion" {
        let questions = request.tool_input["questions"].as_array()?;
        let answers = answer.as_object()?;
        if answers.len() != questions.len()
            || questions.iter().any(|q| {
                q["question"]
                    .as_str()
                    .is_none_or(|s| !answers.get(s).is_some_and(Value::is_string))
            })
        {
            return None;
        }
        let mut input = request.tool_input.clone();
        input
            .as_object_mut()?
            .insert("answers".into(), answer.clone());
        json!({"behavior":"allow", "updatedInput":input})
    } else {
        let behavior = answer.as_str()?;
        if !matches!(behavior, "allow" | "deny") {
            return None;
        }
        json!({"behavior":behavior})
    };
    Some(json!({"hookSpecificOutput":{"hookEventName":"PermissionRequest", "decision":decision}}))
}

pub fn run(state: &Path) -> io::Result<()> {
    let Some(terminal) = std::env::var_os("HC_TERMINAL_ID").filter(|s| !s.is_empty()) else {
        return Ok(());
    };
    let request: Request = serde_json::from_reader(io::stdin().lock())?;
    if request.hook_event_name != "PermissionRequest" {
        return Ok(());
    }
    let terminal = terminal.to_string_lossy();
    let path = socket_path(state, runtime_dir().as_deref(), &terminal)?;
    if let Some(output) = wait(
        &path,
        &terminal,
        request,
        TIMEOUT,
        &mut crate::core_link::CoreLookup::default(),
    )? {
        println!("{output}");
    }
    Ok(())
}

/// Match the terminal hook request identity before writing anything.
/// `None` means use the existing key path after revalidating the live question.
pub fn deliver<L: TerminalLookup + TerminalInput>(
    path: &Path,
    terminal: &str,
    session: &str,
    transcript: &Path,
    expected: Option<(&str, &Value)>,
    answer: Value,
    link: &mut L,
) -> bool {
    let Ok(bytes) = std::fs::read(path.with_extension("json")) else {
        return false;
    };
    let Ok(pending) = serde_json::from_slice::<Pending>(&bytes) else {
        return false;
    };
    if pending.request.session_id != session
        || pending.request.transcript_path != transcript
        || completed(&pending)
    {
        return false;
    }
    if let Some((id, input)) = expected {
        if pending.id != id
            || pending.request.tool_name != "AskUserQuestion"
            || pending.request.tool_input["questions"] != input["questions"]
        {
            return false;
        }
    } else if pending.request.tool_name == "AskUserQuestion" {
        return false;
    }
    let Ok(mut stream) = UnixStream::connect(path) else {
        return false;
    };
    if stream
        .set_write_timeout(Some(Duration::from_millis(250)))
        .is_err()
    {
        return false;
    }
    if link.claim_structured().is_err() {
        return false;
    }
    if writeln!(
        stream,
        "{}",
        json!({"nonce":pending.nonce, "answer":answer})
    )
    .is_err()
    {
        return false;
    }
    let mut scan = ResolutionScan::new(pending.transcript_offset);
    for _ in 0..40 {
        if scan.poll(&pending)
            || link
                .get(terminal)
                .ok()
                .flatten()
                .and_then(|f| f.status)
                .is_some_and(|s| s.state != "blocked")
        {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    false
}

/// Overlay ephemeral hook requests on the ordinary Claude transcript. The
/// cursor remembers the transient id so reconnecting can remove a stale item.
pub fn open(
    agent: &dyn crate::chat::Agent,
    located: &crate::chat::Located,
    resume: Option<&str>,
    state: &Path,
    terminal: &str,
) -> crate::chat::Opened {
    let (previous, position) =
        resume
            .and_then(|s| s.split_once('~'))
            .map_or((None, resume), |(id, pos)| {
                (
                    id.strip_prefix("hook-")
                        .filter(|id| !id.is_empty())
                        .map(|id| format!("claude-hook:{id}")),
                    Some(pos),
                )
            });
    let mut opened = agent.open(located, position);
    let previous = if opened.resumed { previous } else { None };
    opened.source = Box::new(HookSource {
        inner: opened.source,
        path: socket_path(state, runtime_dir().as_deref(), terminal).ok(),
        located: located.clone(),
        shown: previous,
    });
    opened
}

struct HookSource {
    inner: Box<dyn crate::chat::Source>,
    path: Option<PathBuf>,
    located: crate::chat::Located,
    shown: Option<String>,
}

impl crate::chat::Source for HookSource {
    fn position(&self) -> String {
        format!(
            "hook-{}~{}",
            self.shown
                .as_deref()
                .and_then(|id| id.strip_prefix("claude-hook:"))
                .unwrap_or_default(),
            self.inner.position()
        )
    }

    fn poll(&mut self) -> io::Result<crate::chat::Batch> {
        use crate::chat::Batch;
        use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ToolStatus};
        let batch = self.inner.poll()?;
        let missing = matches!(&batch, Batch::Missing);
        let (events, more) = match batch {
            Batch::Replaced => {
                self.shown = None;
                return Ok(Batch::Replaced);
            }
            Batch::Missing => (vec![], false),
            Batch::Events { events, more } => (events, more),
        };
        let p = self
            .path
            .as_ref()
            .and_then(|path| pending(path, &self.located.session, &self.located.path));
        if events
            .iter()
            .any(|(e, _)| matches!(e, ChatEvent::Reset { .. }))
        {
            self.shown = None;
        }
        let next = p.as_ref().map(|p| p.id.clone());
        let mut output = Vec::new();
        if self.shown != next
            && let Some(id) = self.shown.take()
        {
            output.push((ChatEvent::Remove { id }, None));
        }
        for (event, _) in events {
            if let ChatEvent::Item { item } = &event
                && let ChatBody::Tool {
                    name,
                    input,
                    status,
                    ..
                } = &item.body
                && p.as_ref().is_some_and(|p| {
                    name == &p.request.tool_name && same_input(name, input, &p.request.tool_input)
                })
                && *status == ToolStatus::Running
            {
                output.push((
                    ChatEvent::Remove {
                        id: item.id.clone(),
                    },
                    None,
                ));
                continue;
            }
            output.push((event, None));
        }
        if self.shown != next
            && let Some(p) = p
        {
            // The hook can arrive after a transcript poll already showed
            // its tool. Retire that pending transcript item as well.
            let rows = records(&p.request.transcript_path);
            let blocks: Vec<_> = rows
                .iter()
                .flat_map(|r| r["message"]["content"].as_array().into_iter().flatten())
                .collect();
            let answered: HashSet<_> = blocks
                .iter()
                .filter(|b| b["type"] == "tool_result")
                .filter_map(|b| b["tool_use_id"].as_str())
                .collect();
            for block in &blocks {
                if block["type"] == "tool_use"
                    && block["name"] == p.request.tool_name
                    && same_input(&p.request.tool_name, &block["input"], &p.request.tool_input)
                    && let Some(id) = block["id"].as_str().filter(|id| !answered.contains(id))
                {
                    output.push((ChatEvent::Remove { id: id.into() }, None));
                }
            }
            output.push((
                ChatEvent::Item {
                    item: ChatItem {
                        id: p.id,
                        ts: None,
                        body: ChatBody::Tool {
                            title: crate::chat::claude::tool_title(
                                &p.request.tool_name,
                                &p.request.tool_input,
                            ),
                            question: (p.request.tool_name == "AskUserQuestion")
                                .then(|| crate::chat::claude::question(&p.request.tool_input))
                                .flatten(),
                            name: p.request.tool_name,
                            input: p.request.tool_input,
                            status: ToolStatus::Running,
                            output: None,
                            diff: None,
                            images: vec![],
                            details: None,
                        },
                    },
                },
                None,
            ));
        }
        self.shown = next;
        if missing && self.shown.is_none() && output.is_empty() {
            return Ok(Batch::Missing);
        }
        // An overlay has no independent log offset. Its identity is part of
        // the checkpoint, and all events in this batch must precede it.
        let position = self.position();
        if let Some((_, cursor)) = output.last_mut() {
            *cursor = Some(position);
        }
        Ok(Batch::Events {
            events: output,
            more,
        })
    }
}

#[cfg(test)]
mod lock_tests {
    use super::*;

    #[test]
    fn an_opened_lock_cannot_own_the_socket_after_unlink() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("terminal.lock");
        let first = HookLock::acquire(path.clone()).unwrap().unwrap();
        let stale = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
        assert!(stale.try_lock().is_err());
        assert!(HookLock::acquire(path.clone()).unwrap().is_none());
        drop(first);
        assert!(!path.exists());

        // A contender that opened before cleanup can lock the unlinked inode,
        // even while a new hook owns the current path. It must retry instead.
        let current = HookLock::acquire(path.clone()).unwrap().unwrap();
        stale.try_lock().unwrap();
        assert!(!HookLock::is_current(&stale, &path).unwrap());
        assert!(HookLock::is_current(&current._file, &path).unwrap());
        assert!(HookLock::acquire(path.clone()).unwrap().is_none());
        drop(current);
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
    }
}
