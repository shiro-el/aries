//! `aries-beacon chat` on a Pi Terminal, as a black box: a real (isolated)
//! herdr-core, paired through the `aries.pi.*` labels the Aries Pi
//! extension sets.
//!
//! `a_fake_pi_…` plays Pi with the fake agent (titled `pi`). The ignored
//! `real_pi_…` runs the real Pi with the shipped extension and a scripted
//! model; it needs `ARIES_TEST_PI_BIN` (a dir holding `pi`) and `node`:
//! `ARIES_TEST_PI_BIN=/tmp/aries-mvp-run/tools/pi/node_modules/.bin \
//!  cargo test -p aries-beacon --test pi_cli -- --ignored`.

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ChatLine, ChatState, ToolStatus};
use aries_test_support::{HcSession, fake_agent_path, repo_root};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

fn beacon(hc: &HcSession, home: &Path, args: &[&str]) -> Command {
    let mut cmd = Command::new(BEACON);
    cmd.args(args).env_clear();
    cmd.envs(hc.env());
    let bin_dir = hc.bin().parent().unwrap();
    cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
    cmd.env("HOME", home);
    cmd.stdin(Stdio::null());
    cmd
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

    fn until(&self, want: ChatState) -> Vec<ChatLine> {
        let mut seen = Vec::new();
        loop {
            let line = self
                .lines
                .recv_timeout(Duration::from_secs(15))
                .unwrap_or_else(|_| panic!("no {want:?} state; got {seen:#?}"));
            let done = matches!(line.event, ChatEvent::State { state } if state == want);
            seen.push(line);
            if done {
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

fn items(lines: &[ChatLine]) -> Vec<ChatItem> {
    let mut out: Vec<ChatItem> = Vec::new();
    for l in lines {
        match &l.event {
            ChatEvent::Reset { .. } => out.clear(),
            ChatEvent::Item { item } => match out.iter_mut().find(|i| i.id == item.id) {
                Some(slot) => *slot = item.clone(),
                None => out.push(item.clone()),
            },
            ChatEvent::Remove { id } => out.retain(|i| &i.id != id),
            _ => {}
        }
    }
    out
}

fn resets(lines: &[ChatLine]) -> Vec<(String, String)> {
    lines
        .iter()
        .filter_map(|l| match &l.event {
            ChatEvent::Reset { agent, session } => Some((agent.clone(), session.clone())),
            _ => None,
        })
        .collect()
}

fn user_texts(items: &[ChatItem]) -> Vec<String> {
    items
        .iter()
        .filter_map(|i| match &i.body {
            ChatBody::User { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

#[test]
fn a_fake_pi_terminal_is_followed_across_new_sessions() {
    let hc = HcSession::start();
    let home = tempfile::tempdir().unwrap();
    let sessions = home.path().join("sessions");
    std::fs::create_dir_all(&sessions).unwrap();
    let first = sessions.join("2026_aaa.jsonl");
    let second = sessions.join("2026_bbb.jsonl");
    let script = home.path().join("script");
    std::fs::write(
        &script,
        format!(
            "wait\n\
             transcript pi/session-full-tour.jsonl {}\n\
             wait\n\
             transcript pi/session-after-new.jsonl {}\n",
            first.display(),
            second.display()
        ),
    )
    .unwrap();
    let input = home.path().join("input");
    let agent = fake_agent_path();
    // Titled `pi`, like Pi's own process.
    let address = hc.run_labeled(
        "pi",
        &[
            ("aries.pi.session", "aaa"),
            ("aries.pi.file", &first.to_string_lossy()),
        ],
        &[
            "env",
            &format!("FAKE_AGENT_INPUT={}", input.display()),
            "bash",
            "-c",
            "exec -a pi /bin/sh \"$0\" \"$1\"",
            &agent.to_string_lossy(),
            &script.to_string_lossy(),
        ],
    );

    let follow = Follow::start(beacon(&hc, home.path(), &["chat", &address, "--follow"]));
    // The session file appears only with the first message.
    let waiting = follow.until(ChatState::Waiting);
    assert_eq!(resets(&waiting), [("pi".into(), "aaa".into())]);

    hc.hc(&["send", &address, "go"]);
    let live = follow.until(ChatState::Live);
    let chat = items(&live);
    assert_eq!(user_texts(&chat)[0], "hello there");
    assert!(chat.iter().any(|i| matches!(
        &i.body,
        ChatBody::Tool { name, diff: Some(_), status: ToolStatus::Done, .. } if name == "edit"
    )));
    let cursor = live.iter().rev().find_map(|l| l.cursor.clone()).unwrap();
    assert!(cursor.starts_with("pi:aaa:"), "{cursor}");

    // `/new`: the extension relabels the Terminal with the new file.
    hc.hc(&[
        "label",
        &address,
        "aries.pi.session=bbb",
        &format!("aries.pi.file={}", second.display()),
    ]);
    let waiting = follow.until(ChatState::Waiting);
    assert_eq!(resets(&waiting), [("pi".into(), "bbb".into())]);
    hc.hc(&["send", &address, "go"]);
    let live = follow.until(ChatState::Live);
    assert!(!items(&live).is_empty());

    // Pi exits; the labels stay but its Chat is over.
    hc.hc(&["kill", &address]);
    follow.until(ChatState::Ended);
}

// The real Pi.

fn wait_for(what: &str, timeout: Duration, mut ok: impl FnMut() -> bool) {
    let deadline = Instant::now() + timeout;
    while !ok() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn get(hc: &HcSession, t: &str) -> serde_json::Value {
    serde_json::from_str(&hc.hc(&["get", t, "--json"])).unwrap()
}

struct Llm(Child);

impl Drop for Llm {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
#[ignore = "needs a Pi install (ARIES_TEST_PI_BIN) and node"]
fn real_pi_with_the_extension_reports_status_and_shows_its_chat() {
    let pi_bin = PathBuf::from(std::env::var("ARIES_TEST_PI_BIN").expect("ARIES_TEST_PI_BIN"));
    let hc = HcSession::start();
    let home = tempfile::tempdir().unwrap();
    let agent_dir = home.path().join("piagent");
    let work = home.path().join("work");
    std::fs::create_dir_all(&work).unwrap();

    // The scripted model.
    let mut llm = Command::new("node")
        .arg(repo_root().join("fixtures/agents/pi/fake-llm.mjs"))
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    let mut ready = String::new();
    BufReader::new(llm.stdout.take().unwrap())
        .read_line(&mut ready)
        .unwrap();
    let _llm = Llm(llm);
    let port: u16 = ready.trim().rsplit(' ').next().unwrap().parse().unwrap();
    std::fs::create_dir_all(&agent_dir).unwrap();
    std::fs::write(
        agent_dir.join("models.json"),
        serde_json::json!({ "providers": { "fake": {
            "baseUrl": format!("http://127.0.0.1:{port}/v1"),
            "api": "openai-completions",
            "apiKey": "not-a-secret",
            "compat": { "supportsDeveloperRole": false, "supportsReasoningEffort": false },
            "models": [{ "id": "fake-1", "reasoning": true, "input": ["text", "image"] }]
        }}})
        .to_string(),
    )
    .unwrap();
    // Short retries, read by the extension as well as by Pi.
    std::fs::write(
        agent_dir.join("settings.json"),
        r#"{"retry":{"enabled":true,"maxRetries":2,"baseDelayMs":200}}"#,
    )
    .unwrap();

    // What the phone runs after installing the Beacon.
    let out = beacon(&hc, home.path(), &["pi-extension", "install"])
        .env("PI_CODING_AGENT_DIR", &agent_dir)
        .output()
        .unwrap();
    assert!(out.status.success(), "{out:?}");

    let path = format!(
        "{}:{}:/usr/bin:/bin",
        hc.bin().parent().unwrap().display(),
        pi_bin.display()
    );
    let t = hc.run_bg(
        "pi",
        &[
            "env",
            &format!("HOME={}", home.path().display()),
            &format!("PATH={path}"),
            &format!("PI_CODING_AGENT_DIR={}", agent_dir.display()),
            "PI_OFFLINE=1",
            "PI_TELEMETRY=0",
            "sh",
            "-c",
            &format!(
                "cd {} && exec pi --provider fake --model fake-1",
                work.display()
            ),
        ],
    );
    wait_for("idle and labels", Duration::from_secs(30), || {
        let v = get(&hc, &t);
        v["status"]["state"] == "idle" && v["labels"]["aries.pi.file"].is_string()
    });
    let file = get(&hc, &t)["labels"]["aries.pi.file"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(
        file.starts_with(&agent_dir.to_string_lossy().into_owned()),
        "{file}"
    );

    hc.hc(&["send", &t, "dobash now", "--wait", "--timeout", "60"]);
    assert_eq!(get(&hc, &t)["status"]["state"], "done");

    let chat = |hc: &HcSession| {
        let out = beacon(hc, home.path(), &["chat", &t]).output().unwrap();
        let lines: Vec<ChatLine> = String::from_utf8(out.stdout)
            .unwrap()
            .lines()
            .map(|l| ChatLine::parse(l).unwrap())
            .collect();
        items(&lines)
    };
    let items1 = chat(&hc);
    assert_eq!(user_texts(&items1), ["dobash now"]);
    assert!(
        items1.iter().any(|i| matches!(
            &i.body,
            ChatBody::Tool { name, status: ToolStatus::Done, output: Some(o), .. }
                if name == "bash" && o.contains("hello-from-bash")
        )),
        "{items1:#?}"
    );

    // A failing model: retried twice (status stays working, one turn),
    // then one error, shown once in the Chat.
    let turn = get(&hc, &t)["turn"].as_u64().unwrap_or(0);
    hc.hc(&["send", &t, "fail500 now"]);
    wait_for("error", Duration::from_secs(60), || {
        get(&hc, &t)["status"]["state"] == "error"
    });
    let after = get(&hc, &t)["turn"].as_u64().unwrap_or(0);
    assert_eq!(after, turn + 1, "one turn for the retried prompt");
    let errors = chat(&hc)
        .into_iter()
        .filter(|i| matches!(i.body, ChatBody::Error { .. }))
        .count();
    assert_eq!(errors, 1);
    hc.hc(&["kill", &t]);
}
