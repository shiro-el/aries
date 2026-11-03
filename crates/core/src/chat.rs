//! A Terminal's Agent Chat, read through the Host's Beacon.
//!
//! The core runs `aries-beacon chat <terminal> --follow` on the Host (an SSH
//! exec channel; a local shell for Local endpoints) and applies its
//! Agent-neutral lines (`aries_protocol::chat`) to a list of entries. When
//! the stream breaks it reconnects with the last cursor, so nothing is lost
//! or shown twice. The app never sees a transcript format.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use aries_protocol::chat::{
    ChatBody, ChatEvent, ChatItem, ChatLine, ChatLineError, ChatState, ToolStatus,
};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::{Mutex, watch};
use tokio::task::JoinHandle;

use aries_protocol::answer::{Answer, AnswerOutcome, AnswerReply};

use crate::error::{AnswerProblem, CoreError};
use crate::transport::{Ssh, Stream};

/// One Chat entry, ready to render.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatEntry {
    /// Stable while the transcript stays the same; use it as the list key.
    pub id: String,
    /// RFC 3339 time the Agent recorded, when it did.
    pub timestamp: Option<String>,
    pub body: ChatEntryBody,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ChatEntryBody {
    User {
        text: String,
    },
    Agent {
        text: String,
    },
    /// `text` is `None` when the Agent does not record what it thought.
    Thinking {
        text: Option<String>,
    },
    /// Shown collapsed as `name` + `title`; expanded shows `input` (pretty
    /// JSON) and `output`.
    Tool {
        name: String,
        title: String,
        input: String,
        status: ChatToolStatus,
        output: Option<String>,
    },
    AgentError {
        detail: String,
    },
    /// The Agent asks the user to choose. While `status` is `Running` it is
    /// open: show `questions` with their options as buttons and answer
    /// with [`crate::AriesCore::answer_question`] (this entry's id).
    /// `output` is the Agent's record of the answer once there is one.
    Question {
        name: String,
        title: String,
        questions: Vec<ChatQuestionPart>,
        status: ChatToolStatus,
        output: Option<String>,
    },
    /// Something a newer Beacon sends that this app cannot show yet.
    Unsupported,
}

/// One question of a [`ChatEntryBody::Question`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatQuestionPart {
    pub text: String,
    pub header: Option<String>,
    /// Several options may be picked.
    pub multi: bool,
    pub options: Vec<ChatQuestionOption>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatQuestionOption {
    pub label: String,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChatToolStatus {
    Running,
    Done,
    Failed,
}

/// Where the Chat stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChatStatus {
    Connecting,
    /// Caught up and following.
    Live,
    /// The Agent runs but has not written anything yet.
    Waiting,
    /// No supported Agent in this Terminal (yet).
    NoAgent,
    /// The Agent exited; the entries are its last Chat.
    Ended,
    /// The connection broke; reconnecting.
    Reconnecting,
    /// The Terminal is gone. Final.
    NoTerminal,
    /// The Host has no Beacon. Final.
    NoBeacon,
    /// The Beacon cannot reach the Host's herdr-core; retrying.
    NoCore,
    /// The Beacon speaks a newer Chat format: update Aries. Final.
    Incompatible,
}

impl ChatStatus {
    fn is_final(self) -> bool {
        matches!(self, Self::NoTerminal | Self::NoBeacon | Self::Incompatible)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatSnapshot {
    pub entries: Vec<ChatEntry>,
    pub status: ChatStatus,
    /// Resume point of what `entries` covers.
    pub cursor: Option<String>,
}

/// Applies Chat lines to entries (upsert by id; `reset` clears).
#[derive(Debug, Clone)]
pub(crate) struct ChatModel {
    entries: Vec<ChatEntry>,
    index: HashMap<String, usize>,
    pub status: ChatStatus,
    pub cursor: Option<String>,
}

impl ChatModel {
    pub fn new(cursor: Option<String>) -> Self {
        Self {
            entries: Vec::new(),
            index: HashMap::new(),
            status: ChatStatus::Connecting,
            cursor,
        }
    }

    pub fn snapshot(&self) -> ChatSnapshot {
        ChatSnapshot {
            entries: self.entries.clone(),
            status: self.status,
            cursor: self.cursor.clone(),
        }
    }

    /// Applies one line of Beacon output. Lines that are not Chat lines
    /// (login noise) are skipped.
    pub fn apply_text(&mut self, text: &str) {
        match ChatLine::parse(text) {
            Ok(line) => self.apply(line),
            Err(ChatLineError::NewerFormat(_)) => self.status = ChatStatus::Incompatible,
            Err(ChatLineError::Malformed(_)) => {}
        }
    }

    pub fn apply(&mut self, line: ChatLine) {
        match line.event {
            ChatEvent::Reset { .. } => {
                self.entries.clear();
                self.index.clear();
            }
            ChatEvent::Item { item } => {
                let entry = entry(item);
                match self.index.get(&entry.id) {
                    Some(&i) => self.entries[i] = entry,
                    None => {
                        self.index.insert(entry.id.clone(), self.entries.len());
                        self.entries.push(entry);
                    }
                }
            }
            ChatEvent::Remove { id } => {
                if self.index.remove(&id).is_some() {
                    self.entries.retain(|e| e.id != id);
                    self.index = (self.entries.iter().enumerate())
                        .map(|(i, e)| (e.id.clone(), i))
                        .collect();
                }
            }
            ChatEvent::State { state } => {
                if let Some(s) = status(state) {
                    self.status = s;
                }
            }
            ChatEvent::Heartbeat | ChatEvent::Unknown => {}
        }
        if let Some(c) = line.cursor {
            self.cursor = Some(c);
        }
    }
}

fn status(state: ChatState) -> Option<ChatStatus> {
    Some(match state {
        ChatState::Live => ChatStatus::Live,
        ChatState::Waiting => ChatStatus::Waiting,
        ChatState::NoAgent => ChatStatus::NoAgent,
        ChatState::Ended => ChatStatus::Ended,
        ChatState::NoTerminal => ChatStatus::NoTerminal,
        ChatState::NoCore => ChatStatus::NoCore,
        ChatState::NoBeacon => ChatStatus::NoBeacon,
        ChatState::Unknown => return None,
    })
}

fn entry(item: ChatItem) -> ChatEntry {
    let body = match item.body {
        ChatBody::User { text } => ChatEntryBody::User { text },
        ChatBody::Agent { text } => ChatEntryBody::Agent { text },
        ChatBody::Thinking { text } => ChatEntryBody::Thinking { text },
        ChatBody::Tool {
            name,
            title,
            status,
            output,
            question: Some(q),
            ..
        } => ChatEntryBody::Question {
            name,
            title,
            questions: q
                .questions
                .into_iter()
                .map(|q| ChatQuestionPart {
                    text: q.text,
                    header: q.header,
                    multi: q.multi,
                    options: (q.options.into_iter())
                        .map(|o| ChatQuestionOption {
                            label: o.label,
                            description: o.description,
                        })
                        .collect(),
                })
                .collect(),
            status: tool_status(status),
            output,
        },
        ChatBody::Tool {
            name,
            title,
            input,
            status,
            output,
            question: None,
        } => ChatEntryBody::Tool {
            name,
            title,
            input: match input {
                serde_json::Value::Null => String::new(),
                serde_json::Value::String(s) => s,
                v => serde_json::to_string_pretty(&v).unwrap_or_default(),
            },
            status: tool_status(status),
            output,
        },
        ChatBody::Error { message } => ChatEntryBody::AgentError { detail: message },
        ChatBody::Unsupported => ChatEntryBody::Unsupported,
    };
    ChatEntry {
        id: item.id,
        timestamp: item.ts,
        body,
    }
}

fn tool_status(status: ToolStatus) -> ChatToolStatus {
    match status {
        ToolStatus::Running | ToolStatus::Unknown => ChatToolStatus::Running,
        ToolStatus::Done => ChatToolStatus::Done,
        ToolStatus::Error => ChatToolStatus::Failed,
    }
}

/// How to run a command on the Host for the Chat.
pub(crate) enum ChatHost {
    Ssh(Box<Ssh>),
    /// Runs `sh -c` locally with these variables (Local endpoints).
    Local(HashMap<String, String>),
}

/// Environment a local run must not inherit from the app's own process
/// unless the endpoint sets it.
const LEAKY_ENV: &[&str] = &[
    "HC_SOCKET",
    "HC_SCOPE",
    "HC_TERMINAL_ID",
    "HC_TERMINAL_SOCKET",
    "CLAUDECODE",
    "CLAUDE_CODE_CHILD_SESSION",
];

impl ChatHost {
    async fn open(&self, command: &str) -> Result<Stream, CoreError> {
        match self {
            Self::Ssh(ssh) => ssh.exec_stream(command).await,
            Self::Local(env) => {
                let mut cmd = tokio::process::Command::new("/bin/sh");
                cmd.arg("-c").arg(command);
                for k in LEAKY_ENV {
                    if !env.contains_key(*k) {
                        cmd.env_remove(k);
                    }
                }
                let mut child = cmd
                    .envs(env)
                    .stdin(std::process::Stdio::piped())
                    .stdout(std::process::Stdio::piped())
                    .stderr(std::process::Stdio::null())
                    .kill_on_drop(true)
                    .spawn()
                    .map_err(|e| CoreError::Unreachable {
                        detail: format!("sh: {e}"),
                    })?;
                let stdin = child.stdin.take().expect("piped stdin");
                let stdout = child.stdout.take().expect("piped stdout");
                Ok(Stream {
                    reader: Box::new(stdout),
                    writer: Box::new(stdin),
                    guard: Box::new(std::sync::Mutex::new(child)),
                })
            }
        }
    }
}

fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

/// The Host-side command. `ARIES_BEACON` overrides the installed path (for
/// development); without a Beacon it prints a `no_beacon` state line.
pub(crate) fn command(terminal: &str, cursor: Option<&str>) -> String {
    let no_beacon = ChatLine::new(ChatEvent::State {
        state: ChatState::NoBeacon,
    })
    .to_json();
    let cursor = cursor
        .map(|c| format!(" --cursor {}", quote(c)))
        .unwrap_or_default();
    let script = format!(
        r#"b="${{ARIES_BEACON:-$HOME/.local/bin/aries-beacon}}"; if [ -x "$b" ]; then exec "$b" chat {} --follow{cursor}; else printf '%s\n' {}; fi"#,
        quote(terminal),
        quote(&no_beacon),
    );
    format!("sh -c {}", quote(&script))
}

/// The Host-side `aries-beacon answer` command; without a Beacon it prints
/// a `no_beacon` reply.
pub(crate) fn answer_command(terminal: &str, answer: &Answer) -> String {
    let no_beacon = AnswerReply::new(AnswerOutcome::NoBeacon, None).to_json();
    let args: Vec<String> = answer.args().iter().map(|a| quote(a)).collect();
    let script = format!(
        r#"b="${{ARIES_BEACON:-$HOME/.local/bin/aries-beacon}}"; if [ -x "$b" ]; then exec "$b" answer {} {}; else printf '%s\n' {}; fi"#,
        quote(terminal),
        args.join(" "),
        quote(&no_beacon),
    );
    format!("sh -c {}", quote(&script))
}

/// Runs `aries-beacon answer` on the Host once. Not retried: a key press
/// that may have landed must not land twice.
pub(crate) async fn answer(
    host: &ChatHost,
    terminal: &str,
    answer: &Answer,
) -> Result<(), CoreError> {
    let Stream {
        reader,
        writer,
        guard,
    } = host.open(&answer_command(terminal, answer)).await?;
    let mut lines = BufReader::new(reader).lines();
    let mut reply = None;
    let read = tokio::time::timeout(Duration::from_secs(30), async {
        while let Ok(Some(text)) = lines.next_line().await {
            if let Ok(r) = AnswerReply::parse(&text) {
                reply = Some(r);
            }
        }
    })
    .await;
    drop((writer, guard));
    let refused = |reason, detail: Option<String>| CoreError::AnswerRefused {
        reason,
        detail: detail.unwrap_or_default(),
    };
    let Some(reply) = reply else {
        let why = if read.is_err() {
            "timed out"
        } else {
            "no reply"
        };
        return Err(refused(AnswerProblem::Failed, Some(why.into())));
    };
    let reason = match reply.outcome {
        AnswerOutcome::Sent => return Ok(()),
        AnswerOutcome::Answered => AnswerProblem::Answered,
        AnswerOutcome::Gone | AnswerOutcome::NoAgent | AnswerOutcome::NoTerminal => {
            AnswerProblem::Gone
        }
        AnswerOutcome::Invalid => AnswerProblem::Invalid,
        AnswerOutcome::Unsupported => AnswerProblem::Unsupported,
        AnswerOutcome::NoBeacon => AnswerProblem::NoBeacon,
        AnswerOutcome::NoCore => AnswerProblem::NoCore,
        AnswerOutcome::Failed | AnswerOutcome::Unknown => AnswerProblem::Failed,
    };
    Err(refused(reason, reply.detail))
}

/// Silence after which the stream counts as dead (the Beacon sends a
/// heartbeat every 15 s).
const SILENCE: Duration = Duration::from_secs(45);

/// A running Chat for one Terminal.
#[derive(uniffi::Object)]
pub struct ChatWatch {
    rx: Arc<Mutex<watch::Receiver<Option<ChatSnapshot>>>>,
    task: JoinHandle<()>,
}

impl ChatWatch {
    pub(crate) fn start(
        host: ChatHost,
        terminal: String,
        cursor: Option<String>,
        retry: Duration,
    ) -> Self {
        let model = ChatModel::new(cursor);
        let (tx, rx) = watch::channel(Some(model.snapshot()));
        let task = crate::runtime().spawn(follow(host, terminal, model, tx, retry));
        Self {
            rx: Arc::new(Mutex::new(rx)),
            task,
        }
    }
}

async fn follow(
    host: ChatHost,
    terminal: String,
    mut model: ChatModel,
    tx: watch::Sender<Option<ChatSnapshot>>,
    retry: Duration,
) {
    let mut backoff = retry;
    loop {
        let command = command(&terminal, model.cursor.as_deref());
        if let Ok(stream) = host.open(&command).await {
            // The writer and guard stay alive while reading; dropping them
            // ends the Host command.
            let Stream {
                reader,
                writer,
                guard,
            } = stream;
            let mut lines = BufReader::new(reader).lines();
            loop {
                let next = tokio::time::timeout(SILENCE, lines.next_line()).await;
                let Ok(Ok(Some(text))) = next else { break };
                model.apply_text(&text);
                backoff = retry;
                // Publish once the burst is read, not for every line.
                let burst_read = lines.get_ref().buffer().is_empty();
                if (burst_read || model.status.is_final())
                    && tx.send(Some(model.snapshot())).is_err()
                {
                    return;
                }
                if model.status.is_final() {
                    return;
                }
            }
            drop((writer, guard));
        }
        if model.status.is_final() {
            return;
        }
        model.status = ChatStatus::Reconnecting;
        if tx.send(Some(model.snapshot())).is_err() {
            return;
        }
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(Duration::from_secs(30));
    }
}

#[uniffi::export]
impl ChatWatch {
    /// The Chat: right away on the first call, then each time it changes
    /// (intermediate states may be skipped). After a final status
    /// (`NoTerminal`, `NoBeacon`, `Incompatible`) or `stop`, fails with
    /// [`CoreError::Closed`].
    pub async fn next(&self) -> Result<ChatSnapshot, CoreError> {
        let rx = self.rx.clone();
        crate::on_runtime(async move {
            let mut rx = rx.lock().await;
            rx.changed().await.map_err(|_| CoreError::Closed)?;
            rx.borrow_and_update().clone().ok_or(CoreError::Closed)
        })
        .await
    }

    /// Stops following; the Beacon on the Host exits.
    pub fn stop(&self) {
        self.task.abort();
    }
}

impl Drop for ChatWatch {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_command_quotes_terminal_and_cursor() {
        let c = command("it's", Some("claude-code:s:1"));
        assert!(c.starts_with("sh -c '"), "{c}");
        let script = std::process::Command::new("/bin/sh")
            .arg("-c")
            .arg(format!("export ARIES_BEACON=/bin/echo; {c}"))
            .output()
            .unwrap();
        assert_eq!(
            String::from_utf8(script.stdout).unwrap(),
            "chat it's --follow --cursor claude-code:s:1\n"
        );
    }

    #[test]
    fn the_answer_command_quotes_terminal_and_question() {
        let answer = Answer {
            question: "it's".into(),
            picks: vec![vec![1], vec![0, 2]],
        };
        let c = answer_command("t'1", &answer);
        let out = std::process::Command::new("/bin/sh")
            .arg("-c")
            .arg(format!("export ARIES_BEACON=/bin/echo; {c}"))
            .output()
            .unwrap();
        assert_eq!(
            String::from_utf8(out.stdout).unwrap(),
            "answer t'1 it's 1 0,2\n"
        );
    }

    #[test]
    fn without_a_beacon_the_command_says_so() {
        let c = command("t1", None);
        let out = std::process::Command::new("/bin/sh")
            .arg("-c")
            .arg(format!("export ARIES_BEACON=/nonexistent; {c}"))
            .output()
            .unwrap();
        let mut model = ChatModel::new(None);
        model.apply_text(String::from_utf8(out.stdout).unwrap().trim());
        assert_eq!(model.status, ChatStatus::NoBeacon);
    }
}
