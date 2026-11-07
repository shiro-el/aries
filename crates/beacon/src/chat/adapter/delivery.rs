//! `aries-beacon answer <terminal> <question> <picks>...`: answers the
//! question an Agent shows, from the Chat (contract: `aries_protocol::answer`).
//!
//! Agent-neutral: the open question is the one the Agent's [`Interpreter`]
//! reports as a running `tool` with a `question`, and only while herdr-core
//! says the Terminal is `blocked` on a `question`. Claude first tries its
//! PermissionRequest hook and confirms the result; each Agent's TUI keys
//! ([`Agent::answer_keys`]) remain the fallback. The reserved `permission:`
//! IDs take a separate picks-only path: re-read a recognized live permission
//! menu and its status/turn before sending that menu's advertised shortcut.

use std::fs::{File, OpenOptions};
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use aries_protocol::answer::{Answer, AnswerOutcome, AnswerReply};
use aries_protocol::chat::{ChatBody, ChatQuestion, ToolStatus};

use super::claude::hook;
use super::requests::blocked_on;
use super::requests::{collect_requests, terminal_transcript, transcript};
use super::{Agent, TerminalInput, TerminalLookup};

/// Input actions stay separate: text is literal UTF-8, never key names or paste.
#[derive(Debug, Clone)]
pub enum AnswerInput {
    Keys(Vec<String>),
    Text(String),
    /// A highlighted menu whose current row is read again for each part.
    Menu {
        prompt: String,
        options: usize,
        pick: Option<usize>,
        text: Option<String>,
    },
}

pub(super) use super::KeysError;

/// Checks picks against the question, the same for every Agent: one picks
/// list per question, indexes in range and distinct, exactly one for a
/// single-choice question, at least one otherwise.
pub fn validate(question: &ChatQuestion, picks: &[Vec<usize>]) -> Result<(), KeysError> {
    if picks.len() != question.questions.len() {
        return Err(KeysError::Invalid(format!(
            "{} questions, {} answers",
            question.questions.len(),
            picks.len()
        )));
    }
    for (i, (q, p)) in question.questions.iter().zip(picks).enumerate() {
        if p.is_empty() || (!q.multi && p.len() != 1) {
            return Err(KeysError::Invalid(format!(
                "question {i} takes {}",
                if q.multi {
                    "one or more picks"
                } else {
                    "one pick"
                }
            )));
        }
        if let Some(n) = p.iter().find(|n| **n >= q.options.len()) {
            return Err(KeysError::Invalid(format!(
                "question {i} has no option {n}"
            )));
        }
        let mut sorted = p.clone();
        sorted.sort_unstable();
        sorted.dedup();
        if sorted.len() != p.len() {
            return Err(KeysError::Invalid(format!("question {i}: repeated pick")));
        }
    }
    Ok(())
}

pub(super) fn reply(outcome: AnswerOutcome, detail: impl Into<String>) -> AnswerReply {
    AnswerReply::new(outcome, Some(detail.into()))
}

/// Input is serialized across Beacon processes, including validation reads.
/// A claim is synced before the first possibly delivered input and survives a
/// process death or an RPC error. Claims contain only request identity hashes.
struct ClaimedInput<'a, L> {
    inner: &'a mut L,
    path: PathBuf,
    claimed: bool,
    proof: Proof<'a>,
    expected_screen: Option<String>,
}

impl<L> ClaimedInput<'_, L> {
    fn claim(&mut self) -> Result<(), String> {
        if !self.claimed {
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let file = options.open(&self.path).map_err(|e| {
                if e.kind() == std::io::ErrorKind::AlreadyExists {
                    "answer already attempted; delivery is uncertain".to_owned()
                } else {
                    format!("could not claim answer: {e}")
                }
            })?;
            file.sync_all().map_err(|e| e.to_string())?;
            File::open(self.path.parent().expect("claim directory"))
                .and_then(|dir| dir.sync_all())
                .map_err(|e| e.to_string())?;
            self.claimed = true;
        }
        Ok(())
    }
}

impl<L: TerminalLookup> TerminalLookup for ClaimedInput<'_, L> {
    fn get(&mut self, terminal: &str) -> Result<Option<super::TerminalFacts>, String> {
        self.inner.get(terminal)
    }
    fn screen(&mut self, terminal: &str) -> Result<String, String> {
        self.inner.screen(terminal)
    }
}

impl<L: TerminalLookup + TerminalInput> TerminalInput for ClaimedInput<'_, L> {
    fn keys(&mut self, terminal: &str, keys: &[String]) -> Result<(), String> {
        let first = !self.claimed;
        self.claim()?;
        self.verify(first)?;
        self.inner.keys(terminal, keys)
    }
    fn text(&mut self, terminal: &str, text: &str) -> Result<(), String> {
        let first = !self.claimed;
        self.claim()?;
        self.verify(first)?;
        self.inner.text(terminal, text)
    }
}

fn hash(value: impl serde::Serialize) -> String {
    Sha256::digest(serde_json::to_vec(&value).expect("answer identity"))
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn terminal_lock(dir: &Path) -> std::io::Result<File> {
    let mut builder = std::fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(dir)?;
    let mut options = OpenOptions::new();
    options.create(true).truncate(false).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let lock = options.open(dir.join("input.lock"))?;
    lock.lock()?;
    Ok(lock)
}

/// Runs one answer, without retries. Input failures can follow partial input.
pub fn answer<L: TerminalLookup + TerminalInput>(
    state_dir: &Path,
    terminal: &str,
    answer: &Answer,
    link: &mut L,
    agents: &[Box<dyn Agent>],
) -> std::io::Result<AnswerReply> {
    let dir = state_dir.join("answers").join(hash(terminal));
    let _lock = match terminal_lock(&dir) {
        Ok(lock) => lock,
        Err(e) => return Ok(reply(AnswerOutcome::Failed, format!("answer lock: {e}"))),
    };
    let facts = match link.get(terminal) {
        Ok(Some(f)) => f,
        Ok(None) => return Ok(reply(AnswerOutcome::NoTerminal, terminal)),
        Err(e) => return Ok(reply(AnswerOutcome::NoCore, e)),
    };
    let Some((agent, located)) = agents.iter().find_map(|a| a.locate(&facts).map(|l| (a, l)))
    else {
        return Ok(reply(AnswerOutcome::NoAgent, terminal));
    };

    let items = transcript(agent.as_ref(), &located)?.unwrap_or_default();
    let screen = if blocked_on(&facts, "permission") {
        link.screen(terminal).unwrap_or_default()
    } else {
        String::new()
    };
    let requests = collect_requests(
        agent.as_ref(),
        &located,
        state_dir,
        terminal,
        &facts,
        &items,
        &screen,
    );
    let Some(request) = requests
        .pending
        .iter()
        .find(|r| r.id().as_str() == answer.question)
    else {
        if let Some(item) = items.iter().find(|i| i.id == answer.question) {
            return Ok(match &item.body {
                ChatBody::Tool {
                    question: Some(_),
                    status,
                    ..
                } if *status != ToolStatus::Running => {
                    reply(AnswerOutcome::Answered, "the question has a result")
                }
                _ => reply(AnswerOutcome::Gone, "not a current question"),
            });
        }
        return Ok(reply(AnswerOutcome::Gone, "no such question"));
    };
    // Picks never enter the identity: another surface choosing a different
    // option must not dispatch a second answer to this same request.
    let mut claimed = ClaimedInput {
        inner: link,
        path: dir.join(hash((
            agent.name(),
            &located.session,
            located.path.as_os_str().as_encoded_bytes(),
            &answer.question,
        ))),
        claimed: false,
        proof: Proof {
            agent: agent.as_ref(),
            located: &located,
            facts: &facts,
            request,
            terminal,
            has_text: answer.free_text.iter().any(Option::is_some),
        },
        expected_screen: None,
    };
    let link = &mut claimed;

    let (primary, fallback) = match request.route() {
        Route::Tui(fallback) => (None, fallback),
        Route::ClaudeHook { primary, fallback } => (Some(primary), fallback),
    };
    match fallback {
        Tui::Approval(permission) => {
            let Some(keys) = permission.keys(&answer.picks, &answer.free_text) else {
                return Ok(reply(AnswerOutcome::Invalid, "invalid permission choice"));
            };
            let latest = link.get(terminal).ok().flatten();
            if latest
                .as_ref()
                .and_then(|f| agent.approval(&located.session, f, &screen))
                .is_none_or(|p| p.item.id != answer.question)
            {
                return Ok(reply(AnswerOutcome::Gone, "the permission status changed"));
            }
            if let Some(pending) = primary
                && let Some(behavior) = agent.approval_decision(permission, answer.picks[0][0])
            {
                if hook_answer(
                    state_dir,
                    terminal,
                    &located,
                    pending,
                    serde_json::json!(behavior),
                    link,
                ) {
                    return Ok(AnswerReply::new(AnswerOutcome::Sent, None));
                }
                let fresh_screen = link.screen(terminal).unwrap_or_default();
                if link
                    .get(terminal)
                    .ok()
                    .flatten()
                    .and_then(|f| agent.approval(&located.session, &f, &fresh_screen))
                    .is_none_or(|p| p.item.id != answer.question)
                {
                    return Ok(reply(AnswerOutcome::Gone, "the permission menu changed"));
                }
            }
            return Ok(match link.keys(terminal, &keys) {
                Ok(()) => AnswerReply::new(AnswerOutcome::Sent, None),
                Err(e) => reply(AnswerOutcome::Failed, e),
            });
        }
        Tui::Question => {}
    }
    let Some(question) = request.question().cloned() else {
        return Ok(reply(AnswerOutcome::Gone, "not a question"));
    };
    if !request.is_current() {
        return Ok(reply(AnswerOutcome::Gone, "another question is first"));
    }
    if !blocked_on(&facts, "question") {
        return Ok(reply(AnswerOutcome::Gone, "the Agent is not asking"));
    }
    let texts = &answer.free_text;
    let has_text = texts.iter().any(Option::is_some);
    if !texts.is_empty() && texts.len() != question.questions.len() {
        return Ok(reply(AnswerOutcome::Invalid, "answer count mismatch"));
    }
    let mut validation_picks = answer.picks.clone();
    for (i, text) in texts.iter().enumerate() {
        if let Some(text) = text {
            if text.trim().is_empty()
                || text.len() > 4096
                || text.chars().any(char::is_control)
                || answer.picks.get(i).is_none_or(|p| !p.is_empty())
            {
                return Ok(reply(AnswerOutcome::Invalid, "invalid free text"));
            }
            // Validate the remaining option parts with the existing rules.
            validation_picks[i] = vec![0];
        }
    }
    if let Err(KeysError::Invalid(detail)) = validate(&question, &validation_picks) {
        return Ok(reply(AnswerOutcome::Invalid, detail));
    }
    if let Some(pending) = primary {
        let answers: serde_json::Map<String, serde_json::Value> = question
            .questions
            .iter()
            .enumerate()
            .map(|(i, q)| {
                let value = texts
                    .get(i)
                    .and_then(Option::as_ref)
                    .cloned()
                    .unwrap_or_else(|| {
                        answer.picks[i]
                            .iter()
                            .map(|n| q.options[*n].label.clone())
                            .collect::<Vec<_>>()
                            .join(", ")
                    });
                (q.text.clone(), value.into())
            })
            .collect();
        if answers.len() == question.questions.len()
            && hook_answer(state_dir, terminal, &located, pending, answers.into(), link)
        {
            return Ok(AnswerReply::new(AnswerOutcome::Sent, None));
        }
        // Verify the original question is still pending before key fallback.
        let fresh =
            terminal_transcript(agent.as_ref(), &located, state_dir, terminal)?.unwrap_or_default();
        let asking = link
            .get(terminal)
            .ok()
            .flatten()
            .and_then(|f| f.status)
            .is_some_and(|s| s.state == "blocked" && s.kind.as_deref() == Some("question"));
        let same = agent
            .current_question(&fresh)
            .is_some_and(|(id, _)| id == answer.question);
        let still_on_screen = pending.id == answer.question
            && !hook::completed(pending)
            && agent.current_question(&fresh).is_none()
            && link.screen(terminal).is_ok_and(|screen| {
                let normalize = |s: &str| s.split_whitespace().collect::<Vec<_>>().join(" ");
                normalize(&screen).contains(&normalize(&question.questions[0].text))
            });
        if !asking || !(same || still_on_screen) {
            return Ok(reply(AnswerOutcome::Gone, "the question changed"));
        }
    }
    let actions = match agent.answer_input(&question, &answer.picks, texts) {
        Ok(actions) => actions,
        Err(KeysError::Invalid(d)) => return Ok(reply(AnswerOutcome::Invalid, d)),
        Err(KeysError::Unsupported(d)) => return Ok(reply(AnswerOutcome::Unsupported, d)),
    };
    if let Err(result) = agent.prepare(
        &mut Navigation {
            link: link.inner,
            terminal,
            allow_open: true,
        },
        &question,
        has_text,
    ) {
        return Ok(result);
    }

    for action in actions {
        let result = match action {
            AnswerInput::Keys(keys) => link
                .keys(terminal, &keys)
                .map_err(|e| reply(AnswerOutcome::Failed, e)),
            AnswerInput::Text(text) => link
                .text(terminal, &text)
                .map_err(|e| reply(AnswerOutcome::Failed, e)),
            AnswerInput::Menu {
                prompt,
                options,
                pick,
                text,
            } => menu(link, terminal, &prompt, options, pick, text.as_deref()),
        };
        if let Err(reply) = result {
            return Ok(reply);
        }
    }
    Ok(AnswerReply::new(AnswerOutcome::Sent, None))
}

fn hook_answer<L: StructuredInput>(
    state: &Path,
    terminal: &str,
    located: &super::Located,
    expected: &hook::Pending,
    answer: serde_json::Value,
    link: &mut L,
) -> bool {
    let Ok(path) = hook::socket_path(state, hook::runtime_dir().as_deref(), terminal) else {
        return false;
    };
    hook::deliver(
        &path,
        terminal,
        &located.session,
        &located.path,
        expected,
        answer,
        link,
    )
}

/// Codex's editor can retain a person's selection on any part. Re-read the
/// actual menu before each part, including after a preceding Enter advances.
fn menu<L: TerminalLookup + TerminalInput>(
    link: &mut ClaimedInput<'_, L>,
    terminal: &str,
    prompt: &str,
    options: usize,
    pick: Option<usize>,
    text: Option<&str>,
) -> Result<(), AnswerReply> {
    let normalize = |text: &str| text.split_whitespace().collect::<Vec<_>>().join(" ");
    let prompt = normalize(prompt);
    let mut screen = String::new();
    for _ in 0..20 {
        screen = link
            .screen(terminal)
            .map_err(|e| reply(AnswerOutcome::Gone, e))?;
        if normalize(&screen).contains(&prompt) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    if !normalize(&screen).contains(&prompt) {
        return Err(reply(
            AnswerOutcome::Gone,
            "the next question menu did not appear",
        ));
    }
    let selected = screen.lines().rev().find_map(|line| {
        let row = line.trim().strip_prefix(['›', '❯'])?.trim();
        row.split_once('.')?.0.parse::<usize>().ok()?.checked_sub(1)
    });
    let Some(selected) = selected.filter(|n| *n < options) else {
        return Err(reply(
            AnswerOutcome::Gone,
            "Codex selection or answer draft changed",
        ));
    };
    if text.is_some() {
        let row = format!("{}.", options + 1);
        if !screen.lines().any(|line| {
            line.contains(&row)
                && ["Other", "Type something", "None of the above"]
                    .iter()
                    .any(|label| line.contains(label))
        }) {
            return Err(reply(
                AnswerOutcome::Unsupported,
                "this question has no free-text row",
            ));
        }
    }
    let facts = link
        .get(terminal)
        .map_err(|e| reply(AnswerOutcome::NoCore, e))?;
    if !facts
        .and_then(|f| f.status)
        .is_some_and(|s| s.state == "blocked" && s.kind.as_deref() == Some("question"))
    {
        return Err(reply(AnswerOutcome::Gone, "the Agent is not asking"));
    }
    let target = pick.unwrap_or(options);
    let direction = if target < selected { "up" } else { "down" };
    let mut keys = vec![direction.to_owned(); target.abs_diff(selected)];
    keys.push("enter".into());
    link.expected_screen = Some(screen);
    link.keys(terminal, &keys)
        .map_err(|e| reply(AnswerOutcome::Failed, e))?;
    if let Some(text) = text {
        link.text(terminal, text)
            .map_err(|e| reply(AnswerOutcome::Failed, e))?;
        link.keys(terminal, &["enter".into()])
            .map_err(|e| reply(AnswerOutcome::Failed, e))?;
    }
    Ok(())
}

pub(super) enum Route {
    Tui(Tui),
    ClaudeHook {
        primary: hook::Pending,
        fallback: Tui,
    },
}

pub(super) enum Tui {
    Question,
    Approval(super::permission::Permission),
}

pub(super) struct Navigation<'a> {
    link: &'a mut dyn Terminal,
    terminal: &'a str,
    allow_open: bool,
}
trait Terminal: TerminalLookup + TerminalInput {}
impl<T: TerminalLookup + TerminalInput> Terminal for T {}
impl Navigation<'_> {
    pub(super) fn screen(&mut self) -> Result<String, String> {
        self.link.screen(self.terminal)
    }
    pub(super) fn open(&mut self, key: &str) -> Result<(), String> {
        if !self.allow_open {
            return Err("the question changed after claim".into());
        }
        self.link.keys(self.terminal, &[key.into()])
    }
}

pub(super) trait StructuredInput: TerminalLookup + TerminalInput {
    fn claim_structured(&mut self) -> Result<(), String>;
}
impl<L: TerminalLookup + TerminalInput> StructuredInput for ClaimedInput<'_, L> {
    fn claim_structured(&mut self) -> Result<(), String> {
        self.claim()?;
        self.verify(false)
    }
}

#[cfg(test)]
pub(super) use super::requests::open_question;

struct Proof<'a> {
    agent: &'a dyn Agent,
    located: &'a super::Located,
    facts: &'a super::TerminalFacts,
    request: &'a super::requests::PendingRequest,
    terminal: &'a str,
    has_text: bool,
}
impl<L: TerminalLookup + TerminalInput> ClaimedInput<'_, L> {
    fn verify(&mut self, first: bool) -> Result<(), String> {
        let proof = &self.proof;
        let facts = self
            .inner
            .get(proof.terminal)?
            .ok_or("the Terminal ended")?;
        if facts.turn != proof.facts.turn
            || facts.pid != proof.facts.pid
            || proof.agent.locate(&facts).as_ref() != Some(proof.located)
        {
            return Err("the request changed after claim".into());
        }
        let fallback = match proof.request.route() {
            Route::Tui(tui) | Route::ClaudeHook { fallback: tui, .. } => tui,
        };
        match fallback {
            Tui::Approval(_) => {
                let screen = self.inner.screen(proof.terminal)?;
                if proof
                    .agent
                    .approval(&proof.located.session, &facts, &screen)
                    .is_none_or(|p| p.item.id != proof.request.id().as_str())
                {
                    return Err("the permission menu changed after claim".into());
                }
            }
            Tui::Question => {
                if !blocked_on(&facts, "question") {
                    return Err("the Agent is not asking".into());
                }
                if first {
                    let question = proof.request.question().ok_or("not a question")?;
                    proof
                        .agent
                        .prepare(
                            &mut Navigation {
                                link: self.inner,
                                terminal: proof.terminal,
                                allow_open: false,
                            },
                            question,
                            proof.has_text,
                        )
                        .map_err(|r| r.detail.unwrap_or_else(|| "the live menu changed".into()))?;
                    if let Route::Tui(_) = proof.request.route() {
                        let items = transcript(proof.agent, proof.located)
                            .map_err(|e| e.to_string())?
                            .unwrap_or_default();
                        if proof
                            .agent
                            .current_question(&items)
                            .is_none_or(|(id, _)| id != proof.request.id().as_str())
                        {
                            return Err("the question changed after claim".into());
                        }
                    }
                }
            }
        }
        if let Some(expected) = self.expected_screen.take() {
            let normalize = |s: &str| s.split_whitespace().collect::<Vec<_>>().join(" ");
            if normalize(&self.inner.screen(proof.terminal)?) != normalize(&expected) {
                return Err("the selection changed after claim".into());
            }
        }
        Ok(())
    }
}
