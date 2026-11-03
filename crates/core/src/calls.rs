//! Calls on the phone: a Host's Call history and new Calls as they come.
//!
//! The Beacon owns the history (`aries-beacon calls`, format in
//! `aries_protocol::calls`). The core runs `aries-beacon calls --follow` over
//! its own SSH exec channel (not the herdr-core connection; a local shell for
//! Local Hosts, like the Chat), folds the lines
//! into [`CallInfo`]s and hands the whole list to a [`CallListener`] after
//! the first sync and each change. A dropped connection is retried with
//! backoff; every reconnect starts from a fresh snapshot that replaces the
//! list, so nothing is missed or doubled, and Calls that arrived while it was
//! away are reported as new once.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use aries_protocol::calls::{self as wire, CallsLine};
use tokio::io::{AsyncBufReadExt, BufReader};

use crate::chat::ChatHost;
use crate::error::CoreError;
use crate::transport::Stream;

/// No line (not even a heartbeat) for this long means the stream is dead.
const SILENCE: Duration = Duration::from_secs(wire::HEARTBEAT_SECS * 3);
const MAX_BACKOFF: Duration = Duration::from_secs(60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CallUrgency {
    Record,
    Check,
    Urgent,
    Emergency,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CallAnswerState {
    Claimed,
    Delivered,
    Failed,
    Unconfirmed,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CallAnswer {
    /// Index into [`CallInfo::choices`].
    pub choice: u32,
    pub text: String,
    pub state: CallAnswerState,
    pub at_ms: u64,
    pub detail: Option<String>,
}

/// One Call from a Host's history.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CallInfo {
    /// The Host it came from.
    pub host_id: String,
    pub id: String,
    /// When the Beacon recorded it, Unix milliseconds.
    pub at_ms: u64,
    /// The calling Terminal's address; `None` when the Beacon could not
    /// confirm the caller (then there is no Terminal to open or answer).
    pub terminal: Option<String>,
    /// The caller is a top-level Agent, not a helper another Agent started.
    pub top_level: bool,
    pub title: String,
    pub body: String,
    pub urgency: CallUrgency,
    pub choices: Vec<String>,
    pub answer: Option<CallAnswer>,
    /// The default policy pushes (and the app banners) this Call: a
    /// confirmed top-level caller and an urgency above `record`. Other
    /// Calls are only recorded.
    pub pushes_by_default: bool,
}

impl From<wire::Urgency> for CallUrgency {
    fn from(u: wire::Urgency) -> Self {
        match u {
            wire::Urgency::Record => Self::Record,
            wire::Urgency::Check => Self::Check,
            wire::Urgency::Urgent => Self::Urgent,
            wire::Urgency::Emergency => Self::Emergency,
        }
    }
}

impl CallInfo {
    pub(crate) fn from_wire(host_id: &str, c: wire::Call) -> Self {
        Self {
            pushes_by_default: c.pushes_by_default(),
            host_id: host_id.to_owned(),
            id: c.id,
            at_ms: c.at_ms,
            terminal: c.terminal,
            top_level: c.top_level,
            title: c.title,
            body: c.body,
            urgency: c.urgency.into(),
            choices: c.choices,
            answer: c.answer.map(|a| CallAnswer {
                choice: a.choice,
                text: a.text,
                state: match a.state {
                    wire::AnswerState::Claimed => CallAnswerState::Claimed,
                    wire::AnswerState::Delivered => CallAnswerState::Delivered,
                    wire::AnswerState::Failed => CallAnswerState::Failed,
                    wire::AnswerState::Unconfirmed => CallAnswerState::Unconfirmed,
                },
                at_ms: a.at_ms,
                detail: a.detail,
            }),
        }
    }
}

/// Where the feed is.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum CallFeedState {
    /// In sync with the Host's history.
    Live,
    /// Lost the Host (or never reached it); trying again.
    Reconnecting { detail: String },
}

/// Receives a Host's Calls from [`crate::AriesCore::watch_calls`].
#[uniffi::export(with_foreign)]
pub trait CallListener: Send + Sync {
    /// The whole history, newest first: after each sync and each change.
    fn on_calls(&self, calls: Vec<CallInfo>);
    /// A Call that was not there at the first sync (show a banner). Each
    /// Call is reported at most once per watch.
    fn on_new_call(&self, call: CallInfo);
    fn on_state(&self, state: CallFeedState);
}

/// The Host-side command: `aries-beacon <args>`, or `not-installed` without
/// a Beacon. `ARIES_BEACON` overrides the installed path (development and
/// Local test Hosts), as for the Chat.
fn command(args: &[&str]) -> String {
    let words: Vec<String> = args.iter().map(|a| sh_quote(a)).collect();
    let script = format!(
        r#"b="${{ARIES_BEACON:-$HOME/.local/bin/aries-beacon}}"; [ -x "$b" ] || {{ echo not-installed; exit 3; }}; exec "$b" {}"#,
        words.join(" ")
    );
    format!("sh -c {}", sh_quote(&script))
}

fn sh_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

/// Reads one `calls` stream up to and including its `synced` line.
struct Feed {
    lines: tokio::io::Lines<BufReader<Box<dyn tokio::io::AsyncRead + Send + Unpin>>>,
    _stream_guard: Box<dyn Send + Sync>,
    _writer: Box<dyn tokio::io::AsyncWrite + Send + Unpin>,
}

impl Feed {
    fn new(stream: Stream) -> Self {
        Self {
            lines: BufReader::new(stream.reader).lines(),
            _stream_guard: stream.guard,
            _writer: stream.writer,
        }
    }

    /// The next protocol line; skips login noise. `Closed` at the end.
    async fn next(&mut self) -> Result<CallsLine, CoreError> {
        loop {
            let line = self
                .lines
                .next_line()
                .await
                .map_err(|e| CoreError::Unreachable {
                    detail: e.to_string(),
                })?
                .ok_or(CoreError::Closed)?;
            let line = line.trim();
            if line == "not-installed" {
                return Err(CoreError::BeaconNotInstalled);
            }
            if let Ok(l) = serde_json::from_str::<CallsLine>(line) {
                return Ok(l);
            }
        }
    }

    /// Everything up to `synced`, as `(seq, call)` with later lines for the
    /// same id replacing earlier ones.
    async fn snapshot(&mut self) -> Result<Vec<(u64, wire::Call)>, CoreError> {
        let mut calls: Vec<(u64, wire::Call)> = Vec::new();
        loop {
            match self.next().await? {
                CallsLine::Call { seq, call } => upsert(&mut calls, seq, call),
                CallsLine::Synced { .. } => return Ok(calls),
                CallsLine::Heartbeat => {}
            }
        }
    }
}

fn upsert(calls: &mut Vec<(u64, wire::Call)>, seq: u64, call: wire::Call) {
    match calls.iter_mut().find(|(_, c)| c.id == call.id) {
        Some(slot) => *slot = (seq, call),
        None => calls.push((seq, call)),
    }
}

/// Newest first.
fn listing(host_id: &str, calls: &[(u64, wire::Call)]) -> Vec<CallInfo> {
    let mut out: Vec<CallInfo> = calls
        .iter()
        .map(|(_, c)| CallInfo::from_wire(host_id, c.clone()))
        .collect();
    out.sort_by(|a, b| b.at_ms.cmp(&a.at_ms).then_with(|| b.id.cmp(&a.id)));
    out
}

/// The Host's whole Call history, newest first.
pub(crate) async fn history(runner: &ChatHost, host_id: &str) -> Result<Vec<CallInfo>, CoreError> {
    let mut feed = Feed::new(runner.open(&command(&["calls"])).await?);
    let calls = feed.snapshot().await?;
    Ok(listing(host_id, &calls))
}

/// A running [`crate::AriesCore::watch_calls`].
#[derive(uniffi::Object)]
pub struct CallWatch {
    task: tokio::task::JoinHandle<()>,
    /// Cuts the current connection (it is then retried), for tests.
    cut: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>>,
}

#[uniffi::export]
impl CallWatch {
    /// Stops watching; no more listener calls after it returns.
    pub fn stop(&self) {
        self.task.abort();
    }
}

impl CallWatch {
    /// Drops the current connection as a network loss would; the watch
    /// reconnects by itself. For tests; not exported to the app.
    pub fn drop_connection(&self) {
        if let Some(tx) = self.cut.lock().expect("cut lock").take() {
            let _ = tx.send(());
        }
    }
}

impl Drop for CallWatch {
    fn drop(&mut self) {
        self.task.abort();
    }
}

pub(crate) fn watch(
    runner: ChatHost,
    host_id: String,
    listener: Arc<dyn CallListener>,
) -> CallWatch {
    let cut: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>> = Arc::default();
    let task = crate::runtime().spawn(run_watch(runner, host_id, listener, cut.clone()));
    CallWatch { task, cut }
}

async fn run_watch(
    runner: ChatHost,
    host_id: String,
    listener: Arc<dyn CallListener>,
    cut: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>>,
) {
    // Ids seen so far; `None` until the first sync (whose Calls are not new).
    let mut seen: Option<std::collections::HashSet<String>> = None;
    let mut backoff = Duration::from_secs(1);
    loop {
        let (tx, mut cut_rx) = tokio::sync::oneshot::channel();
        *cut.lock().expect("cut lock") = Some(tx);
        let result = async {
            let mut feed = Feed::new(runner.open(&command(&["calls", "--follow"])).await?);
            let mut calls = feed.snapshot().await?;
            backoff = Duration::from_secs(1);
            match &mut seen {
                None => seen = Some(calls.iter().map(|(_, c)| c.id.clone()).collect()),
                Some(seen) => {
                    let mut fresh: Vec<&wire::Call> = calls
                        .iter()
                        .map(|(_, c)| c)
                        .filter(|c| !seen.contains(&c.id))
                        .collect();
                    fresh.sort_by_key(|c| c.at_ms);
                    for c in fresh {
                        seen.insert(c.id.clone());
                        listener.on_new_call(CallInfo::from_wire(&host_id, c.clone()));
                    }
                }
            }
            listener.on_state(CallFeedState::Live);
            listener.on_calls(listing(&host_id, &calls));
            loop {
                let line = tokio::select! {
                    _ = &mut cut_rx => return Err(CoreError::Closed),
                    line = tokio::time::timeout(SILENCE, feed.next()) => {
                        line.map_err(|_| CoreError::Unreachable {
                            detail: "the Host stopped answering".into(),
                        })??
                    }
                };
                if let CallsLine::Call { seq, call } = line {
                    let seen = seen.as_mut().expect("synced");
                    if seen.insert(call.id.clone()) {
                        listener.on_new_call(CallInfo::from_wire(&host_id, call.clone()));
                    }
                    upsert(&mut calls, seq, call);
                    listener.on_calls(listing(&host_id, &calls));
                }
            }
            #[allow(unreachable_code)]
            Ok::<(), CoreError>(())
        }
        .await;
        let detail = match result {
            Err(e) => e.to_string(),
            Ok(()) => "the Call feed ended".into(),
        };
        listener.on_state(CallFeedState::Reconnecting { detail });
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}
