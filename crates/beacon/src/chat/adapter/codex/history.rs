//! Replay immutable `history_base` prefixes before tailing the replacement.
//! Prefix events have no resume point: only the active file advances its
//! cursor. A fingerprint binds inherited bytes too, repairing old cursors
//! that contained only the replacement and detecting changed bases.

use std::cell::RefCell;
use std::collections::{HashSet, VecDeque};
use std::fs::File;
use std::io::{self, BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::rc::Rc;

use aries_protocol::chat::ChatEvent;
use serde::Deserialize;
use serde_json::Value;
use sha2::{Digest, Sha256};

use super::super::{Batch, Interpreter, JsonlSource, Located, Opened, Poll, Source};
use super::{CodexInterpreter, is_uuid};

#[derive(Deserialize)]
struct HistoryBase {
    thread_id: String,
    end_byte_offset: u64,
    end_ordinal_exclusive: u64,
}

struct Prefix {
    path: PathBuf,
    end: u64,
    digest: Vec<u8>,
    /// A compressed base is spooled only through its retained boundary.
    decoded: Option<tempfile::NamedTempFile>,
}

struct Inherited {
    source: JsonlSource,
    end: u64,
    _decoded: Option<tempfile::NamedTempFile>,
}

struct Shared(Rc<RefCell<CodexInterpreter>>);

impl Interpreter for Shared {
    fn line(&mut self, line: &str) -> Vec<ChatEvent> {
        self.0.borrow_mut().line(line)
    }
}

pub(super) fn open(located: &Located, resume: Option<&str>) -> Opened {
    let interpreter = Rc::new(RefCell::new(CodexInterpreter::default()));
    let mut source = HistorySource {
        path: located.path.clone(),
        live: JsonlSource::new(
            located.path.clone(),
            Box::new(Shared(interpreter.clone())),
            0,
        ),
        interpreter,
        prefixes: VecDeque::new(),
        tag: String::new(),
        initialized: false,
        error: None,
    };
    let resumed = match plan(&source.path) {
        Ok(Some(prefixes)) => source.initialize(prefixes, resume),
        Ok(None) => false,
        Err(error) => {
            source.error = Some(error);
            false
        }
    };
    Opened {
        source: Box::new(source),
        resumed,
    }
}

struct HistorySource {
    path: PathBuf,
    interpreter: Rc<RefCell<CodexInterpreter>>,
    live: JsonlSource,
    prefixes: VecDeque<Inherited>,
    tag: String,
    initialized: bool,
    error: Option<io::Error>,
}

impl HistorySource {
    fn initialize(&mut self, prefixes: Vec<Prefix>, resume: Option<&str>) -> bool {
        if !prefixes.is_empty() {
            let mut hash = Sha256::new();
            for prefix in &prefixes {
                hash.update(prefix.end.to_le_bytes());
                hash.update(&prefix.digest);
            }
            let digest: String = hash.finalize().iter().map(|b| format!("{b:02x}")).collect();
            self.tag = format!("h1.{digest}.");
        }
        let after = resume
            .and_then(|position| position.strip_prefix(&self.tag))
            .and_then(|position| self.live.holds(position));
        let resumed = after.is_some();
        for prefix in prefixes {
            let quiet_until = if resumed { prefix.end } else { 0 };
            self.prefixes.push_back(Inherited {
                source: JsonlSource::new(
                    prefix.path,
                    Box::new(Shared(self.interpreter.clone())),
                    quiet_until,
                )
                .with_limit(prefix.end),
                end: prefix.end,
                _decoded: prefix.decoded,
            });
        }
        self.live = JsonlSource::new(
            self.path.clone(),
            Box::new(Shared(self.interpreter.clone())),
            after.unwrap_or(0),
        );
        self.initialized = true;
        resumed
    }
}

impl Source for HistorySource {
    fn poll(&mut self) -> io::Result<Batch> {
        if let Some(error) = self.error.take() {
            return Err(error);
        }
        if !self.initialized {
            // The writer can create the file before completing its header.
            let Some(prefixes) = plan(&self.path)? else {
                return Ok(Batch::Missing);
            };
            self.initialize(prefixes, None);
        }
        if let Some(prefix) = self.prefixes.front_mut() {
            let Poll::Events { events, more } = prefix.source.poll()? else {
                return Err(invalid("inherited rollout was removed or replaced"));
            };
            if !more {
                if prefix.source.offset() != prefix.end {
                    return Err(invalid("incomplete inherited rollout prefix"));
                }
                self.prefixes.pop_front();
            }
            return Ok(Batch::Events {
                events: events.into_iter().map(|(event, _)| (event, None)).collect(),
                more: true,
            });
        }
        let batch = Source::poll(&mut self.live)?;
        Ok(match batch {
            Batch::Events { events, more } => Batch::Events {
                events: events
                    .into_iter()
                    .map(|(event, position)| (event, position.map(|p| format!("{}{p}", self.tag))))
                    .collect(),
                more,
            },
            other => other,
        })
    }

    fn position(&self) -> String {
        format!("{}{}", self.tag, self.live.position())
    }
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

/// None means the active file or its complete header is not there yet.
fn header(path: &Path) -> io::Result<Option<Value>> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let mut line = Vec::new();
    BufReader::new(file).read_until(b'\n', &mut line)?;
    if !line.ends_with(b"\n") {
        return Ok(None);
    }
    // Unknown or malformed records remain the interpreter's concern.
    Ok(Some(serde_json::from_slice(&line).unwrap_or(Value::Null)))
}

fn plan(active: &Path) -> io::Result<Option<Vec<Prefix>>> {
    let Some(mut record) = header(active)? else {
        return Ok(None);
    };
    let mut path = active.to_path_buf();
    let mut seen = HashSet::from([active.canonicalize()?]);
    let mut prefixes = Vec::new();
    loop {
        let base = &record["payload"]["history_base"];
        if record["type"] != "session_meta" || base.is_null() {
            break;
        }
        let base: HistoryBase = serde_json::from_value(base.clone())
            .map_err(|_| invalid("invalid Codex history_base"))?;
        if !is_uuid(&base.thread_id)
            || base.end_byte_offset == 0
            || record["ordinal"].as_u64() != Some(base.end_ordinal_exclusive)
        {
            return Err(invalid("invalid Codex history_base boundary"));
        }
        if prefixes.len() >= 64 {
            return Err(invalid("Codex history_base chain is too deep"));
        }
        path = find_base(&path, &base.thread_id)?;
        if !seen.insert(path.canonicalize()?) {
            return Err(invalid("cyclic Codex history_base"));
        }
        let decoded = if path.extension().is_some_and(|ext| ext == "zst") {
            let mut temp = tempfile::NamedTempFile::new()?;
            let mut decoder =
                zstd::stream::read::Decoder::new(File::open(&path)?)?.take(base.end_byte_offset);
            if io::copy(&mut decoder, &mut temp)? != base.end_byte_offset {
                return Err(invalid("truncated compressed Codex history_base"));
            }
            Some(temp)
        } else {
            None
        };
        let prefix_path = decoded.as_ref().map_or(path.as_path(), |temp| temp.path());
        let digest = validate(prefix_path, &base)?;
        record = header(prefix_path)?.ok_or_else(|| invalid("missing inherited rollout header"))?;
        prefixes.push(Prefix {
            path: prefix_path.to_path_buf(),
            end: base.end_byte_offset,
            digest,
            decoded,
        });
    }
    prefixes.reverse();
    Ok(Some(prefixes))
}

/// Match the rollout UUID in the filename, which need not be its thread ID.
fn find_base(active: &Path, id: &str) -> io::Result<PathBuf> {
    let home = active
        .ancestors()
        .find(|p| {
            p.file_name()
                .is_some_and(|n| n == "sessions" || n == "archived_sessions")
        })
        .and_then(Path::parent)
        .ok_or_else(|| invalid("Codex rollout is outside session storage"))?;
    let mut dirs = vec![home.join("sessions"), home.join("archived_sessions")];
    let mut found = None;
    while let Some(dir) = dirs.pop() {
        let entries = match std::fs::read_dir(dir) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        for entry in entries {
            let entry = entry?;
            let kind = entry.file_type()?;
            if kind.is_dir() {
                dirs.push(entry.path());
            } else if kind.is_file() {
                let name = entry.file_name();
                let Some(name) = name.to_str() else { continue };
                if name.starts_with("rollout-")
                    && name
                        .strip_suffix(".zst")
                        .unwrap_or(name)
                        .strip_suffix(".jsonl")
                        .is_some_and(|stem| {
                            stem.strip_suffix(id)
                                .is_some_and(|prefix| prefix.ends_with(['-', '_']))
                        })
                    && found.replace(entry.path()).is_some()
                {
                    return Err(invalid("ambiguous Codex history_base rollout"));
                }
            }
        }
    }
    found.ok_or_else(|| invalid("missing Codex history_base rollout"))
}

/// Both boundaries must describe the same complete, ordered JSONL prefix.
/// Read one record at a time, including when the retained history is large.
fn validate(path: &Path, base: &HistoryBase) -> io::Result<Vec<u8>> {
    let file = File::open(path)?;
    if file.metadata()?.len() < base.end_byte_offset {
        return Err(invalid("truncated Codex history_base rollout"));
    }
    let mut reader = BufReader::new(file.take(base.end_byte_offset));
    let mut line = String::new();
    let mut last = None;
    let mut hash = Sha256::new();
    loop {
        line.clear();
        if reader.read_line(&mut line)? == 0 {
            break;
        }
        if !line.ends_with('\n') {
            return Err(invalid("Codex history_base byte boundary splits a record"));
        }
        let record: Value = serde_json::from_str(&line)
            .map_err(|_| invalid("invalid JSON in Codex history_base"))?;
        let ordinal = record["ordinal"]
            .as_u64()
            .ok_or_else(|| invalid("missing Codex history_base ordinal"))?;
        if last.is_some_and(|last| ordinal <= last) || ordinal >= base.end_ordinal_exclusive {
            return Err(invalid("invalid Codex history_base ordinal order"));
        }
        last = Some(ordinal);
        hash.update(line.as_bytes());
    }
    if last.and_then(|last| last.checked_add(1)) != Some(base.end_ordinal_exclusive) {
        return Err(invalid(
            "Codex history_base ordinal boundary does not match bytes",
        ));
    }
    Ok(hash.finalize().to_vec())
}
