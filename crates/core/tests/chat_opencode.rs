//! An OpenCode Agent's Chat through the core API (seam 1): the real Beacon
//! and a real herdr-core, a stand-in OpenCode running the Beacon's plugin
//! (it reports the question and labels the Terminal), and an OpenCode
//! database written the way OpenCode does. Needs `node`.

mod common;

use std::collections::HashMap;
use std::path::Path;
use std::process::Command;
use std::sync::Arc;
use std::time::Duration;

use aries_core::{
    AnswerProblem, AriesCore, ChatEntryBody, ChatSnapshot, ChatToolStatus, ChatWatch, CoreError,
    Endpoint,
};
use aries_test_support::HcSession;
use aries_test_support::opencode::{Db, db_path, fixture, parts_of, row};
use serde_json::json;

const MAIN: &str = "ses_e5fed9273ffe3XexHeUg20WxR5";

fn path(p: &Path) -> String {
    p.to_string_lossy().into_owned()
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

#[tokio::test]
async fn an_opencode_chat_shows_its_turns_and_answers_its_question() {
    let dir = tempfile::tempdir().unwrap();
    let core: Arc<AriesCore> = common::core_in(&dir.path().join("app"));
    let hc = HcSession::start();
    let beacon = aries_test_support::beacon_bin();

    // The plugin, installed by the Beacon as on a Host.
    let config = dir.path().join("config");
    let out = Command::new(&beacon)
        .args(["opencode-plugin", "install"])
        .env("XDG_CONFIG_HOME", &config)
        .output()
        .unwrap();
    assert!(out.status.success(), "{out:?}");
    let plugin = config.join("opencode/plugins/aries-agent-state.js");

    let f = fixture("tui-permission-question-diff");
    let q = row(&f, "parts", "b4enhf");
    let qid = q["id"].as_str().unwrap().to_owned();
    let data = dir.path().join("data");
    let oc = hc.run_opencode(
        &dir.path().join("oc"),
        &plugin,
        &data,
        &[],
        &[
            json!({"hook": "chat.message", "input": {"sessionID": MAIN}}),
            json!({"event": "question.asked", "props": {
                "id": "que_1", "sessionID": MAIN,
                "questions": q["data"]["state"]["input"]["questions"],
            }}),
            json!({"wait": "key"}),
            json!({"event": "question.replied", "props": {"sessionID": MAIN, "requestID": "que_1"}}),
        ],
    );

    // So far: the first prompt and its turn, the question open.
    let db = Db::create(&db_path(&data));
    db.session(&f["session"]);
    for id in ["C52pks", "U9LtML", "Af7dr2", "md1Dbt", "AVI2rO"] {
        let mut m = row(&f, "messages", id);
        m["time_updated"] = m["time_created"].clone();
        db.message(&m);
        for p in parts_of(&f, &m) {
            if p["id"] != q["id"] {
                db.part(&p);
            }
        }
    }
    let mut running = q.clone();
    running["data"]["state"]["status"] = json!("running");
    db.part(&running);

    let bin_dir = hc.bin().parent().unwrap().to_path_buf();
    let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
    vars.insert("ARIES_BEACON".into(), path(&beacon));
    vars.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", bin_dir.display()),
    );
    let host = core
        .add_host(
            "h".into(),
            Endpoint::Local {
                argv: vec![],
                env: vars,
            },
        )
        .unwrap()
        .id;
    let watch = core
        .watch_chat_retrying(
            host.clone(),
            oc.address.clone(),
            None,
            Duration::from_millis(50),
        )
        .unwrap();

    wait_for(&watch, "the open question", |s| {
        question(s).is_some_and(|(id, status, labels)| {
            id == qid && status == ChatToolStatus::Running && labels == ["Red", "Green", "Blue"]
        })
    })
    .await;
    let snap = watch.next().await.unwrap();
    let kinds: Vec<&str> = snap
        .entries
        .iter()
        .map(|e| match &e.body {
            ChatEntryBody::User { .. } => "user",
            ChatEntryBody::Agent { .. } => "agent",
            ChatEntryBody::Thinking { .. } => "thinking",
            ChatEntryBody::Tool { .. } => "tool",
            ChatEntryBody::Question { .. } => "question",
            ChatEntryBody::AgentError { .. } => "error",
            ChatEntryBody::Unsupported => "unsupported",
        })
        .collect();
    assert_eq!(
        kinds,
        ["user", "thinking", "tool", "agent", "user", "question"]
    );

    // The plugin has reported the question to herdr-core.
    for _ in 0..200 {
        if hc
            .hc(&["get", &oc.address, "--json"])
            .contains("\"blocked\"")
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    core.answer_question(host.clone(), oc.address.clone(), qid.clone(), vec![vec![1]])
        .await
        .unwrap();
    for _ in 0..200 {
        if !oc.inputs().is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert_eq!(oc.inputs(), ["32"]);

    // OpenCode records the answer, then the edit turns with their diff.
    db.part(&q);
    for id in ["d5rBVR", "QrpUse", "n7EQ6h", "6pTunE", "j7WjHa", "vSgN8h"] {
        let m = row(&f, "messages", id);
        db.message(&m);
        for p in parts_of(&f, &m) {
            db.part(&p);
        }
    }
    wait_for(&watch, "the edit's diff", |s| {
        question(s).is_some_and(|(_, status, _)| status == ChatToolStatus::Done)
            && s.entries.iter().any(|e| {
                matches!(&e.body, ChatEntryBody::Tool { diff: Some(d), .. }
                    if d.hunks[0].lines.contains(&"+line 2".to_string()))
            })
    })
    .await;

    let again = core
        .answer_question(host, oc.address.clone(), qid, vec![vec![0]])
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
    assert_eq!(oc.inputs(), ["32"]);
    watch.stop();
}
