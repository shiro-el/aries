//! Call history on the Host: `aries-beacon call` and `aries-beacon calls`.
//!
//! The log is `calls.jsonl` in the Beacon's private state directory (0700,
//! file 0600), one [`LogEntry`] per line. Every write takes an exclusive
//! lock on `calls.lock`, appends one whole line and syncs it; when the log
//! passes [`MAX_BYTES`] or holds a Call older than [`MAX_AGE_MS`], the
//! writer first rewrites it (folded, old Calls dropped, newest kept within
//! half the size cap) to a temporary file and renames it over the log.
//! Seqs are kept by the rewrite, so followers never see a Call twice.

use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use aries_protocol::calls::{
    Answer, AnswerState, CallsLine, LogEntry, Recipient, ReplyLine, ReplyOutcome, Urgency, fold,
    validate,
};

pub use aries_protocol::calls::Call;

/// The log is compacted once it is bigger than this.
pub const MAX_BYTES: u64 = 1024 * 1024;
/// Calls older than this are dropped at the next compaction (30 days).
pub const MAX_AGE_MS: u64 = 30 * 24 * 60 * 60 * 1000;
/// How long `call` waits for herdr-core to confirm the caller.
const CONFIRM_TIMEOUT: Duration = Duration::from_secs(5);
/// How often `calls --follow` looks at the log.
const POLL: Duration = Duration::from_millis(200);

pub struct Log {
    dir: PathBuf,
}

/// What the Agent asked for, before it is recorded.
pub struct NewCall {
    pub terminal: Option<String>,
    pub recipient: Option<Recipient>,
    pub top_level: bool,
    pub title: String,
    pub body: String,
    pub urgency: Urgency,
    pub choices: Vec<String>,
}

impl Log {
    pub fn new(dir: PathBuf) -> Self {
        Self { dir }
    }

    pub fn path(&self) -> PathBuf {
        self.dir.join("calls.jsonl")
    }

    /// Every readable entry, in order. A torn last line (a write in
    /// progress) and unreadable lines are skipped.
    pub fn entries(&self) -> std::io::Result<Vec<LogEntry>> {
        read_entries(&self.path())
    }

    /// Runs `f` with the log's entries under the exclusive write lock and
    /// appends what it returns. The single place that writes the log:
    /// recording a Call and (ticket 35) claiming an answer both go through it.
    pub fn locked<T>(
        &self,
        f: impl FnOnce(&[LogEntry], u64) -> (Option<LogEntry>, T),
    ) -> std::io::Result<T> {
        create_private_dir(&self.dir)?;
        let lock = OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(self.dir.join("calls.lock"))?;
        lock.lock()?;
        let mut entries = self.entries()?;
        let now = now_ms();
        if self.needs_compaction(&entries, now)? {
            entries = compact(entries, now);
            self.rewrite(&entries)?;
        }
        let next = entries.iter().map(LogEntry::seq).max().unwrap_or(0) + 1;
        let (entry, out) = f(&entries, next);
        if let Some(entry) = entry {
            let mut line = serde_json::to_vec(&entry).map_err(std::io::Error::other)?;
            line.push(b'\n');
            let mut file = open_private(&self.path(), true)?;
            file.write_all(&line)?;
            file.sync_data()?;
        }
        drop(lock);
        Ok(out)
    }

    /// Records a new Call and returns it.
    pub fn append(&self, new: NewCall) -> std::io::Result<Call> {
        self.locked(|_, seq| {
            let at_ms = now_ms();
            let call = Call {
                id: format!("c{at_ms}-{}", random_hex()),
                at_ms,
                terminal: new.terminal,
                recipient: new.recipient,
                top_level: new.top_level,
                title: new.title,
                body: new.body,
                urgency: new.urgency,
                choices: new.choices,
                answer: None,
            };
            (
                Some(LogEntry::Call {
                    seq,
                    call: call.clone(),
                }),
                call,
            )
        })
    }

    fn needs_compaction(&self, entries: &[LogEntry], now: u64) -> std::io::Result<bool> {
        let size = match std::fs::metadata(self.path()) {
            Ok(m) => m.len(),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(false),
            Err(e) => return Err(e),
        };
        let oldest = entries.iter().find_map(|e| match e {
            LogEntry::Call { call, .. } => Some(call.at_ms),
            LogEntry::Answer { .. } => None,
        });
        Ok(size > MAX_BYTES || oldest.is_some_and(|t| now.saturating_sub(t) > MAX_AGE_MS))
    }

    fn rewrite(&self, entries: &[LogEntry]) -> std::io::Result<()> {
        let tmp = self.dir.join("calls.jsonl.tmp");
        let mut body = Vec::new();
        for e in entries {
            serde_json::to_writer(&mut body, e).map_err(std::io::Error::other)?;
            body.push(b'\n');
        }
        let _ = std::fs::remove_file(&tmp);
        let mut file = open_private(&tmp, false)?;
        file.write_all(&body)?;
        file.sync_all()?;
        std::fs::rename(&tmp, self.path())
    }
}

/// Folds the log, drops Calls past the age cap, and keeps the newest that
/// fit in half the size cap, each as one Call entry with its answer.
fn compact(entries: Vec<LogEntry>, now: u64) -> Vec<LogEntry> {
    let mut calls = fold(entries);
    calls.sort_by_key(|(seq, _)| std::cmp::Reverse(*seq));
    let mut kept: Vec<LogEntry> = Vec::new();
    let mut size = 0u64;
    for (i, (seq, call)) in calls.into_iter().enumerate() {
        // The Call holding the highest seq always stays, so the next seq
        // stays above every seq ever handed out and followers miss nothing.
        let newest = i == 0;
        if !newest && now.saturating_sub(call.at_ms) > MAX_AGE_MS {
            continue;
        }
        let entry = LogEntry::Call { seq, call };
        let len = serde_json::to_vec(&entry)
            .map(|v| v.len() as u64 + 1)
            .unwrap_or(0);
        if !newest && size + len > MAX_BYTES / 2 {
            break;
        }
        size += len;
        kept.push(entry);
    }
    kept.reverse();
    kept
}

fn read_entries(path: &Path) -> std::io::Result<Vec<LogEntry>> {
    let file = match File::open(path) {
        Ok(f) => f,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(e),
    };
    let mut out = Vec::new();
    for line in BufReader::new(file).split(b'\n') {
        if let Ok(entry) = serde_json::from_slice::<LogEntry>(&line?) {
            out.push(entry);
        }
    }
    Ok(out)
}

#[cfg(unix)]
fn create_private_dir(dir: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(dir)
}

#[cfg(not(unix))]
fn create_private_dir(dir: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)
}

fn open_private(path: &Path, append: bool) -> std::io::Result<File> {
    let mut o = OpenOptions::new();
    o.create(true);
    if append {
        o.append(true);
    } else {
        o.write(true).truncate(true);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600);
    }
    o.open(path)
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn random_hex() -> String {
    let mut bytes = [0u8; 8];
    if File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut bytes))
        .is_err()
    {
        // No urandom: time and pid still make it unique enough on one Host.
        let n = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or(0);
        bytes[..4].copy_from_slice(&n.to_le_bytes());
        bytes[4..].copy_from_slice(&std::process::id().to_le_bytes());
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

// ---------------------------------------------------------------- `call`

const CALL_USAGE: &str = "usage: aries-beacon call --title TEXT [--body TEXT|-] \
[--urgency record|check|urgent|emergency] [--choice TEXT]...  (at most 3 Choices)";

/// `aries-beacon call ...`: records the Call, then (later tickets) delivers
/// it. Prints the recorded [`Call`] as one JSON line. Exit 2 on bad usage,
/// 1 when it could not be recorded.
pub fn call_command(log: &Log, args: &[String]) -> i32 {
    let new = match parse_call(args) {
        Ok(n) => n,
        Err(e) => {
            eprintln!("aries-beacon call: {e}\n{CALL_USAGE}");
            return 2;
        }
    };
    let (terminal, top_level, recipient) = match confirm_caller() {
        Ok(c) => (Some(c.terminal), c.top_level, Some(c.recipient)),
        Err(why) => {
            eprintln!("aries-beacon call: caller not confirmed ({why}); the Call is only recorded");
            (None, false, None)
        }
    };
    let call = match log.append(NewCall {
        terminal,
        top_level,
        recipient,
        ..new
    }) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("aries-beacon call: could not record the Call: {e}");
            return 1;
        }
    };
    // Recorded first: whatever the push does next, a failure there never
    // loses the Call.
    println!("{}", serde_json::to_string(&call).expect("call json"));
    let _ = std::io::stdout().flush();
    crate::call_push::deliver(&call);
    0
}

fn parse_call(args: &[String]) -> Result<NewCall, String> {
    let mut title = None;
    let mut body = String::new();
    let mut urgency = Urgency::Check;
    let mut choices = Vec::new();
    let mut it = args.iter();
    while let Some(flag) = it.next() {
        let mut value = || it.next().cloned().ok_or(format!("{flag} needs a value"));
        match flag.as_str() {
            "--title" => title = Some(value()?),
            "--body" => {
                body = value()?;
                if body == "-" {
                    body.clear();
                    std::io::stdin()
                        .read_to_string(&mut body)
                        .map_err(|e| format!("read body: {e}"))?;
                }
            }
            "--urgency" => {
                let v = value()?;
                urgency = Urgency::parse(&v).ok_or(format!("unknown urgency {v:?}"))?;
            }
            "--choice" => choices.push(value()?),
            other => return Err(format!("unknown argument {other:?}")),
        }
    }
    let title = title.ok_or("--title is required")?;
    let body = body.trim_end().to_owned();
    validate(&title, &body, &choices).map_err(|e| e.to_string())?;
    Ok(NewCall {
        terminal: None,
        recipient: None,
        top_level: false,
        title,
        body,
        urgency,
        choices,
    })
}

struct Caller {
    terminal: String,
    top_level: bool,
    recipient: Recipient,
}

/// The Terminal this process runs in, as herdr-core confirms it: the
/// Terminal named by `HC_TERMINAL_ID` must be live in the session the
/// environment points at, and its program must be an ancestor of this
/// process (so a copied or stale variable does not pass).
fn confirm_caller() -> Result<Caller, String> {
    let id = std::env::var("HC_TERMINAL_ID")
        .ok()
        .filter(|v| !v.is_empty())
        .ok_or("not run inside a herdr-core Terminal (no HC_TERMINAL_ID)")?;
    let t = crate::rpc_once(
        "terminal.get",
        serde_json::json!({ "terminal": id }),
        CONFIRM_TIMEOUT,
    )?;
    if t["state"] != "live" {
        return Err(format!("Terminal {id} is not live"));
    }
    let pid = t["pid"]
        .as_u64()
        .ok_or(format!("herdr-core reports no pid for {id}"))? as u32;
    if !is_ancestor(pid) {
        return Err(format!("this process does not run in Terminal {id}"));
    }
    let terminal = t["address"].as_str().unwrap_or(&id).to_owned();
    // The first descendant of the Terminal's program is the Agent when
    // it runs inside a shell; a short-lived `call` helper is never bound.
    let mut agent_pid = pid;
    let mut child = std::process::id();
    while is_shell(pid)
        && let Some(parent) = parent_of(child)
    {
        if parent == pid {
            if child != std::process::id() {
                agent_pid = child;
            }
            break;
        }
        child = parent;
    }
    let recipient = Recipient {
        terminal_pid: pid,
        created_ms: t["created_ms"]
            .as_u64()
            .ok_or("Terminal has no creation identity")?,
        agent_pid,
        process: process_identity(agent_pid).ok_or("Agent process identity unavailable")?,
    };
    Ok(Caller {
        terminal,
        top_level: t["parent"].is_null(),
        recipient,
    })
}

/// `pid` is this process or one of its ancestors.
fn is_ancestor(pid: u32) -> bool {
    let mut cur = std::process::id();
    for _ in 0..256 {
        if cur == pid {
            return true;
        }
        match parent_of(cur) {
            Some(p) if p != 0 && p != cur => cur = p,
            _ => return false,
        }
    }
    false
}

fn parent_of(pid: u32) -> Option<u32> {
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // `pid (comm) state ppid ...`; comm may hold spaces and parentheses.
    let rest = &stat[stat.rfind(')')? + 1..];
    rest.split_whitespace().nth(1)?.parse().ok()
}

fn process_identity(pid: u32) -> Option<String> {
    use sha2::{Digest, Sha256};
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let rest = &stat[stat.rfind(')')? + 1..];
    let start = rest.split_whitespace().nth(19)?;
    let argv = std::fs::read(format!("/proc/{pid}/cmdline")).ok()?;
    if argv.is_empty() {
        return None;
    }
    let hash: String = Sha256::digest(argv)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    let boot = std::fs::read_to_string("/proc/sys/kernel/random/boot_id").ok()?;
    Some(format!("{}:{start}:{hash}", boot.trim()))
}

fn is_shell(pid: u32) -> bool {
    let Ok(argv) = std::fs::read(format!("/proc/{pid}/cmdline")) else {
        return false;
    };
    let args: Vec<_> = argv.split(|b| *b == 0).filter(|s| !s.is_empty()).collect();
    let Some(program) = args.first() else {
        return false;
    };
    let name = program.rsplit(|b| *b == b'/').next().unwrap_or(program);
    matches!(name, b"sh" | b"bash" | b"dash" | b"zsh" | b"fish")
        && (args[1..].iter().all(|a| a.starts_with(b"-"))
            || args
                .get(1)
                .is_some_and(|a| matches!(*a, b"-c" | b"-lc" | b"-ic")))
}

// --------------------------------------------------------------- `reply`

/// How long `reply` waits for herdr-core to confirm the typing. herdr-core
/// holds a send while a person types at the Host, so this is generous; past
/// it the answer is recorded unconfirmed.
const SEND_TIMEOUT: Duration = Duration::from_secs(30);

/// `aries-beacon reply CALL CHOICE`: types Choice `CHOICE` (0-based) of Call
/// `CALL` into the Terminal that sent it, then Enter. Claims the Call in the
/// log first, so it is typed at most once ever. Prints one [`ReplyLine`];
/// exit 0 only when delivered, 2 on bad usage.
pub fn reply_command(log: &Log, args: &[String]) -> i32 {
    let (id, choice) = match args {
        [id, choice] => match choice.parse::<u32>() {
            Ok(n) => (id.as_str(), n),
            Err(_) => return reply_usage(&format!("bad choice {choice:?}")),
        },
        _ => return reply_usage("expected CALL and CHOICE"),
    };
    let line = reply(log, id, choice);
    println!("{}", line.to_json());
    if line.outcome == ReplyOutcome::Delivered {
        0
    } else {
        1
    }
}

fn reply_usage(why: &str) -> i32 {
    eprintln!(
        "aries-beacon reply: {why}\nusage: aries-beacon reply CALL CHOICE  (CHOICE is 0-based)"
    );
    2
}

fn reply(log: &Log, id: &str, choice: u32) -> ReplyLine {
    // The durable claim: under the log lock, only a Call without any
    // answer gets one.
    let claimed = log.locked(|entries, seq| {
        let Some((_, call)) = fold(entries.iter().cloned())
            .into_iter()
            .find(|(_, c)| c.id == id)
        else {
            return (None, Err(ReplyLine::new(ReplyOutcome::NoCall, None, None)));
        };
        if let Some(answer) = call.answer {
            return (
                None,
                Err(ReplyLine::new(ReplyOutcome::Answered, Some(answer), None)),
            );
        }
        let Some(terminal) = call.terminal else {
            return (
                None,
                Err(ReplyLine::new(ReplyOutcome::NotAnswerable, None, None)),
            );
        };
        let Some(text) = call.choices.get(choice as usize) else {
            return (
                None,
                Err(ReplyLine::new(ReplyOutcome::NoChoice, None, None)),
            );
        };
        let answer = Answer {
            choice,
            text: text.clone(),
            state: AnswerState::Claimed,
            at_ms: now_ms(),
            detail: None,
        };
        (
            Some(LogEntry::Answer {
                seq,
                id: id.to_owned(),
                answer: answer.clone(),
            }),
            Ok((terminal, call.recipient, answer)),
        )
    });
    let (terminal, recipient, answer) = match claimed {
        Ok(Ok(c)) => c,
        Ok(Err(line)) => return line,
        Err(e) => {
            // Nothing claimed, nothing typed.
            return ReplyLine::new(
                ReplyOutcome::Failed,
                None,
                Some(format!("could not claim the Call: {e}")),
            );
        }
    };

    let (state, detail) = match deliver(&terminal, recipient.as_ref(), &answer.text) {
        Sent::Yes => (AnswerState::Delivered, None),
        Sent::No(why) => (AnswerState::Failed, Some(why)),
        Sent::Maybe(why) => (AnswerState::Unconfirmed, Some(why)),
    };
    let done = Answer {
        state,
        detail: detail.clone(),
        at_ms: now_ms(),
        ..answer
    };
    let recorded = log.locked(|_, seq| {
        (
            Some(LogEntry::Answer {
                seq,
                id: id.to_owned(),
                answer: done.clone(),
            }),
            (),
        )
    });
    let outcome = match state {
        AnswerState::Delivered => ReplyOutcome::Delivered,
        AnswerState::Failed => ReplyOutcome::Failed,
        _ => ReplyOutcome::Unconfirmed,
    };
    let detail = match recorded {
        Ok(()) => detail,
        // The claim stays; the outcome is still what happened.
        Err(e) => Some(format!(
            "{}could not record the result: {e}",
            detail.map(|d| d + "; ").unwrap_or_default()
        )),
    };
    ReplyLine::new(outcome, Some(done), detail)
}

enum Sent {
    Yes,
    /// Certainly not typed.
    No(String),
    /// May have been typed.
    Maybe(String),
}

/// Checks that `terminal` is still live, then one herdr-core
/// `terminal.send` (paste the text, then Enter); never retried. A refusal
/// (an error reply) types nothing; a lost connection or no answer within
/// [`SEND_TIMEOUT`] may have.
fn deliver(terminal: &str, recipient: Option<&Recipient>, text: &str) -> Sent {
    let Some(recipient) = recipient else {
        return Sent::No("the original Agent identity was not recorded".into());
    };
    let mut link = match aries_beacon::core_link::CoreLink::connect() {
        Ok(l) => l,
        Err(e) => return Sent::No(format!("herdr-core: {e}")),
    };
    match link.call("terminal.get", serde_json::json!({ "terminal": terminal })) {
        Ok(t)
            if t["state"] == "live"
                && t["pid"].as_u64() == Some(u64::from(recipient.terminal_pid))
                && t["created_ms"].as_u64() == Some(recipient.created_ms)
                && process_identity(recipient.agent_pid).as_ref() == Some(&recipient.process) => {}
        Ok(_) => return Sent::No(format!("Terminal {terminal} has ended")),
        Err(e) if e.starts_with("rpc") => {
            return Sent::No(format!("Terminal {terminal} is gone ({e})"));
        }
        Err(e) => return Sent::No(format!("herdr-core: {e}")),
    }
    let (tx, rx) = std::sync::mpsc::channel();
    let params = serde_json::json!({ "terminal": terminal, "text": text });
    std::thread::spawn(move || {
        let _ = tx.send(link.call("terminal.send", params));
    });
    match rx.recv_timeout(SEND_TIMEOUT) {
        Ok(Ok(_)) => Sent::Yes,
        Ok(Err(e)) if e.starts_with("rpc") => Sent::No(format!("herdr-core refused it: {e}")),
        Ok(Err(e)) => Sent::Maybe(e),
        Err(_) => Sent::Maybe("herdr-core did not confirm in time".into()),
    }
}

// --------------------------------------------------------------- `calls`

/// `aries-beacon calls [--follow]`: prints every Call (oldest first), a
/// `synced` line, and with `--follow` each Call again when it is added or
/// changes, plus heartbeats, until stdout closes.
pub fn calls_command(log: &Log, args: &[String]) -> i32 {
    let follow = match args {
        [] => false,
        [f] if f == "--follow" => true,
        _ => {
            eprintln!("usage: aries-beacon calls [--follow]");
            return 2;
        }
    };
    match run_calls(log, follow) {
        Ok(()) => 0,
        // stdout closed: the reader went away.
        Err(e) if e.kind() == std::io::ErrorKind::BrokenPipe => 0,
        Err(e) => {
            eprintln!("aries-beacon calls: {e}");
            1
        }
    }
}

fn run_calls(log: &Log, follow: bool) -> std::io::Result<()> {
    let mut out = std::io::stdout().lock();
    let mut emit = |line: &CallsLine| -> std::io::Result<()> {
        serde_json::to_writer(&mut out, line).map_err(std::io::Error::other)?;
        out.write_all(b"\n")?;
        out.flush()
    };
    let path = log.path();
    let mut stamp = file_stamp(&path);
    let mut last = 0u64;
    for (seq, call) in fold(log.entries()?) {
        last = last.max(seq);
        emit(&CallsLine::Call { seq, call })?;
    }
    emit(&CallsLine::Synced { seq: last })?;
    if !follow {
        return Ok(());
    }
    let heartbeat = Duration::from_secs(aries_protocol::calls::HEARTBEAT_SECS);
    let mut beat = Instant::now();
    loop {
        std::thread::sleep(POLL);
        let now = file_stamp(&path);
        if now != stamp {
            stamp = now;
            let mut calls = fold(log.entries()?);
            calls.sort_by_key(|(seq, _)| *seq);
            for (seq, call) in calls {
                if seq > last {
                    last = seq;
                    emit(&CallsLine::Call { seq, call })?;
                }
            }
        }
        if beat.elapsed() >= heartbeat {
            beat = Instant::now();
            emit(&CallsLine::Heartbeat)?;
        }
    }
}

/// Changes whenever the log is appended to or replaced.
fn file_stamp(path: &Path) -> Option<(u64, u64, SystemTime)> {
    let m = std::fs::metadata(path).ok()?;
    #[cfg(unix)]
    let ino = std::os::unix::fs::MetadataExt::ino(&m);
    #[cfg(not(unix))]
    let ino = 0;
    Some((ino, m.len(), m.modified().ok()?))
}
