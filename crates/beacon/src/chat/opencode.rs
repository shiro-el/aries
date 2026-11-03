//! OpenCode: pairing a Terminal with its session, and reading the session
//! out of OpenCode's SQLite database into Chat items. Facts:
//! `docs/research/opencode-integration.md`.
//!
//! Pairing: the Beacon's OpenCode plugin ([`crate::integrations`]) labels
//! its Terminal `aries.opencode.session=<id>` at every prompt; before the
//! first prompt of a resumed session the `-s <id>` argument says it. Labels
//! outlive the program, so a label counts only while OpenCode runs in the
//! Terminal.
//!
//! Transcript: `$XDG_DATA_HOME/opencode/opencode.db` (WAL), tables
//! `message` and `part` (JSON `data`), shared by every OpenCode process.
//! Parts are rewritten in place while they stream, so the reader re-asks
//! for rows by `time_updated` instead of tailing an append-only file. Only
//! `session`, `message` and `part` are read, read-only.

use std::collections::{BTreeMap, HashMap};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::time::Duration;

use aries_protocol::chat::{
    ChatBody, ChatDiff, ChatEvent, ChatImage, ChatItem, ChatQuestion, DiffHunk, Question,
    QuestionOption, ToolStatus,
};
use rusqlite::{Connection, OpenFlags, OptionalExtension};
use serde_json::Value;

use super::source::{Batch, Opened, Source};
use super::{Agent, Interpreter, KeysError, Located, TerminalFacts, media};

pub const AGENT: &str = "opencode";
/// The Terminal label the plugin keeps on the current session id.
pub const SESSION_LABEL: &str = "aries.opencode.session";

/// Rows updated this long before the newest one seen are asked for again:
/// a writer stamps `time_updated` before it commits, so a row can land
/// with a slightly older stamp than one already read.
const SLACK_MS: i64 = 2_000;

pub struct OpenCode {
    /// The Beacon's own `XDG_DATA_HOME`, used when OpenCode's environment
    /// cannot be read.
    pub data_home: Option<PathBuf>,
}

impl OpenCode {
    pub fn from_env() -> Self {
        Self {
            data_home: std::env::var_os("XDG_DATA_HOME")
                .filter(|v| !v.is_empty())
                .map(PathBuf::from),
        }
    }

    fn db_path(&self, pid: Option<u32>) -> PathBuf {
        let data_home = pid
            .and_then(|p| environ(p, "XDG_DATA_HOME"))
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .or_else(|| self.data_home.clone())
            .unwrap_or_else(|| crate::home().join(".local/share"));
        data_home.join("opencode/opencode.db")
    }
}

impl Agent for OpenCode {
    fn name(&self) -> &'static str {
        AGENT
    }

    fn locate(&self, terminal: &TerminalFacts) -> Option<Located> {
        let (pid, argv) = if is_opencode(&terminal.foreground_argv) {
            (terminal.foreground_pid, terminal.foreground_argv.clone())
        } else {
            terminal.pids().into_iter().find_map(|pid| {
                let argv = cmdline(pid)?;
                is_opencode(&argv).then_some((Some(pid), argv))
            })?
        };
        let session = terminal
            .labels
            .get(SESSION_LABEL)
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .or_else(|| session_arg(&argv))?;
        Some(Located {
            path: self.db_path(pid),
            session,
        })
    }

    fn interpreter(&self) -> Box<dyn Interpreter> {
        Box::new(NoLines)
    }

    fn open(&self, located: &Located, resume: Option<&str>) -> Opened {
        let mut source = OpenCodeSource::new(located.path.clone(), located.session.clone());
        let resume = resume.and_then(|p| p.parse::<i64>().ok());
        let resumed = resume.is_some() && source.holds_session();
        if resumed {
            source.quiet_before = resume.map(|t| t - SLACK_MS);
        }
        Opened {
            source: Box::new(source),
            resumed,
        }
    }

    /// The question dock: a number picks that option and submits it. Only a
    /// single single-choice question is known to work that way.
    fn answer_keys(
        &self,
        question: &ChatQuestion,
        picks: &[Vec<usize>],
    ) -> Result<Vec<String>, KeysError> {
        match (question.questions.as_slice(), picks) {
            ([q], [p]) if !q.multi && q.options.len() <= 9 && p.len() == 1 => {
                Ok(vec![(p[0] + 1).to_string()])
            }
            ([q], _) if q.options.len() > 9 => {
                Err(KeysError::Unsupported("more than 9 options".into()))
            }
            ([_], _) => Err(KeysError::Unsupported(
                "OpenCode multi-select questions cannot be answered from Chat yet".into(),
            )),
            _ => Err(KeysError::Unsupported(
                "several OpenCode questions at once cannot be answered from Chat yet".into(),
            )),
        }
    }
}

/// OpenCode has no JSONL transcript; [`OpenCode::open`] reads the database.
struct NoLines;

impl Interpreter for NoLines {
    fn line(&mut self, _: &str) -> Vec<ChatEvent> {
        Vec::new()
    }
}

/// `opencode`, its npm wrapper `opencode.exe`, or a script named so, run
/// directly or by an interpreter (`sh opencode`, `node .../opencode`).
fn is_opencode(argv: &[String]) -> bool {
    argv.iter().take(2).any(|a| {
        let base = Path::new(a)
            .file_name()
            .map(|b| b.to_string_lossy().into_owned())
            .unwrap_or_default();
        let base = base.trim_start_matches('.');
        base == "opencode" || base.starts_with("opencode.") || base.starts_with("opencode-")
    })
}

/// `-s <id>`, `--session <id>` or `--session=<id>`.
fn session_arg(argv: &[String]) -> Option<String> {
    let mut args = argv.iter();
    while let Some(a) = args.next() {
        if a == "-s" || a == "--session" {
            return args.next().filter(|s| !s.is_empty()).cloned();
        }
        if let Some(id) = a.strip_prefix("--session=") {
            return (!id.is_empty()).then(|| id.to_owned());
        }
    }
    None
}

fn cmdline(pid: u32) -> Option<Vec<String>> {
    let raw = std::fs::read(format!("/proc/{pid}/cmdline")).ok()?;
    Some(
        raw.split(|b| *b == 0)
            .filter(|a| !a.is_empty())
            .map(|a| String::from_utf8_lossy(a).into_owned())
            .collect(),
    )
}

fn environ(pid: u32, key: &str) -> Option<String> {
    let raw = std::fs::read(format!("/proc/{pid}/environ")).ok()?;
    raw.split(|b| *b == 0).find_map(|kv| {
        let kv = String::from_utf8_lossy(kv);
        let (k, v) = kv.split_once('=')?;
        (k == key).then(|| v.to_owned())
    })
}

/// One `message` row.
#[derive(Debug, Clone)]
pub struct MessageRow {
    pub id: String,
    pub time_created: i64,
    pub time_updated: i64,
    pub data: Value,
}

/// One `part` row.
#[derive(Debug, Clone)]
pub struct PartRow {
    pub id: String,
    pub message_id: String,
    pub time_created: i64,
    pub time_updated: i64,
    pub data: Value,
}

/// Reads one session from the database, asking again for every row
/// updated since the last poll (minus [`SLACK_MS`]).
pub struct OpenCodeSource {
    path: PathBuf,
    session: String,
    db: Option<(Connection, (u64, u64))>,
    chat: OpenCodeChat,
    /// What was last handed to the interpreter, per row id:
    /// `time_updated` and a digest of `data` (two writes can share a
    /// millisecond).
    seen: HashMap<String, (i64, u64)>,
    /// Newest `time_updated` read: the resume position.
    newest: i64,
    /// Rows updated before this were sent before (resuming from a cursor):
    /// read only to rebuild the interpreter's state.
    quiet_before: Option<i64>,
    /// Parts whose message has not been read yet.
    orphans: Vec<PartRow>,
    complained: bool,
}

impl OpenCodeSource {
    pub fn new(path: PathBuf, session: String) -> Self {
        Self {
            path,
            session,
            db: None,
            chat: OpenCodeChat::default(),
            seen: HashMap::new(),
            newest: 0,
            quiet_before: None,
            orphans: Vec::new(),
            complained: false,
        }
    }

    /// The database exists and has this session (a cursor into it holds).
    fn holds_session(&mut self) -> bool {
        matches!(self.connect(), Ok(Some(_)))
            && self.db.as_ref().is_some_and(|(c, _)| {
                c.query_row(
                    "SELECT 1 FROM session WHERE id = ?1",
                    [&self.session],
                    |_| Ok(()),
                )
                .optional()
                .ok()
                .flatten()
                .is_some()
            })
    }

    /// Opens the database once. `Ok(None)`: it does not exist.
    /// `Err(())`: it was replaced since it was opened.
    fn connect(&mut self) -> Result<Option<&Connection>, ()> {
        let id = match std::fs::metadata(&self.path) {
            Ok(m) => (m.dev(), m.ino()),
            Err(_) if self.db.is_some() => return Err(()),
            Err(_) => return Ok(None),
        };
        if let Some((_, known)) = &self.db {
            if *known != id {
                return Err(());
            }
        } else {
            let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
            match Connection::open_with_flags(&self.path, flags) {
                Ok(c) => {
                    let _ = c.busy_timeout(Duration::from_millis(500));
                    self.db = Some((c, id));
                }
                Err(e) => {
                    self.complain(&e);
                    return Ok(None);
                }
            }
        }
        Ok(self.db.as_ref().map(|(c, _)| c))
    }

    fn complain(&mut self, e: &dyn std::fmt::Display) {
        if !self.complained {
            eprintln!("aries-beacon: {}: {e}", self.path.display());
            self.complained = true;
        }
    }

    /// Messages and parts updated since `since`, from one snapshot.
    fn rows(
        db: &Connection,
        session: &str,
        since: i64,
    ) -> rusqlite::Result<(Vec<MessageRow>, Vec<PartRow>)> {
        let tx = db.unchecked_transaction()?;
        let json = |s: String| serde_json::from_str(&s).unwrap_or(Value::Null);
        let messages = tx
            .prepare(
                "SELECT id, time_created, time_updated, data FROM message \
                 WHERE session_id = ?1 AND time_updated >= ?2 ORDER BY time_created, id",
            )?
            .query_map(rusqlite::params![session, since], |r| {
                Ok(MessageRow {
                    id: r.get(0)?,
                    time_created: r.get(1)?,
                    time_updated: r.get(2)?,
                    data: json(r.get(3)?),
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let parts = tx
            .prepare(
                "SELECT id, message_id, time_created, time_updated, data FROM part \
                 WHERE session_id = ?1 AND time_updated >= ?2 ORDER BY message_id, id",
            )?
            .query_map(rusqlite::params![session, since], |r| {
                Ok(PartRow {
                    id: r.get(0)?,
                    message_id: r.get(1)?,
                    time_created: r.get(2)?,
                    time_updated: r.get(3)?,
                    data: json(r.get(4)?),
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let found = !messages.is_empty()
            || tx
                .query_row("SELECT 1 FROM session WHERE id = ?1", [session], |_| Ok(()))
                .optional()?
                .is_some();
        tx.finish()?;
        if !found {
            return Err(rusqlite::Error::QueryReturnedNoRows);
        }
        Ok((messages, parts))
    }
}

impl Source for OpenCodeSource {
    fn poll(&mut self) -> std::io::Result<Batch> {
        let since = if self.seen.is_empty() {
            i64::MIN
        } else {
            self.newest - SLACK_MS
        };
        let session = self.session.clone();
        let rows = match self.connect() {
            Ok(Some(db)) => Self::rows(db, &session, since),
            Ok(None) => return Ok(Batch::Missing),
            Err(()) => return Ok(Batch::Replaced),
        };
        let (messages, parts) = match rows {
            Ok(rows) => rows,
            // No such session (yet), or it was deleted.
            Err(rusqlite::Error::QueryReturnedNoRows) => {
                return Ok(if self.seen.is_empty() {
                    Batch::Missing
                } else {
                    Batch::Replaced
                });
            }
            // Busy or locked by a writer: try again next poll.
            Err(e) => {
                self.complain(&e);
                return Ok(Batch::Events {
                    events: Vec::new(),
                    more: false,
                });
            }
        };
        self.complained = false;

        let seen = &self.seen;
        let fresh = |id: &str, t: i64, data: &Value| seen.get(id) != Some(&(t, digest(data)));
        let messages: Vec<MessageRow> = messages
            .into_iter()
            .filter(|m| fresh(&m.id, m.time_updated, &m.data))
            .collect();
        let mut by_id: HashMap<String, PartRow> = std::mem::take(&mut self.orphans)
            .into_iter()
            .map(|p| (p.id.clone(), p))
            .collect();
        for p in parts {
            if fresh(&p.id, p.time_updated, &p.data) {
                by_id.insert(p.id.clone(), p);
            }
        }
        for m in &messages {
            self.chat.know(m);
        }

        // In conversation order: by message, a message's parts by id (ids
        // sort by creation), the message's own item (its error) last.
        enum Row {
            Message(MessageRow),
            Part(PartRow),
        }
        let mut rows: Vec<((i64, String, u8, String), Row)> = Vec::new();
        for m in messages {
            rows.push((
                (m.time_created, m.id.clone(), 1, String::new()),
                Row::Message(m),
            ));
        }
        for (_, p) in by_id {
            match self.chat.created(&p.message_id) {
                Some(created) => rows.push((
                    (created, p.message_id.clone(), 0, p.id.clone()),
                    Row::Part(p),
                )),
                None => self.orphans.push(p),
            }
        }
        rows.sort_by(|a, b| a.0.cmp(&b.0));

        let mut events = Vec::new();
        for (_, row) in rows {
            let (id, t, d, produced) = match row {
                Row::Message(m) => (
                    m.id.clone(),
                    m.time_updated,
                    digest(&m.data),
                    self.chat.message(&m),
                ),
                Row::Part(p) => (
                    p.id.clone(),
                    p.time_updated,
                    digest(&p.data),
                    self.chat.part(&p),
                ),
            };
            self.seen.insert(id, (t, d));
            self.newest = self.newest.max(t);
            if self.quiet_before.is_none_or(|q| t >= q) {
                events.extend(produced.into_iter().map(|e| (e, None)));
            }
        }
        // A whole poll is one resume point: resuming re-asks for every row
        // updated since (items are upserts, so a repeat is harmless).
        if let Some(last) = events.last_mut() {
            last.1 = Some(self.newest.to_string());
        }
        Ok(Batch::Events {
            events,
            more: false,
        })
    }

    fn position(&self) -> String {
        self.newest.to_string()
    }
}

fn digest(data: &Value) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    data.to_string().hash(&mut h);
    h.finish()
}

/// Turns `message` and `part` rows into Chat items. A row may arrive again
/// (it was updated); the items it makes are sent again whole.
#[derive(Default)]
pub struct OpenCodeChat {
    messages: HashMap<String, Message>,
    /// The parts of each user message, by part id.
    users: HashMap<String, BTreeMap<String, UserPart>>,
}

struct Message {
    user: bool,
    created: i64,
}

enum UserPart {
    Text(String),
    Image(ChatImage),
    Nothing,
}

impl OpenCodeChat {
    /// Learns a message's role, before its parts are read.
    pub fn know(&mut self, m: &MessageRow) {
        self.messages.insert(
            m.id.clone(),
            Message {
                user: m.data["role"] == "user",
                created: m.time_created,
            },
        );
    }

    /// When a known message was created.
    pub fn created(&self, message: &str) -> Option<i64> {
        self.messages.get(message).map(|m| m.created)
    }

    /// The items of a message row itself: a failed assistant turn's error.
    pub fn message(&mut self, m: &MessageRow) -> Vec<ChatEvent> {
        self.know(m);
        let error = &m.data["error"];
        if m.data["role"] != "assistant" || !error.is_object() {
            return Vec::new();
        }
        let message = error["data"]["message"]
            .as_str()
            .filter(|s| !s.is_empty())
            .or_else(|| error["name"].as_str())
            .unwrap_or("error")
            .to_owned();
        vec![item(
            m.id.clone(),
            rfc3339(m.time_created),
            ChatBody::Error { message },
        )]
    }

    pub fn part(&mut self, p: &PartRow) -> Vec<ChatEvent> {
        let Some(message) = self.messages.get(&p.message_id) else {
            return Vec::new();
        };
        if message.user {
            let created = message.created;
            return self.user_part(p, created);
        }
        let d = &p.data;
        let ts = rfc3339(d["time"]["start"].as_i64().unwrap_or(p.time_created));
        let body = match d["type"].as_str() {
            Some("text") => {
                let text = d["text"].as_str().unwrap_or_default();
                if text.trim().is_empty() || d["ignored"] == true {
                    return Vec::new();
                }
                ChatBody::Agent {
                    text: text.to_owned(),
                }
            }
            Some("reasoning") => {
                let text = d["text"].as_str().unwrap_or_default();
                if text.trim().is_empty() {
                    return Vec::new();
                }
                ChatBody::Thinking {
                    text: Some(text.to_owned()),
                }
            }
            Some("tool") => tool(d),
            _ => return Vec::new(),
        };
        vec![item(p.id.clone(), ts, body)]
    }

    /// A user message is one item: its typed text and attached images.
    /// OpenCode's own notes about attachments (`synthetic` text) are left
    /// out.
    fn user_part(&mut self, p: &PartRow, created: i64) -> Vec<ChatEvent> {
        let d = &p.data;
        let part = match d["type"].as_str() {
            Some("text") if d["synthetic"] != true && d["ignored"] != true => {
                UserPart::Text(d["text"].as_str().unwrap_or_default().to_owned())
            }
            Some("file") => file_image(d).map_or(UserPart::Nothing, UserPart::Image),
            _ => UserPart::Nothing,
        };
        let parts = self.users.entry(p.message_id.clone()).or_default();
        parts.insert(p.id.clone(), part);
        let mut text = Vec::new();
        let mut images = Vec::new();
        for part in parts.values() {
            match part {
                UserPart::Text(t) if !t.trim().is_empty() => text.push(t.trim()),
                UserPart::Image(i) => images.push(i.clone()),
                _ => {}
            }
        }
        if text.is_empty() && images.is_empty() {
            return Vec::new();
        }
        vec![item(
            p.message_id.clone(),
            rfc3339(created),
            ChatBody::User {
                text: text.join("\n\n"),
                images,
            },
        )]
    }
}

fn item(id: String, ts: Option<String>, body: ChatBody) -> ChatEvent {
    ChatEvent::Item {
        item: ChatItem { id, ts, body },
    }
}

/// A `tool` part: `tool`, `state {status, input, output, error, title,
/// metadata, attachments}`.
fn tool(d: &Value) -> ChatBody {
    let name = d["tool"].as_str().unwrap_or("tool").to_owned();
    let state = &d["state"];
    let input = state["input"].clone();
    let (status, output) = match state["status"].as_str() {
        Some("completed") => (
            ToolStatus::Done,
            Some(state["output"].as_str().unwrap_or_default().to_owned()),
        ),
        Some("error") => (
            ToolStatus::Error,
            Some(state["error"].as_str().unwrap_or_default().to_owned()),
        ),
        _ => (ToolStatus::Running, None),
    };
    let diff = (status == ToolStatus::Done)
        .then(|| tool_diff(&name, &input, &state["metadata"]))
        .flatten();
    let images = (state["attachments"].as_array().into_iter().flatten())
        .filter_map(file_image)
        .collect();
    ChatBody::Tool {
        title: tool_title(&name, &input, state["title"].as_str()),
        question: (name == "question").then(|| question(&input)).flatten(),
        name,
        input,
        status,
        output,
        diff,
        images,
    }
}

/// `question` tool input `questions[{question, header, multiple,
/// options[{label, description}]}]`. `None` if it has no usable question.
fn question(input: &Value) -> Option<ChatQuestion> {
    let text = |v: &Value| v.as_str().filter(|s| !s.is_empty()).map(str::to_owned);
    let questions = input["questions"]
        .as_array()?
        .iter()
        .map(|q| {
            Some(Question {
                text: text(&q["question"])?,
                header: text(&q["header"]),
                multi: q["multiple"] == true,
                options: q["options"]
                    .as_array()?
                    .iter()
                    .map(|o| {
                        Some(QuestionOption {
                            label: text(&o["label"])?,
                            description: text(&o["description"]),
                        })
                    })
                    .collect::<Option<Vec<_>>>()?,
            })
        })
        .collect::<Option<Vec<_>>>()?;
    (!questions.is_empty()).then_some(ChatQuestion { questions })
}

/// The one line on a collapsed tool row: the file, command or pattern it
/// works on, else OpenCode's own title.
fn tool_title(name: &str, input: &Value, title: Option<&str>) -> String {
    let field = |k: &str| {
        input[k]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
    };
    let title = match name {
        "question" => input["questions"][0]["question"]
            .as_str()
            .map(str::to_owned),
        _ => [
            "filePath",
            "command",
            "pattern",
            "url",
            "path",
            "description",
            "prompt",
        ]
        .iter()
        .find_map(|k| field(k)),
    }
    .or_else(|| title.filter(|t| !t.is_empty()).map(str::to_owned))
    .or_else(|| {
        input
            .as_object()
            .and_then(|o| o.values().find_map(|v| v.as_str().map(str::to_owned)))
    })
    .unwrap_or_default();
    let first = title.lines().next().unwrap_or_default();
    let mut out: String = first.chars().take(120).collect();
    if out.len() < title.len() {
        out.push('…');
    }
    out
}

/// What a finished file tool changed: the unified diff OpenCode records
/// (`metadata.diff`, `metadata.filediff.patch`), or a written file's content.
fn tool_diff(name: &str, input: &Value, metadata: &Value) -> Option<ChatDiff> {
    let patch = metadata["diff"]
        .as_str()
        .or_else(|| metadata["filediff"]["patch"].as_str())
        .filter(|p| p.contains("@@"));
    if let Some(patch) = patch {
        let fallback = input["filePath"].as_str().unwrap_or_default();
        return Some(unified_diff(patch, fallback));
    }
    if name == "write" {
        let path = input["filePath"].as_str().unwrap_or_default();
        return Some(media::created(path, input["content"].as_str()?));
    }
    None
}

/// The first file of a unified diff. Other files, if any, mark it
/// truncated.
pub fn unified_diff(patch: &str, fallback_path: &str) -> ChatDiff {
    let mut path: Option<String> = None;
    let mut hunks: Vec<DiffHunk> = Vec::new();
    let mut more_files = false;
    let mut in_hunk = false;
    for line in patch.lines() {
        if let Some(header) = line.strip_prefix("@@ ") {
            match hunk_header(header) {
                Some(h) => {
                    hunks.push(h);
                    in_hunk = true;
                }
                None => in_hunk = false,
            }
            continue;
        }
        let file_header = line.starts_with("Index: ")
            || line.starts_with("diff ")
            || line.starts_with("--- ")
            || line.starts_with("+++ ");
        if in_hunk && !file_header {
            if let Some(h) = hunks.last_mut() {
                match line.chars().next() {
                    Some('+' | '-' | ' ') => h.lines.push(line.to_owned()),
                    None => h.lines.push(" ".to_owned()),
                    _ => {} // "\ No newline at end of file"
                }
            }
            continue;
        }
        in_hunk = false;
        if !hunks.is_empty() && file_header {
            more_files = true;
            break;
        }
        if let Some(p) = line.strip_prefix("+++ ") {
            let p = p.split('\t').next().unwrap_or_default().trim();
            let p = p.strip_prefix("b/").unwrap_or(p);
            if !p.is_empty() && p != "/dev/null" {
                path = Some(p.to_owned());
            }
        }
    }
    let path = path.unwrap_or_else(|| fallback_path.to_owned());
    let mut diff = media::diff(&path, hunks);
    diff.truncated |= more_files;
    diff
}

/// `-a,b +c,d @@ ...` (the part after `@@ `).
fn hunk_header(h: &str) -> Option<DiffHunk> {
    let mut it = h.split_whitespace();
    let range = |s: &str| -> Option<(u32, u32)> {
        let (start, len) = s.split_once(',').unwrap_or((s, "1"));
        Some((start.parse().ok()?, len.parse().ok()?))
    };
    let (old_start, old_lines) = range(it.next()?.strip_prefix('-')?)?;
    let (new_start, new_lines) = range(it.next()?.strip_prefix('+')?)?;
    Some(DiffHunk {
        old_start,
        old_lines,
        new_start,
        new_lines,
        lines: Vec::new(),
    })
}

/// A `file` part (or tool attachment) holding an image as a data URL.
fn file_image(d: &Value) -> Option<ChatImage> {
    let url = d["url"].as_str()?;
    let (meta, data) = url.strip_prefix("data:")?.split_once(',')?;
    let media_type = meta.strip_suffix(";base64")?;
    let media_type = d["mime"].as_str().unwrap_or(media_type);
    if !media_type.starts_with("image/") {
        return None;
    }
    Some(media::image(media_type, data, None))
}

/// Milliseconds since the epoch as RFC 3339 UTC (`2026-11-03T06:22:04.684Z`).
pub fn rfc3339(ms: i64) -> Option<String> {
    if ms <= 0 {
        return None;
    }
    let secs = ms.div_euclid(1000);
    let millis = ms.rem_euclid(1000);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // civil_from_days (H. Hinnant).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    Some(format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{millis:03}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn formats_epoch_milliseconds_as_rfc3339() {
        assert_eq!(
            rfc3339(1_793_686_924_684).as_deref(),
            Some("2026-11-03T06:22:04.684Z")
        );
        assert_eq!(
            rfc3339(951_782_400_000).as_deref(),
            Some("2000-02-29T00:00:00.000Z")
        );
        assert_eq!(rfc3339(0), None);
    }

    #[test]
    fn recognises_opencode_command_lines() {
        assert!(is_opencode(&argv(&["opencode"])));
        assert!(is_opencode(&argv(&[
            "/x/node_modules/opencode-ai/bin/opencode.exe",
            "-s",
            "a"
        ])));
        assert!(is_opencode(&argv(&["/bin/sh", "/tmp/fake/opencode", "s"])));
        assert!(!is_opencode(&argv(&["bash"])));
        assert!(!is_opencode(&argv(&["vim", "notes.txt", "opencode"])));
    }

    #[test]
    fn a_resumed_session_pairs_by_its_argument() {
        assert_eq!(
            session_arg(&argv(&["opencode", "-s", "ses_1"])).as_deref(),
            Some("ses_1")
        );
        assert_eq!(
            session_arg(&argv(&["opencode", "--session=ses_2"])).as_deref(),
            Some("ses_2")
        );
        assert_eq!(session_arg(&argv(&["opencode", "-c"])), None);
    }

    #[test]
    fn reads_the_first_file_of_a_unified_diff() {
        let patch = "Index: /w/n.txt\n===\n--- /w/n.txt\n+++ /w/n.txt\n@@ -1,3 +1,3 @@\n line one\n-line two\n+line 2\n line three\n\\ No newline at end of file\nIndex: /w/o.txt\n--- /w/o.txt\n+++ /w/o.txt\n@@ -1 +1 @@\n-a\n+b\n";
        let d = unified_diff(patch, "fallback");
        assert_eq!(d.path, "/w/n.txt");
        assert_eq!(d.hunks.len(), 1);
        assert_eq!(
            d.hunks[0].lines,
            [" line one", "-line two", "+line 2", " line three"]
        );
        assert_eq!((d.hunks[0].old_start, d.hunks[0].new_lines), (1, 3));
        assert!(d.truncated);
    }
}
