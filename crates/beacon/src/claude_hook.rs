//! Per-terminal PermissionRequest rendezvous. A decision is only an attempted
//! delivery: callers must observe the transcript or terminal before reporting Sent.
use std::collections::HashSet;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
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
    pub tool_use_id: String,
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
    use std::os::unix::fs::MetadataExt;
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

pub fn completed(path: &Path, id: &str) -> bool {
    records(path).iter().any(|r| {
        r["message"]["content"].as_array().is_some_and(|blocks| {
            blocks
                .iter()
                .any(|b| b["type"] == "tool_result" && b["tool_use_id"] == id)
        })
    })
}

pub fn tool_use_id(request: &Request) -> Option<String> {
    let records = records(&request.transcript_path);
    let answered: HashSet<_> = records
        .iter()
        .flat_map(|r| r["message"]["content"].as_array().into_iter().flatten())
        .filter(|b| b["type"] == "tool_result")
        .filter_map(|b| b["tool_use_id"].as_str())
        .collect();
    records
        .iter()
        .rev()
        .flat_map(|r| {
            r["message"]["content"]
                .as_array()
                .into_iter()
                .flatten()
                .rev()
        })
        .find(|b| {
            b["type"] == "tool_use"
                && b["name"] == request.tool_name
                && b["input"] == request.tool_input
                && b["id"].as_str().is_some_and(|id| !answered.contains(id))
        })
        .and_then(|b| b["id"].as_str())
        .map(str::to_owned)
}

struct Cleanup(PathBuf);
impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
        let _ = std::fs::remove_file(self.0.with_extension("json"));
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
    let lock = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path.with_extension("lock"))?;
    // Never steal a live hook, even for a duplicate PermissionRequest event.
    if lock.try_lock().is_err() {
        return Ok(None);
    }
    let _cleanup = Cleanup(path.to_path_buf());
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(path.with_extension("json"));
    let Some(id) = tool_use_id(&request) else {
        return Ok(None);
    };
    let listener = UnixListener::bind(path)?;
    listener.set_nonblocking(true)?;
    let pending = Pending {
        request,
        tool_use_id: id,
        created_at: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        nonce: format!("{:032x}", rand::random::<u128>()),
    };
    let mut file = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(path.with_extension("json"))?;
    serde_json::to_writer(&mut file, &pending)?;
    file.flush()?;
    let deadline = Instant::now() + timeout;
    let mut blocked = false;
    while Instant::now() < deadline {
        if completed(&pending.request.transcript_path, &pending.tool_use_id) {
            return Ok(None);
        }
        if let Ok(Some(facts)) = link.get(terminal)
            && let Some(status) = facts.status
        {
            if status.state == "blocked" {
                blocked = true;
            } else if blocked {
                return Ok(None);
            }
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
                    if completed(&pending.request.transcript_path, &pending.tool_use_id) {
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

/// Match the request to the exact transcript identity before writing anything.
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
        || completed(transcript, &pending.tool_use_id)
    {
        return false;
    }
    if let Some((id, input)) = expected {
        if pending.tool_use_id != id
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
    for _ in 0..40 {
        if completed(transcript, &pending.tool_use_id)
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
