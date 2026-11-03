//! OpenCode end to end on one Host: the plugin the Beacon installs runs in a
//! real (isolated) herdr-core Terminal inside a stand-in for OpenCode
//! (`fixtures/agents/opencode/plugin-harness.mjs`, needs `node`), reports
//! OSC 7501 and labels the Terminal; `aries-beacon chat` pairs through that
//! label and reads the session from an OpenCode database; `aries-beacon
//! answer` presses the option's number.

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use aries_protocol::answer::{AnswerOutcome, AnswerReply};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatLine, ChatState, ToolStatus};
use aries_test_support::HcSession;
use aries_test_support::opencode::{Db, OpenCodeStandIn, db_path, fixture, parts_of, row};
use serde_json::{Value, json};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");
const MAIN: &str = "ses_e5fed9273ffe3XexHeUg20WxR5";

struct Host {
    hc: HcSession,
    dir: tempfile::TempDir,
}

impl Host {
    fn new() -> Self {
        Self {
            hc: HcSession::start(),
            dir: tempfile::tempdir().unwrap(),
        }
    }

    fn path(&self, p: &str) -> PathBuf {
        self.dir.path().join(p)
    }

    fn beacon(&self, args: &[&str]) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.args(args).env_clear();
        cmd.envs(self.hc.env());
        let bin_dir = self.hc.bin().parent().unwrap();
        cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
        cmd.env("HOME", self.path("home"));
        cmd.env("XDG_CONFIG_HOME", self.path("config"));
        cmd.stdin(Stdio::null());
        cmd
    }

    /// Installs the plugin the way the phone does, and returns its path.
    fn install_plugin(&self) -> PathBuf {
        let out = self.beacon(&["integrations", "install"]).output().unwrap();
        assert!(out.status.success(), "{out:?}");
        assert_eq!(
            String::from_utf8_lossy(&out.stdout).trim(),
            r#"{"opencode":"installed"}"#
        );
        self.path("config/opencode/plugins/aries-agent-state.js")
    }

    /// The stand-in OpenCode with the plugin and `steps`, its data in
    /// `data/`. Starts labelled with a stale session, as a Terminal where
    /// OpenCode ran before. Its `PATH` has no `hc`: the plugin labels over
    /// the socket.
    fn opencode(&self, plugin: &Path, steps: &[Value]) -> OpenCodeStandIn {
        self.hc.run_opencode(
            &self.path("bin"),
            plugin,
            &self.path("data"),
            &[("aries.opencode.session", "ses_stale")],
            steps,
        )
    }

    fn get(&self, t: &str) -> Value {
        serde_json::from_str(&self.hc.hc(&["get", t, "--json"])).unwrap()
    }

    fn wait(&self, t: &str, what: &str, ok: impl Fn(&Value) -> bool) -> Value {
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            let get = self.get(t);
            if ok(&get) {
                return get;
            }
            assert!(Instant::now() < deadline, "never {what}: {get}");
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    fn answer(&self, t: &str, args: &[&str]) -> AnswerReply {
        let mut a = vec!["answer", t];
        a.extend_from_slice(args);
        let out = self.beacon(&a).output().unwrap();
        let text = String::from_utf8(out.stdout).unwrap();
        AnswerReply::parse(text.lines().last().unwrap_or_default())
            .unwrap_or_else(|e| panic!("{text:?}: {e}"))
    }
}

struct Follow {
    child: Child,
    lines: mpsc::Receiver<ChatLine>,
}

impl Follow {
    fn start(mut cmd: Command) -> Self {
        let mut child = cmd.stdout(Stdio::piped()).spawn().unwrap();
        let stdout = child.stdout.take().unwrap();
        let (tx, lines) = mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                let Ok(line) = line else { break };
                if tx.send(ChatLine::parse(&line).unwrap()).is_err() {
                    break;
                }
            }
        });
        Self { child, lines }
    }

    /// Lines up to and including the first one `done` accepts.
    fn until(&self, what: &str, done: impl Fn(&ChatLine) -> bool) -> Vec<ChatLine> {
        let mut seen = Vec::new();
        loop {
            let line = self
                .lines
                .recv_timeout(Duration::from_secs(15))
                .unwrap_or_else(|_| panic!("no {what}; got {seen:#?}"));
            let stop = done(&line);
            seen.push(line);
            if stop {
                return seen;
            }
        }
    }
}

impl Drop for Follow {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn state(want: ChatState) -> impl Fn(&ChatLine) -> bool {
    move |l| matches!(l.event, ChatEvent::State { state } if state == want)
}

fn tool_status(id: &str, want: ToolStatus) -> impl Fn(&ChatLine) -> bool + '_ {
    move |l| match &l.event {
        ChatEvent::Item { item } if item.id == id => {
            matches!(&item.body, ChatBody::Tool { status, .. } if *status == want)
        }
        _ => false,
    }
}

#[test]
fn an_opencode_terminal_reports_pairs_shows_its_chat_and_takes_an_answer() {
    let host = Host::new();
    let plugin = host.install_plugin();
    let f = fixture("tui-permission-question-diff");
    let questions = row(&f, "parts", "b4enhf")["data"]["state"]["input"]["questions"].clone();
    let session = |extra: Value| {
        let mut props = json!({ "sessionID": MAIN });
        props
            .as_object_mut()
            .unwrap()
            .extend(extra.as_object().unwrap().clone());
        props
    };
    let oc = host.opencode(
        &plugin,
        &[
            json!({"hook": "chat.message", "input": {"sessionID": MAIN}}),
            json!({"event": "session.status", "props": session(json!({"status": {"type": "busy"}}))}),
            json!({"event": "question.asked", "props": session(json!({"id": "que_1", "questions": questions}))}),
            json!({"wait": "key"}),
            json!({"event": "question.replied", "props": session(json!({"requestID": "que_1", "answers": [["Green"]]}))}),
            json!({"event": "session.status", "props": session(json!({"status": {"type": "idle"}}))}),
            json!({"event": "session.idle", "props": session(json!({}))}),
            json!({"wait": "line"}),
            json!({"hook": "chat.message", "input": {"sessionID": MAIN}}),
            json!({"event": "session.error", "props": session(json!({"error": {"name": "APIError", "data": {"message": "Cannot connect to API"}}}))}),
            json!({"event": "session.status", "props": session(json!({"status": {"type": "idle"}}))}),
            json!({"event": "session.idle", "props": session(json!({}))}),
        ],
    );
    let t = oc.address.clone();

    // The plugin replaced the stale label and reports the open question.
    let get = host.wait(&t, "blocked on a question", |g| {
        g["status"]["state"] == "blocked" && g["labels"]["aries.opencode.session"] == MAIN
    });
    assert_eq!(get["status"]["kind"], "question");
    assert_eq!(get["status"]["app"], "opencode");

    // OpenCode's database so far: the prompt, the question waiting.
    let db = Db::create(&db_path(&host.path("data")));
    db.session(&f["session"]);
    for id in ["md1Dbt", "AVI2rO"] {
        let mut m = row(&f, "messages", id);
        m["time_updated"] = m["time_created"].clone();
        db.message(&m);
        for p in parts_of(&f, &m) {
            db.part(&p);
        }
    }
    let q = row(&f, "parts", "b4enhf");
    let qid = q["id"].as_str().unwrap().to_owned();
    let mut running = q.clone();
    running["time_updated"] = json!(1793686948992i64);
    running["data"]["state"]["status"] = json!("running");
    db.part(&running);

    // The Beacon's own XDG_DATA_HOME is the core's: it must find
    // OpenCode's database through OpenCode's environment.
    let follow = Follow::start(host.beacon(&["chat", &t, "--follow"]));
    let first = follow.until("the open question", tool_status(&qid, ToolStatus::Running));
    assert!(first.iter().any(|l| matches!(
        &l.event,
        ChatEvent::Reset { agent, session } if agent == "opencode" && session == MAIN
    )));

    // Green is option 1: OpenCode picks and submits on "2".
    let reply = host.answer(&t, &[&qid, "1"]);
    assert_eq!(reply.outcome, AnswerOutcome::Sent, "{reply:?}");
    let deadline = Instant::now() + Duration::from_secs(10);
    while oc.inputs().is_empty() {
        assert!(Instant::now() < deadline, "no keys arrived");
        std::thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(oc.inputs(), ["32"]);

    // OpenCode records the answer and replies.
    db.part(&q);
    let reply_msg = row(&f, "messages", "d5rBVR");
    db.message(&reply_msg);
    for p in parts_of(&f, &reply_msg) {
        db.part(&p);
    }
    let after = follow.until("the reply", |l| {
        matches!(&l.event, ChatEvent::Item { item }
            if matches!(&item.body, ChatBody::Agent { text } if text == "Green it is."))
    });
    assert!(after.iter().any(|l| tool_status(&qid, ToolStatus::Done)(l)));
    assert_eq!(
        host.answer(&t, &[&qid, "1"]).outcome,
        AnswerOutcome::Answered
    );

    // The turn ends; then a failing turn stays an error after OpenCode's
    // idle.
    host.wait(&t, "idle", |g| g["status"]["state"] == "idle");
    host.hc.hc(&["send", &t, "next"]);
    let get = host.wait(&t, "error", |g| g["status"]["state"] == "error");
    std::thread::sleep(Duration::from_millis(300));
    let get2 = host.get(&t);
    assert_eq!(get2["status"]["state"], "error", "{get} then {get2}");
    assert_eq!(get2["status"]["msg"], "Cannot connect to API");

    // OpenCode exits; the label stays but the Chat has ended.
    host.hc.hc(&["kill", &t]);
    follow.until("ended", state(ChatState::Ended));
}

#[test]
fn a_resumed_opencode_pairs_by_its_session_argument_before_the_first_prompt() {
    let host = Host::new();
    let f = fixture("tui-api-error");
    let id = f["session"]["id"].as_str().unwrap().to_owned();
    Db::create(&db_path(&host.path("data"))).load(&f);
    let bin = host.path("bin");
    std::fs::create_dir_all(&bin).unwrap();
    // A shell script named opencode, holding still like the TUI.
    let fake = bin.join("opencode");
    std::fs::write(&fake, "#!/bin/sh\nsleep 600\n").unwrap();
    let data = format!("XDG_DATA_HOME={}", host.path("data").display());
    let t = host.hc.run_bg(
        "opencode",
        &["env", &data, "/bin/sh", &fake.to_string_lossy(), "-s", &id],
    );
    let follow = Follow::start(host.beacon(&["chat", &t, "--follow"]));
    let lines = follow.until("live", state(ChatState::Live));
    let texts: Vec<String> = lines
        .iter()
        .filter_map(|l| match &l.event {
            ChatEvent::Item { item } => Some(format!("{:?}", item.body)),
            _ => None,
        })
        .collect();
    assert_eq!(texts.len(), 2, "{texts:#?}");
    assert!(texts[1].contains("Cannot connect to API"));
    let cursor = lines.iter().rev().find_map(|l| l.cursor.clone()).unwrap();
    assert!(cursor.starts_with(&format!("opencode:{id}:")), "{cursor}");
    host.hc.hc(&["kill", &t]);
}

#[test]
fn a_new_opencode_clears_the_label_an_earlier_one_left() {
    let host = Host::new();
    let plugin = host.install_plugin();
    let t = host.opencode(&plugin, &[]).address;
    host.wait(&t, "the stale label gone", |g| {
        g["labels"]["aries.opencode.session"]
            .as_str()
            .unwrap_or_default()
            .is_empty()
    });
    // Before its first prompt it has no session: no Chat yet.
    let out = host.beacon(&["chat", &t]).output().unwrap();
    let last = String::from_utf8_lossy(&out.stdout)
        .lines()
        .last()
        .map(ChatLine::parse);
    assert!(matches!(
        last,
        Some(Ok(ChatLine {
            event: ChatEvent::State {
                state: ChatState::NoAgent
            },
            ..
        }))
    ));
    host.hc.hc(&["kill", &t]);
}
