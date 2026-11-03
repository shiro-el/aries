//! Answering an Agent's question from the Chat: `watch_chat` shows the
//! question with its options, `answer_question` presses the Agent's keys
//! through the real Beacon, a fake Claude Code records what reached it.

mod common;

use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use aries_core::{
    AnswerProblem, AriesCore, ChatEntryBody, ChatSnapshot, ChatToolStatus, ChatWatch, CoreError,
    Endpoint,
};
use aries_test_support::{FakeAgent, HcSession};

const COLOR: &str = "toolu_012hzhteG39obz5TwRGEb26o";

fn path(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

struct Env {
    dir: tempfile::TempDir,
    core: Arc<AriesCore>,
}

impl Env {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let core = common::core_in(&dir.path().join("data"));
        Self { dir, core }
    }

    fn host(&self, env: HashMap<String, String>) -> String {
        self.core
            .add_host("h".into(), Endpoint::Local { argv: vec![], env })
            .unwrap()
            .id
    }
}

async fn wait_for(watch: &ChatWatch, what: &str, ok: impl Fn(&ChatSnapshot) -> bool) {
    let mut last = None;
    let found = tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            match watch.next().await {
                Ok(s) if ok(&s) => return,
                Ok(s) => last = Some(s),
                Err(e) => panic!("{what}: {e:?}"),
            }
        }
    })
    .await;
    assert!(
        found.is_ok(),
        "timed out waiting for {what}; last {last:#?}"
    );
}

fn question(s: &ChatSnapshot) -> Option<(&str, ChatToolStatus, Vec<String>)> {
    s.entries.iter().find_map(|e| match &e.body {
        ChatEntryBody::Question {
            status, questions, ..
        } => Some((
            e.id.as_str(),
            *status,
            questions[0]
                .options
                .iter()
                .map(|o| o.label.clone())
                .collect(),
        )),
        _ => None,
    })
}

async fn inputs(agent: &FakeAgent, n: usize) -> Vec<String> {
    for _ in 0..200 {
        let lines = agent.inputs();
        if lines.len() >= n {
            return lines;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("input so far: {:?}", agent.inputs());
}

#[tokio::test]
async fn a_claude_code_question_is_shown_with_options_and_answered_once() {
    answer_once(false).await;
}

#[tokio::test]
async fn a_notification_answers_from_a_fresh_core_and_refuses_an_already_answered_question() {
    answer_once(true).await;
}

async fn answer_once(background: bool) {
    let env = Env::new();
    let hc = HcSession::start();
    let config = env.dir.path().join("claude");
    let projects = config.join("projects/-work-a");
    std::fs::create_dir_all(&projects).unwrap();
    let t = path(&projects.join("S1.jsonl"));
    let agent = hc.run_fake_agent(
        "claude",
        &format!(
            "claude-session {} S1 /work/a\n\
             lines claude-code/main-session.jsonl 1 65 {t}\n\
             status blocked question answer: Which color do you prefer?\n\
             keys 1\n\
             lines claude-code/main-session.jsonl 66 125 {t}\n\
             status working\n",
            config.display(),
        ),
    );
    let bin_dir = hc.bin().parent().unwrap().to_path_buf();
    let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
    vars.insert(
        "ARIES_BEACON".into(),
        path(&aries_test_support::beacon_bin()),
    );
    vars.insert("CLAUDE_CONFIG_DIR".into(), path(&config));
    vars.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", bin_dir.display()),
    );
    let host = env.host(vars);

    let watch = env
        .core
        .watch_chat_retrying(
            host.clone(),
            agent.address.clone(),
            None,
            Duration::from_millis(50),
        )
        .unwrap();
    wait_for(&watch, "open question", |s| {
        question(s).is_some_and(|(id, status, labels)| {
            id == COLOR && status == ChatToolStatus::Running && labels == ["Red", "Blue", "Green"]
        })
    })
    .await;
    // The status reaches herdr-core right after the transcript lines.
    for _ in 0..100 {
        if hc
            .hc(&["get", &agent.address, "--json"])
            .contains("\"blocked\"")
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // A background action can start after the app process died: it has no
    // HostConnection or ChatWatch and loads everything from the saved Host.
    let core = common::core_in(&env.dir.path().join("data"));
    if background {
        let stale = core
            .answer_question_background(
                host.clone(),
                agent.address.clone(),
                "older-question".into(),
                vec![vec![0]],
            )
            .await
            .unwrap_err();
        assert!(
            matches!(
                stale,
                CoreError::AnswerRefused {
                    reason: AnswerProblem::Gone,
                    ..
                }
            ),
            "{stale:?}"
        );
        assert!(agent.inputs().is_empty());
        core.answer_question_background(
            host.clone(),
            agent.address.clone(),
            COLOR.into(),
            vec![vec![1]],
        )
        .await
        .unwrap();
    } else {
        core.answer_question(
            host.clone(),
            agent.address.clone(),
            COLOR.into(),
            vec![vec![1]],
        )
        .await
        .unwrap();
    }
    assert_eq!(inputs(&agent, 1).await, ["keys 32"]);

    wait_for(&watch, "answered question", |s| {
        if !question(s).is_some_and(|(_, status, _)| status == ChatToolStatus::Done) {
            return false;
        }
        let recorded = s
            .entries
            .iter()
            .find_map(|entry| match &entry.body {
                ChatEntryBody::Question { questions, .. } => questions[0].answer.as_ref(),
                _ => None,
            })
            .expect("the recorded answer reaches the phone core");
        assert_eq!(recorded.choices, [1]);
        true
    })
    .await;
    let again = if background {
        core.answer_question_background(host, agent.address.clone(), COLOR.into(), vec![vec![2]])
            .await
    } else {
        core.answer_question(host, agent.address.clone(), COLOR.into(), vec![vec![2]])
            .await
    };
    assert!(
        matches!(
            again,
            Err(CoreError::AnswerRefused {
                reason: AnswerProblem::Answered,
                ..
            })
        ),
        "{again:?}"
    );
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(agent.inputs(), ["keys 32"]);
    watch.stop();
}

#[tokio::test]
async fn without_a_beacon_answering_says_so() {
    let env = Env::new();
    let host = env.host(HashMap::from([(
        "ARIES_BEACON".to_string(),
        "/nonexistent/aries-beacon".to_string(),
    )]));
    let r = env
        .core
        .answer_question(host, "t1".into(), "q".into(), vec![vec![0]])
        .await;
    assert!(
        matches!(
            r,
            Err(CoreError::AnswerRefused {
                reason: AnswerProblem::NoBeacon,
                ..
            })
        ),
        "{r:?}"
    );
}

/// Free text must open Other and type UTF-8, without bracketed paste or
/// terminal.send (which would answer the wrong dialog or add a message).
#[tokio::test]
async fn claude_free_text_uses_other_and_types_korean_literally() {
    let env = Env::new();
    let hc = HcSession::start();
    let config = env.dir.path().join("claude");
    let projects = config.join("projects/-work-a");
    std::fs::create_dir_all(&projects).unwrap();
    let agent = hc.run_fake_agent("claude", &format!(
        "claude-session {} S1 /work/a\nlines claude-code/main-session.jsonl 1 65 {}\nsay Which color do you prefer?\nsay 4. Type something.\nstatus blocked question\nkeys 11\nstatus working\n",
        config.display(), projects.join("S1.jsonl").display(),
    ));
    let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
    vars.insert(
        "ARIES_BEACON".into(),
        path(&aries_test_support::beacon_bin()),
    );
    vars.insert("CLAUDE_CONFIG_DIR".into(), path(&config));
    vars.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", hc.bin().parent().unwrap().display()),
    );
    let host = env.host(vars);
    for _ in 0..100 {
        if hc
            .hc(&["get", &agent.address, "--json"])
            .contains("\"blocked\"")
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    for text in [
        String::new(),
        "bad\nanswer".into(),
        "\u{1b}[200~escape".into(),
        "x".repeat(4097),
    ] {
        let invalid = env
            .core
            .answer_question_with_text(
                host.clone(),
                agent.address.clone(),
                COLOR.into(),
                vec![vec![]],
                vec![Some(text)],
            )
            .await;
        assert!(
            matches!(
                invalid,
                Err(CoreError::AnswerRefused {
                    reason: AnswerProblem::Invalid,
                    ..
                })
            ),
            "{invalid:?}"
        );
        assert!(agent.inputs().is_empty());
    }
    env.core
        .answer_question_with_text(
            host,
            agent.address.clone(),
            COLOR.into(),
            vec![vec![]],
            vec![Some("보라색".into())],
        )
        .await
        .unwrap();
    assert_eq!(
        inputs(&agent, 1).await,
        ["keys 34 eb b3 b4 eb 9d bc ec 83 89 0d"]
    );
}

#[tokio::test]
async fn codex_queued_requests_are_answered_in_order_with_text_or_options() {
    let env = Env::new();
    let hc = HcSession::start();
    let rollout = env
        .dir
        .path()
        .join("sessions/rollout-2026-11-04T00-00-00-01a1a012-3af0-7c01-97e4-7e2adbc2ae4f.jsonl");
    std::fs::create_dir_all(rollout.parent().unwrap()).unwrap();
    let call = |id: &str, prompt: &str| {
        serde_json::json!({"type":"response_item","payload":{
        "type":"function_call","name":"request_user_input","call_id":id,
        "arguments":serde_json::json!({"questions":[{"id":"color","question":prompt,"header":"Color","options":[{"label":"Red","description":"red"},{"label":"Blue","description":"blue"}]}]}).to_string()
    }}).to_string()
    };
    std::fs::write(
        &rollout,
        format!(
            "{}\n{}\n",
            call("q1", "First color?"),
            call("q2", "Second color?")
        ),
    )
    .unwrap();
    let result = env.dir.path().join("q1-result");
    std::fs::write(&result, "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"q1\",\"output\":\"Purple\"}}\n").unwrap();
    let agent = hc.run_fake_agent("codex", &format!(
        "hold {}\nsay 2 questions - shift+left to answer\nstatus blocked question\nkeys 6\nsay First color?\nsay › 2. Blue\nsay 3. Other\nkeys 11\ntranscript {} {}\nsay Second color?\nstatus blocked question\nkeys 4\nstatus working\n",
        rollout.display(), result.display(), rollout.display(),
    ));
    let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
    vars.insert(
        "ARIES_BEACON".into(),
        path(&aries_test_support::beacon_bin()),
    );
    vars.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", hc.bin().parent().unwrap().display()),
    );
    let host = env.host(vars);
    for _ in 0..100 {
        if hc
            .hc(&["get", &agent.address, "--json"])
            .contains("\"blocked\"")
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let early = env
        .core
        .answer_question(
            host.clone(),
            agent.address.clone(),
            "q2".into(),
            vec![vec![1]],
        )
        .await;
    assert!(
        matches!(
            early,
            Err(CoreError::AnswerRefused {
                reason: AnswerProblem::Gone,
                ..
            })
        ),
        "{early:?}"
    );
    assert!(agent.inputs().is_empty());
    env.core
        .answer_question_with_text(
            host.clone(),
            agent.address.clone(),
            "q1".into(),
            vec![vec![]],
            vec![Some("Purple".into())],
        )
        .await
        .unwrap();
    assert_eq!(
        inputs(&agent, 2).await,
        [
            "keys 1b 5b 31 3b 32 44",
            "keys 1b 5b 42 0d 50 75 72 70 6c 65 0d"
        ]
    );
    env.core
        .answer_question(host, agent.address.clone(), "q2".into(), vec![vec![1]])
        .await
        .unwrap();
    assert_eq!(inputs(&agent, 3).await[2], "keys 1b 5b 42 0d");
}

#[tokio::test]
async fn codex_rechecks_each_part_before_mixing_free_text_and_options() {
    let env = Env::new();
    let hc = HcSession::start();
    let rollout = env
        .dir
        .path()
        .join("sessions/rollout-2026-11-04T00-00-00-01a1a012-3af0-7c01-97e4-7e2adbc2ae4f.jsonl");
    std::fs::create_dir_all(rollout.parent().unwrap()).unwrap();
    let part = |id: &str, text: &str| serde_json::json!({"id":id,"header":"Color","question":text,"options":[{"label":"Red","description":"red"},{"label":"Blue","description":"blue"}]});
    let call = serde_json::json!({"type":"response_item","payload":{"type":"function_call","name":"request_user_input","call_id":"q1","arguments":serde_json::json!({"questions":[part("first", "First color?"),part("second", "Second color?")]}).to_string()}});
    std::fs::write(&rollout, format!("{call}\n")).unwrap();
    let agent = hc.run_fake_agent("codex", &format!(
        "hold {}\nsay First color?\nsay › 2. Blue\nsay 3. Other\nstatus blocked question\nkeys 11\nsay Second color?\nsay › 1. Red\nsay 3. Other\nkeys 1\nstatus working\n", rollout.display(),
    ));
    let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
    vars.insert(
        "ARIES_BEACON".into(),
        path(&aries_test_support::beacon_bin()),
    );
    vars.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", hc.bin().parent().unwrap().display()),
    );
    let host = env.host(vars);
    for _ in 0..100 {
        if hc
            .hc(&["get", &agent.address, "--json"])
            .contains("\"blocked\"")
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    env.core
        .answer_question_with_text(
            host,
            agent.address.clone(),
            "q1".into(),
            vec![vec![], vec![0]],
            vec![Some("Purple".into()), None],
        )
        .await
        .unwrap();
    assert_eq!(
        inputs(&agent, 2).await,
        ["keys 1b 5b 42 0d 50 75 72 70 6c 65 0d", "keys 0d"]
    );
}
