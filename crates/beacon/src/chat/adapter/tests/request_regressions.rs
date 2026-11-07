use std::io::{BufRead, BufReader};
use std::os::unix::net::UnixListener;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use super::super::claude::hook::{self, Request};
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
        runtime_dir: None,
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

#[test]
fn an_older_turns_running_question_does_not_remove_push_buttons() {
    let (dir, transcript, terminal, agents) = setup();
    append(&transcript, &user("u1", None, "first"));
    append(&transcript, &ask("m1", "u1", "tool0", q()));
    append(&transcript, &user("u2", Some("m1"), "second"));
    let other = json!({"questions":[{"question":"Color?","header":"C","multiSelect":false,"options":[{"label":"Red","description":"r"},{"label":"Blue","description":"b"}]}]});
    append(&transcript, &ask("m2", "u2", "tool1", other));
    let mut link = Link::default();
    link.turn.store(7, Ordering::SeqCst);
    let old = submit(
        dir.path(),
        &terminal,
        "tool0",
        vec![vec![0]],
        &mut link,
        &agents,
    );
    assert_eq!(old.outcome, AnswerOutcome::Gone, "{old:?}");
    assert!(link.keys.is_empty());
    let adapters = super::super::Adapters {
        state: dir.path().to_owned(),
        agents,
    };
    let view = adapters.observe(&terminal, &mut link).unwrap();
    let quick = view.quick_reply().unwrap();
    assert_eq!(quick.id().as_str(), "tool1");
    assert_eq!(quick.options(), ["Red", "Blue"]);
}

fn hook_pending(transcript: &Path, id: &str, nonce: &str) -> hook::Pending {
    hook::Pending {
        request: Request {
            session_id: "session".into(),
            transcript_path: transcript.into(),
            cwd: "/work".into(),
            permission_mode: "default".into(),
            hook_event_name: "PermissionRequest".into(),
            tool_name: "AskUserQuestion".into(),
            tool_input: q(),
        },
        id: id.into(),
        transcript_offset: std::fs::metadata(transcript).unwrap().len(),
        created_at: 0,
        nonce: nonce.into(),
    }
}

/// A hook socket that accepts one decision, flips status to working (the
/// confirmation main also relies on) and removes its files like `wait` does.
fn serve_hook(
    sock: &Path,
    pending: &hook::Pending,
    working: Arc<AtomicBool>,
) -> std::thread::JoinHandle<Value> {
    std::fs::create_dir_all(sock.parent().unwrap()).unwrap();
    let listener = UnixListener::bind(sock).unwrap();
    std::fs::write(
        sock.with_extension("json"),
        serde_json::to_vec(pending).unwrap(),
    )
    .unwrap();
    let sock = sock.to_owned();
    std::thread::spawn(move || {
        let (s, _) = listener.accept().unwrap();
        let mut l = String::new();
        BufReader::new(s).read_line(&mut l).unwrap();
        working.store(true, Ordering::SeqCst);
        std::fs::remove_file(&sock).unwrap();
        std::fs::remove_file(sock.with_extension("json")).unwrap();
        serde_json::from_str(&l).unwrap()
    })
}

fn result(uuid: &str, parent: &str, tool: &str) -> String {
    line(
        json!({"type":"user","uuid":uuid,"parentUuid":parent,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":tool,"content":"answered"}]}}),
    )
}

#[test]
fn a_hook_claim_still_refuses_its_late_call_after_a_herdr_turn_change() {
    let (dir, transcript, terminal, agents) = setup();
    let sock = hook::socket_path(dir.path(), None, &terminal).unwrap();
    let h = hook_pending(&transcript, "claude-hook:0123456789abcdef", "test");
    let mut link = Link::default();
    link.turn.store(3, Ordering::SeqCst);
    std::fs::create_dir_all(sock.parent().unwrap()).unwrap();
    let listener = UnixListener::bind(&sock).unwrap();
    std::fs::write(sock.with_extension("json"), serde_json::to_vec(&h).unwrap()).unwrap();
    let s2 = sock.clone();
    let w = std::thread::spawn(move || {
        let (s, _) = listener.accept().unwrap();
        let mut l = String::new();
        BufReader::new(s).read_line(&mut l).unwrap();
        std::fs::remove_file(&s2).unwrap();
        std::fs::remove_file(s2.with_extension("json")).unwrap();
    });
    let first = submit(
        dir.path(),
        &terminal,
        &h.id,
        vec![vec![0]],
        &mut link,
        &agents,
    );
    w.join().unwrap();
    assert_eq!(first.outcome, AnswerOutcome::Sent, "{first:?}");
    assert_eq!(link.keys, ["1"]);
    append(&transcript, &user("late-user", None, "Ask me"));
    append(&transcript, &ask("m1", "late-user", "tool1", q()));
    link.turn.store(4, Ordering::SeqCst);
    let second = submit(
        dir.path(),
        &terminal,
        "tool1",
        vec![vec![1]],
        &mut link,
        &agents,
    );
    assert_eq!(second.outcome, AnswerOutcome::Failed, "{second:?}");
    assert!(
        second
            .detail
            .as_deref()
            .unwrap()
            .contains("already attempted")
    );
    assert_eq!(link.keys, ["1"]);
}

#[test]
fn repeated_identical_questions_in_one_transcript_turn_remain_answerable() {
    for (first_turn, second_turn) in [(0, 0), (7, 7), (2, 4)] {
        for correlated in [true, false] {
            for second_hook in [false, true] {
                let (dir, transcript, terminal, agents) = setup();
                let sock = hook::socket_path(dir.path(), None, &terminal).unwrap();
                append(&transcript, &user("u1", None, "Ask me twice"));
                if correlated {
                    append(&transcript, &ask("m1", "u1", "tool1", q()));
                }
                let h1 = hook_pending(&transcript, "claude-hook:1111111111111111", "n1");
                let mut link = Link::default();
                link.turn.store(first_turn, Ordering::SeqCst);
                let worker = serve_hook(&sock, &h1, link.working.clone());
                let first = submit(
                    dir.path(),
                    &terminal,
                    &h1.id,
                    vec![vec![0]],
                    &mut link,
                    &agents,
                );
                worker.join().unwrap();
                assert_eq!(first.outcome, AnswerOutcome::Sent, "{first:?}");
                if !correlated {
                    append(&transcript, &ask("m1", "u1", "tool1", q()));
                }
                append(&transcript, &result("r1", "m1", "tool1"));
                append(&transcript, &ask("m2", "r1", "tool2", q()));
                link.working.store(false, Ordering::SeqCst);
                link.turn.store(second_turn, Ordering::SeqCst);
                let h2 = hook_pending(&transcript, "claude-hook:2222222222222222", "n2");
                let worker = second_hook.then(|| serve_hook(&sock, &h2, link.working.clone()));
                let id = if second_hook { &h2.id } else { "tool2" };
                let second = submit(dir.path(), &terminal, id, vec![vec![1]], &mut link, &agents);
                if let Some(worker) = worker {
                    if second.outcome != AnswerOutcome::Sent {
                        let mut stream = std::os::unix::net::UnixStream::connect(&sock).unwrap();
                        std::io::Write::write_all(&mut stream, b"{}\n").unwrap();
                    }
                    worker.join().unwrap();
                }
                assert_eq!(
                    second.outcome,
                    AnswerOutcome::Sent,
                    "turn {first_turn}->{second_turn}, correlated={correlated}, second_hook={second_hook}: {second:?}"
                );
                if second_hook {
                    assert!(link.keys.is_empty());
                } else {
                    assert_eq!(link.keys, ["2"]);
                }
            }
        }
    }
}

#[test]
fn corrupt_claim_contents_do_not_block_another_request() {
    for kind in ["empty", "truncated", "future-version"] {
        let (dir, transcript, terminal, agents) = setup();
        append(&transcript, &user("u1", None, "ask"));
        append(&transcript, &ask("m1", "u1", "tool1", q()));
        let mut link = Link::default();
        let facts = link.get(&terminal).unwrap().unwrap();
        let located = agents[0].locate(&facts).unwrap();
        let items = super::super::requests::transcript(agents[0].as_ref(), &located)
            .unwrap()
            .unwrap();
        let requests = super::super::requests::collect_requests(
            agents[0].as_ref(),
            &located,
            dir.path(),
            &terminal,
            &facts,
            &items,
            "",
        );
        let mut identity = serde_json::to_value(requests.pending[0].identity()).unwrap();
        identity["version"] = json!(2);
        let contents = match kind {
            "empty" => vec![],
            "truncated" => b"{\"version\":1,".to_vec(),
            _ => serde_json::to_vec(&identity).unwrap(),
        };
        let claims = dir
            .path()
            .join("answers")
            .join(super::super::claims::hash(&terminal));
        std::fs::create_dir_all(&claims).unwrap();
        let foreign = claims.join("another-request");
        std::fs::write(&foreign, &contents).unwrap();
        let reply = submit(
            dir.path(),
            &terminal,
            "tool1",
            vec![vec![0]],
            &mut link,
            &agents,
        );
        assert_eq!(reply.outcome, AnswerOutcome::Sent, "{kind}: {reply:?}");
        assert_eq!(link.keys, ["1"]);
        assert_eq!(std::fs::read(&foreign).unwrap(), contents);
        let alias = super::super::claims::hash((
            agents[0].name(),
            &located.session,
            located.path.as_os_str().as_encoded_bytes(),
            "tool1",
        ));
        std::fs::write(claims.join(alias), &contents).unwrap();
        let again = submit(
            dir.path(),
            &terminal,
            "tool1",
            vec![vec![1]],
            &mut link,
            &agents,
        );
        assert_eq!(again.outcome, AnswerOutcome::Failed, "{kind}: {again:?}");
        assert!(
            again
                .detail
                .as_deref()
                .unwrap()
                .contains("already attempted")
        );
        assert_eq!(link.keys, ["1"]);
    }
}
