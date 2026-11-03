//! `AriesCore::watch_chat`: the app's side of the Chat stream, against fake
//! Beacons (scripts that print Chat lines) and the real Beacon following a
//! fake Claude Code in an isolated herdr-core.

mod common;

use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use aries_core::{
    AriesCore, ChatEntryBody, ChatSnapshot, ChatStatus, ChatToolStatus, ChatWatch, CoreError,
    Endpoint,
};
use aries_test_support::HcSession;

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

    /// A Local Host whose "Beacon" is `script` (sh), getting the Beacon's
    /// arguments.
    fn fake_beacon(&self, script: &str) -> String {
        let path = self.dir.path().join("beacon.sh");
        std::fs::write(&path, format!("#!/bin/sh\n{script}\n")).unwrap();
        std::process::Command::new("chmod")
            .arg("+x")
            .arg(&path)
            .status()
            .unwrap();
        self.host(HashMap::from([(
            "ARIES_BEACON".to_string(),
            path.to_string_lossy().into_owned(),
        )]))
    }

    fn host(&self, env: HashMap<String, String>) -> String {
        self.core
            .add_host("h".into(), Endpoint::Local { argv: vec![], env })
            .unwrap()
            .id
    }

    fn file(&self, name: &str, text: &str) -> String {
        let path = self.dir.path().join(name);
        std::fs::write(&path, text).unwrap();
        path.to_string_lossy().into_owned()
    }

    fn watch(&self, host: &str) -> Arc<ChatWatch> {
        self.core
            .watch_chat_retrying(host.into(), "t1".into(), None, Duration::from_millis(50))
            .unwrap()
    }
}

/// Waits for a snapshot matching `ok`.
async fn wait_for(
    watch: &ChatWatch,
    what: &str,
    ok: impl Fn(&ChatSnapshot) -> bool,
) -> ChatSnapshot {
    let mut last = None;
    let found = tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            match watch.next().await {
                Ok(s) if ok(&s) => return Ok(s),
                Ok(s) => last = Some(s),
                Err(e) => return Err(e),
            }
        }
    })
    .await;
    match found {
        Ok(Ok(s)) => s,
        Ok(Err(e)) => panic!("{what}: watch ended with {e:?}; last {last:#?}"),
        Err(_) => panic!("timed out waiting for {what}; last {last:#?}"),
    }
}

fn texts(s: &ChatSnapshot) -> Vec<String> {
    s.entries
        .iter()
        .map(|e| match &e.body {
            ChatEntryBody::User { text, .. } => format!("user: {text}"),
            ChatEntryBody::Agent { text } => format!("agent: {text}"),
            ChatEntryBody::Thinking { .. } => "thinking".into(),
            ChatEntryBody::Tool { name, status, .. } => format!("tool {name} {status:?}"),
            ChatEntryBody::AgentError { detail } => format!("error: {detail}"),
            ChatEntryBody::Question { name, status, .. } => format!("question {name} {status:?}"),
            ChatEntryBody::Unsupported => "unsupported".into(),
        })
        .collect()
}

#[tokio::test]
async fn a_newer_beacon_of_the_same_format_still_shows_the_chat() {
    // Only the Beacon's interpreter changed: extra fields everywhere, an
    // item kind and an event type this app has never heard of.
    let env = Env::new();
    let lines = env.file(
        "lines",
        concat!(
            r#"{"v":1,"type":"reset","agent":"claude-code","session":"s","model":"x"}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"1","kind":"user","text":"hi","source":"typed"}}"#, "\n",
            r#"{"v":1,"type":"typing","who":"agent"}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"2","kind":"question","questions":[{"q":"?"}]}}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"3","kind":"tool","name":"Write","title":"a.txt","input":{"file_path":"a.txt"},"status":"done","output":"ok","diff":[1]}}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"4","kind":"agent","text":"done","tokens":12},"cursor":"c:4"}"#, "\n",
            r#"{"v":1,"type":"state","state":"live","since":1}"#, "\n",
        ),
    );
    let host = env.fake_beacon(&format!("cat {lines}; sleep 30"));
    let watch = env.watch(&host);
    let s = wait_for(&watch, "live", |s| s.status == ChatStatus::Live).await;
    assert_eq!(
        texts(&s),
        ["user: hi", "unsupported", "tool Write Done", "agent: done"]
    );
    assert_eq!(s.cursor.as_deref(), Some("c:4"));
    let ChatEntryBody::Tool {
        input,
        output,
        title,
        ..
    } = &s.entries[2].body
    else {
        panic!()
    };
    assert_eq!(title, "a.txt");
    assert!(input.contains("\"file_path\": \"a.txt\""), "{input}");
    assert_eq!(output.as_deref(), Some("ok"));
    watch.stop();
}

#[tokio::test]
async fn reconnects_with_the_cursor_and_shows_nothing_twice() {
    let env = Env::new();
    let log = env.dir.path().join("args");
    let first = env.file(
        "first",
        concat!(
            r#"{"v":1,"type":"reset","agent":"claude-code","session":"s"}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"u1","kind":"user","text":"run it"}}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"t1","kind":"tool","name":"Bash","status":"running"},"cursor":"cc:s:20"}"#, "\n",
        ),
    );
    // After the cursor: the tool finishes (same id) and a reply follows.
    let second = env.file(
        "second",
        concat!(
            r#"{"v":1,"type":"item","item":{"id":"t1","kind":"tool","name":"Bash","status":"error","output":"boom"},"cursor":"cc:s:30"}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"a1","kind":"agent","text":"it failed"},"cursor":"cc:s:40"}"#, "\n",
            r#"{"v":1,"type":"state","state":"live"}"#, "\n",
        ),
    );
    let host = env.fake_beacon(&format!(
        r#"echo "$*" >> {log}
if [ "$(wc -l < {log})" -eq 1 ]; then cat {first}; exit 0; fi
cat {second}; sleep 30"#,
        log = log.display()
    ));
    let watch = env.watch(&host);
    let s = wait_for(&watch, "reply", |s| s.entries.len() == 3).await;
    assert_eq!(
        texts(&s),
        ["user: run it", "tool Bash Failed", "agent: it failed"]
    );
    assert_eq!(s.cursor.as_deref(), Some("cc:s:40"));
    let args = std::fs::read_to_string(&log).unwrap();
    assert_eq!(
        args.lines().collect::<Vec<_>>(),
        ["chat t1 --follow", "chat t1 --follow --cursor cc:s:20"]
    );
    watch.stop();
}

#[tokio::test]
async fn images_are_fetched_on_demand_and_a_capped_one_is_only_described() {
    let env = Env::new();
    let lines = env.file(
        "lines",
        concat!(
            r#"{"v":1,"type":"reset","agent":"claude-code","session":"s"}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"u1","kind":"user","text":"see","images":[{"media_type":"image/png","data":"aGVsbG8=","bytes":5},{"media_type":"image/jpeg","bytes":9000000}]}}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"t1","kind":"tool","name":"Edit","status":"done","output":"ok","diff":{"path":"a.txt","hunks":[{"old_start":3,"old_lines":1,"new_start":3,"new_lines":2,"lines":[" a","-b","+c","+d"]}],"truncated":true}}}"#, "\n",
            r#"{"v":1,"type":"state","state":"live"}"#, "\n",
        ),
    );
    let host = env.fake_beacon(&format!("cat {lines}; sleep 30"));
    let watch = env.watch(&host);
    let s = wait_for(&watch, "live", |s| s.status == ChatStatus::Live).await;
    let ChatEntryBody::User { text, images } = &s.entries[0].body else {
        panic!()
    };
    assert_eq!(text, "see");
    assert_eq!(images.len(), 2);
    assert!(images[0].available);
    assert_eq!(
        watch.image(images[0].id.clone()).as_deref(),
        Some(&b"hello"[..])
    );
    assert!(!images[1].available);
    assert_eq!(images[1].bytes, 9_000_000);
    assert_eq!(watch.image(images[1].id.clone()), None);
    let ChatEntryBody::Tool { diff, .. } = &s.entries[1].body else {
        panic!()
    };
    let diff = diff.as_ref().unwrap();
    assert_eq!(diff.path, "a.txt");
    assert!(diff.truncated);
    assert_eq!(
        (
            diff.hunks[0].old_start,
            diff.hunks[0].new_start,
            diff.hunks[0].lines.len()
        ),
        (3, 3, 4)
    );
    watch.stop();
}

#[tokio::test]
async fn a_newer_chat_format_is_incompatible() {
    let env = Env::new();
    let host = env.fake_beacon(r#"echo '{"v":2,"type":"reset"}'; sleep 30"#);
    let watch = env.watch(&host);
    wait_for(&watch, "incompatible", |s| {
        s.status == ChatStatus::Incompatible
    })
    .await;
    assert!(matches!(watch.next().await, Err(CoreError::Closed)));
}

#[tokio::test]
async fn a_host_without_a_beacon_says_so() {
    let env = Env::new();
    let host = env.host(HashMap::from([(
        "ARIES_BEACON".to_string(),
        "/nonexistent/aries-beacon".to_string(),
    )]));
    let watch = env.watch(&host);
    wait_for(&watch, "no beacon", |s| s.status == ChatStatus::NoBeacon).await;
}

#[tokio::test]
async fn a_broken_stream_shows_reconnecting() {
    let env = Env::new();
    let host = env.fake_beacon("exit 1");
    let watch = env.watch(&host);
    wait_for(&watch, "reconnecting", |s| {
        s.status == ChatStatus::Reconnecting
    })
    .await;
    watch.stop();
}

/// The real Beacon, following a fake Claude Code (session registry plus
/// the ticket-02 transcript) in an isolated herdr-core.
#[tokio::test]
async fn follows_a_claude_code_agent_through_the_real_beacon() {
    let env = Env::new();
    let hc = HcSession::start();
    let config = env.dir.path().join("claude");
    let projects = config.join("projects/-work-a");
    std::fs::create_dir_all(&projects).unwrap();
    let agent = hc.run_fake_agent(
        "claude",
        &format!(
            "claude-session {} S1 /work/a\nwait\ntranscript claude-code/main-session.jsonl {}/S1.jsonl\n",
            config.display(),
            projects.display()
        ),
    );
    let beacon = aries_test_support::beacon_bin();
    let bin_dir = hc.bin().parent().unwrap().to_path_buf();
    let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
    vars.insert("ARIES_BEACON".into(), path(&beacon));
    vars.insert("CLAUDE_CONFIG_DIR".into(), path(&config));
    vars.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", bin_dir.display()),
    );
    let host = env.host(vars);
    let watch = env
        .core
        .watch_chat_retrying(host, agent.address.clone(), None, Duration::from_millis(50))
        .unwrap();
    wait_for(&watch, "waiting", |s| s.status == ChatStatus::Waiting).await;

    hc.hc(&["send", &agent.address, "go"]);
    let s = wait_for(&watch, "conversation", |s| {
        s.status == ChatStatus::Live && s.entries.len() == 20
    })
    .await;
    let t = texts(&s);
    assert_eq!(t[0], "user: Reply with exactly: pong");
    assert_eq!(t[2], "agent: pong");
    assert_eq!(t[4], "tool Write Done");
    let failed = s.entries.iter().filter(|e| {
        matches!(
            &e.body,
            ChatEntryBody::Tool {
                status: ChatToolStatus::Failed,
                ..
            }
        )
    });
    assert_eq!(failed.count(), 1);
    assert!(s.cursor.as_deref().unwrap().starts_with("claude-code:S1:"));
    // The Edit renders as its diff; the Read picture as the PNG itself.
    let diff = s
        .entries
        .iter()
        .find_map(|e| match &e.body {
            ChatEntryBody::Tool { name, diff, .. } if name == "Edit" => diff.clone(),
            _ => None,
        })
        .expect("edit diff");
    assert_eq!(diff.hunks[0].lines, ["-hi", "+hello"]);
    let image = s
        .entries
        .iter()
        .find_map(|e| match &e.body {
            ChatEntryBody::Tool { images, .. } => images.first().cloned(),
            _ => None,
        })
        .expect("read image");
    assert_eq!(
        (image.width, image.height, image.bytes),
        (Some(8), Some(8), 75)
    );
    let png = watch.image(image.id).expect("image bytes");
    assert_eq!(png.len(), 75);
    assert_eq!(&png[..4], b"\x89PNG");

    hc.hc(&["kill", &agent.address]);
    wait_for(&watch, "ended", |s| s.status == ChatStatus::Ended).await;
    watch.stop();
}

/// Pi pairs through the labels its Aries extension sets; `/tree` branches
/// in the same file replace the abandoned branch's entries.
#[tokio::test]
async fn follows_a_pi_agent_and_its_tree_branches_through_the_real_beacon() {
    let env = Env::new();
    let hc = HcSession::start();
    let file = env.dir.path().join("2026_pi1.jsonl");
    let tree = "pi/synthetic-tree-branches.jsonl";
    let f = file.display();
    let script = env.file(
        "pi-script",
        &format!(
            "wait\nlines {tree} 1 7 {f}\nwait\nlines {tree} 8 9 {f}\nwait\nlines {tree} 10 12 {f}\n"
        ),
    );
    let input = env.dir.path().join("pi-input");
    // Titled `pi`, like Pi's own process.
    let agent = hc.run_labeled(
        "pi",
        &[("aries.pi.session", "pi1"), ("aries.pi.file", &path(&file))],
        &[
            "env",
            &format!("FAKE_AGENT_INPUT={}", input.display()),
            "bash",
            "-c",
            "exec -a pi /bin/sh \"$0\" \"$1\"",
            &path(&aries_test_support::fake_agent_path()),
            &script,
        ],
    );
    let bin_dir = hc.bin().parent().unwrap().to_path_buf();
    let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
    vars.insert(
        "ARIES_BEACON".into(),
        path(&aries_test_support::beacon_bin()),
    );
    vars.insert(
        "PATH".into(),
        format!("{}:/usr/bin:/bin", bin_dir.display()),
    );
    let host = env.host(vars);
    let watch = env
        .core
        .watch_chat_retrying(host, agent.clone(), None, Duration::from_millis(50))
        .unwrap();
    wait_for(&watch, "waiting", |s| s.status == ChatStatus::Waiting).await;

    hc.hc(&["send", &agent, "go"]);
    let s = wait_for(&watch, "branch A", |s| {
        s.entries.len() == 4 && texts(s)[3] == "tool bash Done"
    })
    .await;
    assert_eq!(texts(&s)[2], "user: branch A question");
    assert!(s.cursor.as_deref().unwrap().starts_with("pi:pi1:"));

    hc.hc(&["send", &agent, "go"]);
    let s = wait_for(&watch, "branch B", |s| {
        texts(s).last().map(String::as_str) == Some("agent: answer B")
    })
    .await;
    assert_eq!(
        texts(&s),
        [
            "user: first question",
            "agent: first answer",
            "user: branch B question",
            "agent: answer B"
        ]
    );

    hc.hc(&["send", &agent, "go"]);
    let s = wait_for(&watch, "back on A", |s| {
        texts(s).last().map(String::as_str) == Some("agent: answer A again")
    })
    .await;
    assert_eq!(
        texts(&s),
        [
            "user: first question",
            "agent: first answer",
            "user: branch A question",
            "tool bash Done",
            "user: back on A",
            "agent: answer A again"
        ]
    );

    hc.hc(&["kill", &agent]);
    wait_for(&watch, "ended", |s| s.status == ChatStatus::Ended).await;
    watch.stop();
}

fn path(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

#[tokio::test]
async fn reconnecting_after_a_transcript_rewrite_replaces_the_cached_chat() {
    let env = Env::new();
    let hc = HcSession::start();
    let config = env.dir.path().join("claude");
    let projects = config.join("projects/-work-a");
    std::fs::create_dir_all(&projects).unwrap();
    let file = projects.join("S1.jsonl");
    let transcript = |id: &str, text: &str| {
        format!(
            "{{\"type\":\"user\",\"uuid\":\"{id}\",\"message\":{{\"role\":\"user\",\"content\":\"{text}\"}}}}\n"
        )
    };
    std::fs::write(&file, transcript("old", "old content")).unwrap();
    let agent = hc.run_fake_agent(
        "claude",
        &format!("claude-session {} S1 /work/a\nwait\n", config.display()),
    );
    let mut vars: HashMap<_, _> = hc.env().into_iter().collect();
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
    let first = env
        .core
        .watch_chat(host.clone(), agent.address.clone(), None)
        .unwrap();
    let before = wait_for(&first, "old transcript", |s| {
        s.status == ChatStatus::Live && !s.entries.is_empty()
    })
    .await;
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if env
                .core
                .cached_chat(host.clone(), agent.address.clone())
                .await
                .unwrap()
                .is_some_and(|s| texts(&s) == ["user: old content"])
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    first.stop();
    std::fs::write(&file, transcript("new", "new content")).unwrap();
    let resumed = env
        .core
        .watch_chat(host, agent.address, before.cursor)
        .unwrap();
    let after = wait_for(&resumed, "rewritten transcript", |s| {
        s.status == ChatStatus::Live
    })
    .await;
    resumed.stop();
    assert_eq!(texts(&after), ["user: new content"]);
}
