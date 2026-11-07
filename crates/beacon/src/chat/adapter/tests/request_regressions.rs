use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use super::super::claude::hook;
use super::support::{
    Agent, Located, TerminalFacts, TerminalInput, TerminalLookup, answer, claude::ClaudeCode,
};
use aries_protocol::answer::{Answer, AnswerOutcome};
use serde_json::{Value, json};

struct Claude {
    located: Located,
}
fn cc() -> ClaudeCode {
    ClaudeCode {
        config_dir: "/unused".into(),
    }
}
impl Agent for Claude {
    fn name(&self) -> &'static str {
        "claude-code"
    }
    fn locate(&self, _: &TerminalFacts) -> Option<Located> {
        Some(self.located.clone())
    }
    fn interpreter(&self) -> Box<dyn super::support::Interpreter> {
        cc().interpreter()
    }
    fn hook(&self, state: &Path, terminal: &str, located: &Located) -> Option<hook::Pending> {
        cc().hook(state, terminal, located)
    }
    fn answer_keys(
        &self,
        q: &aries_protocol::chat::ChatQuestion,
        p: &[Vec<usize>],
    ) -> Result<Vec<String>, answer::KeysError> {
        cc().answer_keys(q, p)
    }
}

fn q() -> Value {
    json!({"questions":[{"question":"Proceed?","header":"Go","multiSelect":false,"options":[{"label":"Yes","description":"y"},{"label":"No","description":"n"}]}]})
}
fn line(v: Value) -> String {
    format!("{v}\n")
}
fn user(uuid: &str, parent: Option<&str>, text: &str) -> String {
    line(
        json!({"type":"user","uuid":uuid,"parentUuid":parent,"message":{"role":"user","content":text}}),
    )
}
fn ask(uuid: &str, parent: &str, tool: &str, input: Value) -> String {
    line(
        json!({"type":"assistant","uuid":uuid,"parentUuid":parent,"message":{"role":"assistant","content":[{"type":"tool_use","id":tool,"name":"AskUserQuestion","input":input}]}}),
    )
}
fn append(path: &Path, text: &str) {
    use std::io::Write;
    std::fs::OpenOptions::new()
        .append(true)
        .open(path)
        .unwrap()
        .write_all(text.as_bytes())
        .unwrap();
}

#[derive(Default)]
struct Link {
    keys: Vec<String>,
    working: Arc<AtomicBool>,
    gets: Arc<AtomicUsize>,
    fail_get: Option<usize>,
    /// 0 = absent (older cores), otherwise the Host turn.
    turn: Arc<AtomicUsize>,
}
impl TerminalLookup for Link {
    fn screen(&mut self, _: &str) -> Result<String, String> {
        Ok("Proceed?\n1. Yes\n2. No".into())
    }
    fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
        let n = self.gets.fetch_add(1, Ordering::SeqCst) + 1;
        if self.fail_get == Some(n) {
            return Err("core hiccup".into());
        }
        let state = if self.working.load(Ordering::SeqCst) {
            json!({"state":"working"})
        } else {
            json!({"state":"blocked","kind":"question"})
        };
        let turn = match self.turn.load(Ordering::SeqCst) {
            0 => json!(null),
            n => json!(n),
        };
        Ok(Some(TerminalFacts::from_json(
            &json!({"turn": turn, "status": state}),
        )))
    }
}
impl TerminalInput for Link {
    fn keys(&mut self, _: &str, keys: &[String]) -> Result<(), String> {
        self.keys.extend_from_slice(keys);
        Ok(())
    }
}

fn submit(
    state: &Path,
    terminal: &str,
    id: &str,
    picks: Vec<Vec<usize>>,
    link: &mut Link,
    agents: &[Box<dyn Agent>],
) -> aries_protocol::answer::AnswerReply {
    answer::answer(
        state,
        terminal,
        &Answer {
            question: id.into(),
            picks,
            free_text: vec![],
        },
        link,
        agents,
    )
    .unwrap()
}

fn setup() -> (
    tempfile::TempDir,
    std::path::PathBuf,
    String,
    Vec<Box<dyn Agent>>,
) {
    assert!(
        hook::runtime_dir().is_none(),
        "run with XDG_RUNTIME_DIR unset"
    );
    let dir = tempfile::tempdir().unwrap();
    let transcript = dir.path().join("transcript.jsonl");
    std::fs::write(&transcript, "").unwrap();
    let terminal = format!("t-{:08x}", rand::random::<u32>());
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(Claude {
        located: Located {
            session: "session".into(),
            path: transcript.clone(),
        },
    })];
    (dir, transcript, terminal, agents)
}

#[test]
fn a_core_hiccup_after_claim_leaves_the_question_answerable() {
    let (dir, transcript, terminal, agents) = setup();
    append(&transcript, &user("u1", None, "ask"));
    append(&transcript, &ask("m1", "u1", "tool1", q()));
    let mut link = Link {
        fail_get: Some(2),
        ..Link::default()
    };
    link.turn.store(7, Ordering::SeqCst);
    let first = submit(
        dir.path(),
        &terminal,
        "tool1",
        vec![vec![0]],
        &mut link,
        &agents,
    );
    assert_eq!(first.outcome, AnswerOutcome::Failed, "{first:?}");
    assert_eq!(first.detail.as_deref(), Some("core hiccup"));
    assert!(link.keys.is_empty());
    let retry = submit(
        dir.path(),
        &terminal,
        "tool1",
        vec![vec![0]],
        &mut link,
        &agents,
    );
    assert_eq!(retry.outcome, AnswerOutcome::Sent, "{retry:?}");
    assert_eq!(link.keys, ["1"]);
}

#[test]
fn reanswers_validate_bad_picks_and_nonasking_status_before_claims() {
    let (dir, transcript, terminal, agents) = setup();
    append(&transcript, &user("u1", None, "ask"));
    append(&transcript, &ask("m1", "u1", "tool1", q()));
    let mut link = Link::default();
    link.turn.store(7, Ordering::SeqCst);
    let first = submit(
        dir.path(),
        &terminal,
        "tool1",
        vec![vec![0]],
        &mut link,
        &agents,
    );
    assert_eq!(first.outcome, AnswerOutcome::Sent, "{first:?}");
    let invalid = submit(
        dir.path(),
        &terminal,
        "tool1",
        vec![vec![5]],
        &mut link,
        &agents,
    );
    link.working.store(true, Ordering::SeqCst);
    let not_asking = submit(
        dir.path(),
        &terminal,
        "tool1",
        vec![vec![1]],
        &mut link,
        &agents,
    );
    eprintln!(
        "REVIEW invalid-picks={invalid:?} not-asking={not_asking:?} keys={:?}",
        link.keys
    );
    assert_eq!(invalid.outcome, AnswerOutcome::Invalid, "{invalid:?}");
    assert_eq!(not_asking.outcome, AnswerOutcome::Gone, "{not_asking:?}");
}
