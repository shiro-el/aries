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

    env.core
        .answer_question(
            host.clone(),
            agent.address.clone(),
            COLOR.into(),
            vec![vec![1]],
        )
        .await
        .unwrap();
    assert_eq!(inputs(&agent, 1).await, ["keys 32"]);

    wait_for(&watch, "answered question", |s| {
        question(s).is_some_and(|(_, status, _)| status == ChatToolStatus::Done)
    })
    .await;
    let again = env
        .core
        .answer_question(host, agent.address.clone(), COLOR.into(), vec![vec![2]])
        .await;
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
