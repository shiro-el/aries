//! Seam 1: the encrypted Chat cache. A Chat read once opens again without
//! the Host, resumes after its cursor when the Host is back, is sealed with
//! the device key, expires after the retention period and is deleted per
//! Host and with the Host.

mod common;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use aries_core::{
    AriesCore, ChatEntryBody, ChatSnapshot, ChatStatus, ChatWatch, CoreError, Endpoint, JumpHost,
    KeyProtection, KeyVault, VaultError,
};

const DAY: Duration = Duration::from_secs(24 * 60 * 60);

const FIRST: &str = concat!(
    r#"{"v":1,"type":"reset","agent":"claude-code","session":"s"}"#,
    "\n",
    r#"{"v":1,"type":"item","item":{"id":"u1","kind":"user","text":"the secret word is aubergine"}}"#,
    "\n",
    r#"{"v":1,"type":"item","item":{"id":"t1","kind":"tool","name":"Bash","status":"running"},"cursor":"cc:s:20"}"#,
    "\n",
    r#"{"v":1,"type":"state","state":"live"}"#,
    "\n",
);

/// What a Beacon sends after `cc:s:20`: the tool finishes (same id) and a
/// reply follows.
const AFTER: &str = concat!(
    r#"{"v":1,"type":"item","item":{"id":"t1","kind":"tool","name":"Bash","status":"error","output":"boom"},"cursor":"cc:s:30"}"#,
    "\n",
    r#"{"v":1,"type":"item","item":{"id":"a1","kind":"agent","text":"it failed"},"cursor":"cc:s:40"}"#,
    "\n",
    r#"{"v":1,"type":"state","state":"live"}"#,
    "\n",
);

struct Env {
    dir: tempfile::TempDir,
}

impl Env {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
        }
    }

    fn data(&self) -> PathBuf {
        self.dir.path().join("data")
    }

    fn core(&self) -> Arc<AriesCore> {
        common::core_in(&self.data())
    }

    /// A Local Host whose "Beacon" is the script in `beacon-<name>.sh`
    /// (rewritable with [`Env::beacon`]).
    fn host(&self, core: &AriesCore, name: &str) -> String {
        let path = self.script(name);
        self.beacon(name, "exit 1");
        core.add_host(
            name.into(),
            Endpoint::Local {
                argv: vec![],
                env: HashMap::from([("ARIES_BEACON".into(), path)]),
            },
        )
        .unwrap()
        .id
    }

    fn script(&self, name: &str) -> String {
        path(&self.dir.path().join(format!("beacon-{name}.sh")))
    }

    fn beacon(&self, name: &str, script: &str) {
        let path = self.script(name);
        std::fs::write(&path, format!("#!/bin/sh\n{script}\n")).unwrap();
        std::process::Command::new("chmod")
            .arg("+x")
            .arg(&path)
            .status()
            .unwrap();
    }

    /// Makes the Beacon log its arguments, then print `text` and stay.
    fn beacon_printing(&self, name: &str, text: &str) -> PathBuf {
        let lines = self.dir.path().join(format!("lines-{name}"));
        let log = self.dir.path().join(format!("args-{name}"));
        std::fs::write(&lines, text).unwrap();
        let _ = std::fs::remove_file(&log);
        self.beacon(
            name,
            &format!(
                "echo \"$*\" >> {}; cat {}; sleep 30",
                log.display(),
                lines.display()
            ),
        );
        log
    }

    /// Reads `FIRST` once through `core`, so the Chat is cached.
    async fn cache_first(&self, core: &AriesCore, name: &str, host: &str) {
        self.beacon_printing(name, FIRST);
        let watch = watch(core, host);
        wait_for(&watch, "first", |s| s.status == ChatStatus::Live).await;
        watch.stop();
        cached(core, host, "first cached", |s| s.entries.len() == 2).await;
    }

    /// Every byte the core keeps on disk.
    fn at_rest(&self) -> Vec<u8> {
        let mut all = Vec::new();
        read_tree(&self.data(), &mut all);
        all
    }
}

fn read_tree(dir: &Path, out: &mut Vec<u8>) {
    for e in std::fs::read_dir(dir).unwrap() {
        let p = e.unwrap().path();
        if p.is_dir() {
            read_tree(&p, out);
        } else {
            out.extend(std::fs::read(&p).unwrap());
        }
    }
}

fn contains(hay: &[u8], needle: &str) -> bool {
    hay.windows(needle.len()).any(|w| w == needle.as_bytes())
}

fn path(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

fn watch(core: &AriesCore, host: &str) -> Arc<ChatWatch> {
    core.watch_chat_retrying(host.into(), "t1".into(), None, Duration::from_millis(50))
        .unwrap()
}

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

/// Waits until the cache holds a Chat matching `ok` (saving is async).
async fn cached(
    core: &AriesCore,
    host: &str,
    what: &str,
    ok: impl Fn(&ChatSnapshot) -> bool,
) -> ChatSnapshot {
    for _ in 0..100 {
        if let Some(s) = core.cached_chat(host.into(), "t1".into()).await.unwrap()
            && ok(&s)
        {
            return s;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("cache never held {what}")
}

fn texts(s: &ChatSnapshot) -> Vec<String> {
    s.entries
        .iter()
        .map(|e| match &e.body {
            ChatEntryBody::User { text, .. } => format!("user: {text}"),
            ChatEntryBody::Agent { text, .. } => format!("agent: {text}"),
            ChatEntryBody::Tool { name, status, .. } => format!("tool {name} {status:?}"),
            other => format!("{other:?}"),
        })
        .collect()
}

#[tokio::test]
async fn a_cached_chat_opens_while_the_host_is_unreachable() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    env.cache_first(&core, "h", &host).await;
    drop(core);

    // The app restarts; the Host's Beacon cannot be reached.
    let core = env.core();
    env.beacon("h", "exit 1");
    let watch = watch(&core, &host);
    let s = wait_for(&watch, "cached chat", |s| !s.entries.is_empty()).await;
    assert_eq!(
        texts(&s),
        ["user: the secret word is aubergine", "tool Bash Running"]
    );
    wait_for(&watch, "reconnecting", |s| {
        s.status == ChatStatus::Reconnecting && s.entries.len() == 2
    })
    .await;
    watch.stop();

    let s = core
        .cached_chat(host.clone(), "t1".into())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(s.cursor.as_deref(), Some("cc:s:20"));
    assert_eq!(s.entries.len(), 2);
    assert!(
        (core.cached_chat(host, "other".into()).await.unwrap()).is_none(),
        "another Terminal has no cache"
    );
}

#[tokio::test]
async fn reconnecting_resumes_after_the_cached_cursor_without_gaps_or_repeats() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    env.cache_first(&core, "h", &host).await;
    drop(core);

    let core = env.core();
    let log = env.beacon_printing("h", AFTER);
    let watch = watch(&core, &host);
    let s = wait_for(&watch, "reply", |s| s.entries.len() == 3).await;
    assert_eq!(
        texts(&s),
        [
            "user: the secret word is aubergine",
            "tool Bash Failed",
            "agent: it failed"
        ]
    );
    assert_eq!(s.cursor.as_deref(), Some("cc:s:40"));
    let args = std::fs::read_to_string(&log).unwrap();
    assert_eq!(
        args.lines().collect::<Vec<_>>(),
        ["chat t1 --follow --cursor cc:s:20"]
    );
    watch.stop();
    // The cache moved on with it.
    cached(&core, &host, "resumed chat", |s| {
        s.cursor.as_deref() == Some("cc:s:40") && s.entries.len() == 3
    })
    .await;
}

#[tokio::test]
async fn the_cache_is_sealed_with_the_device_key() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    env.cache_first(&core, "h", &host).await;
    drop(core);

    let disk = env.at_rest();
    assert!(
        !contains(&disk, "aubergine"),
        "Chat text stored in the clear"
    );
    assert!(!contains(&disk, "cc:s:20"), "cursor stored in the clear");
    assert!(!contains(&disk, "Bash"), "tool name stored in the clear");

    // Without the device key (the Keystore key is gone) nothing opens and
    // the core starts a new, empty cache instead of failing.
    let core = AriesCore::new(path(&env.data()), Arc::new(ForgetfulVault)).unwrap();
    assert!((core.cached_chat(host.clone(), "t1".into()).await.unwrap()).is_none());
}

/// A vault that seals but never opens what an earlier vault sealed.
struct ForgetfulVault;

impl KeyVault for ForgetfulVault {
    fn seal(&self, plaintext: Vec<u8>, _: KeyProtection) -> Result<Vec<u8>, VaultError> {
        let mut out = b"forgetful:".to_vec();
        out.extend(plaintext);
        Ok(out)
    }

    fn open(&self, sealed: Vec<u8>, _: KeyProtection) -> Result<Vec<u8>, VaultError> {
        sealed
            .strip_prefix(b"forgetful:")
            .map(<[u8]>::to_vec)
            .ok_or(VaultError::Failed {
                detail: "key gone".into(),
            })
    }
}

#[tokio::test]
async fn chats_older_than_the_retention_period_are_deleted() {
    let env = Env::new();
    let core = env.core();
    assert_eq!(core.chat_cache_retention_days(), 30);
    let host = env.host(&core, "h");
    env.cache_first(&core, "h", &host).await;

    core.shift_clock(29 * DAY);
    assert!(
        core.cached_chat(host.clone(), "t1".into())
            .await
            .unwrap()
            .is_some()
    );
    core.shift_clock(2 * DAY);
    assert!(
        (core.cached_chat(host.clone(), "t1".into()).await.unwrap()).is_none(),
        "a 31-day-old Chat is still cached"
    );

    // A shorter period applies at once, and survives a relaunch.
    env.cache_first(&core, "h", &host).await;
    core.shift_clock(8 * DAY);
    core.set_chat_cache_retention_days(7).unwrap();
    assert!((core.cached_chat(host.clone(), "t1".into()).await.unwrap()).is_none());
    drop(core);
    assert_eq!(env.core().chat_cache_retention_days(), 7);
}

#[tokio::test]
async fn clearing_one_hosts_cache_keeps_the_others() {
    let env = Env::new();
    let core = env.core();
    let a = env.host(&core, "a");
    let b = env.host(&core, "b");
    env.cache_first(&core, "a", &a).await;
    env.cache_first(&core, "b", &b).await;

    core.clear_chat_cache(a.clone()).await.unwrap();

    assert!(
        core.cached_chat(a.clone(), "t1".into())
            .await
            .unwrap()
            .is_none()
    );
    assert!(core.cached_chat(b, "t1".into()).await.unwrap().is_some());
    // Clearing an empty cache is fine.
    core.clear_chat_cache(a).await.unwrap();
}

#[tokio::test]
async fn removing_a_host_leaves_no_trace_of_its_cache() {
    let env = Env::new();
    let core = env.core();
    let gone = env.host(&core, "gone");
    let kept = env.host(&core, "kept");
    env.cache_first(&core, "gone", &gone).await;
    env.cache_first(&core, "kept", &kept).await;
    let before = env.at_rest();
    assert!(contains(&before, &gone));

    core.remove_host(gone.clone()).unwrap();

    assert!(
        !contains(&env.at_rest(), &gone),
        "the removed Host's id is still on disk"
    );
    drop(core);
    let core = env.core();
    assert!(core.cached_chat(kept, "t1".into()).await.unwrap().is_some());
}

#[tokio::test]
async fn removing_a_host_prevents_an_open_watch_from_recreating_its_cache() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    env.beacon_printing("h", FIRST);
    let watch = watch(&core, &host);
    wait_for(&watch, "live", |s| s.status == ChatStatus::Live).await;
    cached(&core, &host, "first cache", |s| s.entries.len() == 2).await;
    core.remove_host(host.clone()).unwrap();

    env.beacon_printing("h", AFTER);
    watch.reconnect();
    let closed = tokio::time::timeout(Duration::from_secs(1), async {
        loop {
            match watch.next().await {
                Err(CoreError::Closed) => break,
                Ok(_) => continue, // Drain a snapshot queued before removal.
                Err(error) => panic!("unexpected watch error: {error:?}"),
            }
        }
    })
    .await;
    assert!(closed.is_ok(), "a removed Host's Chat kept following");
    // Let any already-started blocking cache save finish.
    tokio::time::sleep(Duration::from_secs(2)).await;
    watch.stop();
    assert!(
        !contains(&env.at_rest(), &host),
        "a retained watch recreated the removed Host's cache"
    );
}

// The app's reconnect signals (HostLink, ticket 19): a network change
// replaces the Chat's line at once; coming back to the foreground retries a
// waiting reconnect at once. Both resume after the cursor.

#[tokio::test]
async fn a_network_change_replaces_the_line_and_resumes_after_the_cursor() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    let log = env.beacon_printing("h", FIRST);
    let watch = watch(&core, &host);
    wait_for(&watch, "first", |s| s.status == ChatStatus::Live).await;

    env.beacon_printing("h", AFTER);
    watch.reconnect();
    let s = wait_for(&watch, "resumed", |s| s.entries.len() == 3).await;
    assert_eq!(
        texts(&s),
        [
            "user: the secret word is aubergine",
            "tool Bash Failed",
            "agent: it failed"
        ]
    );
    let args = std::fs::read_to_string(&log).unwrap();
    assert_eq!(
        args.lines().collect::<Vec<_>>(),
        ["chat t1 --follow --cursor cc:s:20"]
    );
    watch.stop();
}

#[tokio::test]
async fn coming_back_retries_a_waiting_reconnect_at_once() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    // The first attempt fails; the next one would wait a minute.
    let watch = core
        .watch_chat_retrying(host.clone(), "t1".into(), None, Duration::from_secs(60))
        .unwrap();
    wait_for(&watch, "reconnecting", |s| {
        s.status == ChatStatus::Reconnecting
    })
    .await;

    env.beacon_printing("h", FIRST);
    watch.retry_now();
    let s = tokio::time::timeout(
        Duration::from_secs(10),
        wait_for(&watch, "live", |s| s.status == ChatStatus::Live),
    )
    .await
    .expect("retry_now did not retry");
    assert_eq!(s.entries.len(), 2);
    watch.stop();
}

#[tokio::test]
async fn a_cached_chat_keeps_its_images() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    env.beacon_printing(
        "h",
        concat!(
            r#"{"v":1,"type":"reset","agent":"claude-code","session":"s"}"#, "\n",
            r#"{"v":1,"type":"item","item":{"id":"u1","kind":"user","text":"see","images":[{"media_type":"image/png","data":"aGVsbG8=","bytes":5}]},"cursor":"cc:s:9"}"#, "\n",
            r#"{"v":1,"type":"state","state":"live"}"#, "\n",
        ),
    );
    let first = watch(&core, &host);
    wait_for(&first, "image", |s| s.status == ChatStatus::Live).await;
    first.stop();
    cached(&core, &host, "image chat", |s| s.entries.len() == 1).await;
    drop(core);
    assert!(
        !contains(&env.at_rest(), "aGVsbG8="),
        "image stored in the clear"
    );

    let core = env.core();
    env.beacon("h", "exit 1");
    let watch = watch(&core, &host);
    let s = wait_for(&watch, "cached image", |s| !s.entries.is_empty()).await;
    let ChatEntryBody::User { images, .. } = &s.entries[0].body else {
        panic!("{s:?}")
    };
    assert!(images[0].available);
    assert_eq!(
        watch.image(images[0].id.clone()).as_deref(),
        Some(&b"hello"[..])
    );
    watch.stop();
}

#[tokio::test]
async fn a_chat_image_cache_keeps_newest_images_within_its_size_cap() {
    use base64::{Engine, engine::general_purpose::STANDARD};
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "h");
    let image = STANDARD.encode(vec![7u8; 2 * 1024 * 1024]);
    let mut lines =
        String::from("{\"v\":1,\"type\":\"reset\",\"agent\":\"claude-code\",\"session\":\"s\"}\n");
    for i in 0..7 {
        lines.push_str(
            &serde_json::json!({"v":1,"type":"item", "item": {
            "id":format!("u{i}"), "kind":"user", "text":format!("message {i}"),
            "images":[{"media_type":"image/png","data":image,"bytes":2*1024*1024}]
        }, "cursor":format!("cc:s:{i}")})
            .to_string(),
        );
        lines.push('\n');
    }
    lines.push_str("{\"v\":1,\"type\":\"state\",\"state\":\"live\"}\n");
    env.beacon_printing("h", &lines);
    let first = watch(&core, &host);
    wait_for(&first, "all images", |s| s.status == ChatStatus::Live).await;
    cached(&core, &host, "all messages", |s| s.entries.len() == 7).await;
    first.stop();
    env.beacon("h", "exit 1");
    let reopened = watch(&core, &host);
    let snapshot = wait_for(&reopened, "cached messages", |s| s.entries.len() == 7).await;
    assert_eq!(snapshot.cursor.as_deref(), Some("cc:s:6"));
    let available: Vec<_> = snapshot
        .entries
        .iter()
        .map(|e| {
            let ChatEntryBody::User { text, images, .. } = &e.body else {
                panic!("user entry")
            };
            assert!(text.starts_with("message "));
            images[0].available
        })
        .collect();
    // Each 2 MiB image occupies 2,796,204 base64 bytes; newest five fit
    // inside the 16 MiB persisted image budget. Text/metadata all survive.
    assert_eq!(available, [false, false, true, true, true, true, true]);
    reopened.stop();
}

#[tokio::test]
async fn pausing_chat_stops_host_reads_and_resumes_the_same_transcript_after_its_cursor() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "pause");
    let args = env.beacon_printing("pause", FIRST);
    let watch = watch(&core, &host);
    wait_for(&watch, "first", |s| s.status == ChatStatus::Live).await;
    watch.pause();
    // A pending snapshot can still be delivered. The Host must not be
    // reopened even when a network-change signal arrives while paused.
    tokio::time::sleep(Duration::from_millis(150)).await;
    watch.reconnect();
    watch.retry_now();
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(std::fs::read_to_string(&args).unwrap().lines().count(), 1);
    std::fs::write(env.dir.path().join("lines-pause"), AFTER).unwrap();
    watch.resume();
    let resumed = wait_for(&watch, "resumed", |s| s.entries.len() == 3).await;
    assert_eq!(
        resumed.entries[0].id, "u1",
        "old transcript survived the pause"
    );
    assert_eq!(resumed.cursor.as_deref(), Some("cc:s:40"));
    assert!(
        std::fs::read_to_string(args)
            .unwrap()
            .contains("--cursor cc:s:20")
    );
    watch.stop();
}

#[tokio::test]
async fn connection_setting_edits_stop_chat_watches_without_erasing_cached_chat() {
    for change in ["endpoint", "jump", "key"] {
        let env = Env::new();
        let core = env.core();
        let host = env.host(&core, "h");
        env.cache_first(&core, "h", &host).await;
        env.beacon_printing("h", FIRST);
        let watch = watch(&core, &host);
        wait_for(&watch, "live", |s| s.status == ChatStatus::Live).await;
        match change {
            "endpoint" => {
                core.edit_host(host.clone(), "h".into(), common::script_endpoint("exit 1"))
                    .await
                    .unwrap();
            }
            "jump" => core
                .set_jump_hosts(
                    host.clone(),
                    vec![JumpHost {
                        address: "jump".into(),
                        port: 22,
                        user: "me".into(),
                        key_id: None,
                        host_key: None,
                    }],
                )
                .unwrap(),
            "key" => {
                let key = core.create_key("new key".into()).unwrap();
                core.set_host_ssh_key(host.clone(), Some(key.id)).unwrap();
            }
            _ => unreachable!(),
        }
        watch.reconnect();
        let closed = tokio::time::timeout(Duration::from_secs(1), async {
            while watch.next().await.is_ok() {}
        })
        .await;
        assert!(closed.is_ok(), "{change}: the old Chat kept following");
        assert!(core.cached_chat(host, "t1".into()).await.unwrap().is_some());
    }
}

/// Storage companion to ChatProfileActivity's 300-history / 200-tail workload.
/// Run explicitly with --ignored --nocapture; numbers are observations, never gates.
#[tokio::test]
#[ignore = "observational Chat storage workload"]
async fn measure_chat_storage() {
    use std::time::Instant;
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "profile");
    let mut script = String::from(
        "cat <<'CHAT'\n{\"v\":1,\"type\":\"reset\",\"agent\":\"claude-code\",\"session\":\"profile\"}\n",
    );
    let prompt = "Check `src/change.kt`, preserve the existing behavior, and explain the tests before editing.\n\n".repeat(1000);
    for i in 0..300 {
        let text = if i % 10 == 0 {
            prompt.clone()
        } else {
            "A paragraph with **emphasis**, `code`, and a [link](https://example.org). ".repeat(20)
        };
        script.push_str(&serde_json::json!({"v":1,"type":"item","item":{"id":format!("entry-{i}"),"kind":"agent","text":text}}).to_string());
        script.push('\n');
    }
    script.push_str("{\"v\":1,\"type\":\"state\",\"state\":\"live\"}\nCHAT\n");
    for step in 0..200 {
        let text = format!(
            "Streaming response\n\n{}",
            (0..=step)
                .map(|i| format!("token{i}"))
                .collect::<Vec<_>>()
                .join(" ")
        );
        let line = serde_json::json!({"v":1,"type":"item","item":{"id":"stream","kind":"agent","text":text},"cursor":format!("profile:{step}")});
        script.push_str(&format!("sleep 0.1\ncat <<'CHAT'\n{line}\nCHAT\n"));
    }
    script.push_str("sleep 30\n");
    env.beacon("profile", &script);
    let io_before = std::fs::read_to_string("/proc/self/io").unwrap();
    let began = Instant::now();
    let watch = watch(&core, &host);
    let mut received = 0;
    let mut receive_us = 0u128;
    let deltas = std::env::var_os("ARIES_CHAT_PROFILE_DELTAS").is_some();
    let mut body_bytes = 0usize;
    loop {
        let start = Instant::now();
        let (cursor, entries) = if deltas {
            let update = tokio::time::timeout(Duration::from_secs(30), watch.next_update())
                .await
                .unwrap()
                .unwrap();
            (update.cursor, update.upserts)
        } else {
            let snapshot = tokio::time::timeout(Duration::from_secs(30), watch.next())
                .await
                .unwrap()
                .unwrap();
            (snapshot.cursor, snapshot.entries)
        };
        body_bytes += entries
            .iter()
            .map(|e| match &e.body {
                ChatEntryBody::Agent { text, .. } | ChatEntryBody::User { text, .. } => text.len(),
                _ => 0,
            })
            .sum::<usize>();
        // Includes waiting for the source, intentionally reported separately from wall time.
        receive_us += start.elapsed().as_micros();
        received += 1;
        if cursor.as_deref() == Some("profile:199") {
            break;
        }
    }
    cached(&core, &host, "profile persisted", |s| {
        s.cursor.as_deref() == Some("profile:199")
    })
    .await;
    watch.stop();
    println!(
        "CHAT_STORAGE elapsed_ms={} snapshots={} next_wait_us={} delivered_text_bytes={} deltas={} db_bytes={}\nIO_BEFORE\n{}IO_AFTER\n{}RSS\n{}",
        began.elapsed().as_millis(),
        received,
        receive_us,
        body_bytes,
        deltas,
        std::fs::metadata(env.data().join("chat-cache.sqlite"))
            .unwrap()
            .len(),
        io_before,
        std::fs::read_to_string("/proc/self/io").unwrap(),
        std::fs::read_to_string("/proc/self/status")
            .unwrap()
            .lines()
            .filter(|l| l.starts_with("VmHWM:") || l.starts_with("VmRSS:"))
            .collect::<Vec<_>>()
            .join("\n")
    );
}

#[tokio::test]
async fn idle_heartbeat_neither_publishes_nor_rewrites_the_chat() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "idle");
    let lines = env.dir.path().join("initial");
    std::fs::write(&lines, FIRST).unwrap();
    env.beacon(
        "idle",
        &format!(
            "cat {}; sleep 2; echo '{{\"v\":1,\"type\":\"heartbeat\"}}'; sleep 30",
            lines.display()
        ),
    );
    let watch = watch(&core, &host);
    wait_for(&watch, "live", |s| s.status == ChatStatus::Live).await;
    cached(&core, &host, "initial stored", |s| s.entries.len() == 2).await;
    let db = rusqlite::Connection::open(env.data().join("chat-cache.sqlite")).unwrap();
    let before: i64 = db
        .query_row("PRAGMA data_version", [], |r| r.get(0))
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(2400), watch.next())
            .await
            .is_err(),
        "heartbeat must only advance liveness"
    );
    let after: i64 = db
        .query_row("PRAGMA data_version", [], |r| r.get(0))
        .unwrap();
    assert_eq!(before, after, "idle Chat must not be saved again");
    watch.stop();
}

#[tokio::test]
async fn incremental_history_edits_and_reset_restore_in_order_after_restart() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "pages");
    let mut initial =
        String::from("{\"v\":1,\"type\":\"reset\",\"agent\":\"claude-code\",\"session\":\"s\"}\n");
    for i in 0..300 {
        initial.push_str(&serde_json::json!({"v":1,"type":"item","item":{"id":format!("a{i}"),"kind":"agent","text":format!("history {i}")},"cursor":format!("s:{i}")}).to_string());
        initial.push('\n');
    }
    initial.push_str("{\"v\":1,\"type\":\"state\",\"state\":\"live\"}\n");
    env.beacon_printing("pages", &initial);
    let live = watch(&core, &host);
    wait_for(&live, "history", |s| {
        s.status == ChatStatus::Live && s.entries.len() == 300
    })
    .await;
    cached(&core, &host, "history stored", |s| s.entries.len() == 300).await;
    live.stop();
    drop(core);

    let core = env.core();
    // Edit early history, remove another entry, then reinsert it at the tail.
    let edits = concat!(
        "{\"v\":1,\"type\":\"item\",\"item\":{\"id\":\"a2\",\"kind\":\"agent\",\"text\":\"corrected\"}}\n",
        "{\"v\":1,\"type\":\"remove\",\"id\":\"a5\"}\n",
        "{\"v\":1,\"type\":\"remove\",\"id\":\"a10\"}\n",
        "{\"v\":1,\"type\":\"item\",\"item\":{\"id\":\"a5\",\"kind\":\"agent\",\"text\":\"returned\"},\"cursor\":\"s:done\"}\n",
        "{\"v\":1,\"type\":\"state\",\"state\":\"live\"}\n"
    );
    env.beacon_printing("pages", edits);
    let live = watch(&core, &host);
    wait_for(&live, "edits", |s| s.cursor.as_deref() == Some("s:done")).await;
    let expected = cached(&core, &host, "edits stored", |s| {
        s.cursor.as_deref() == Some("s:done")
    })
    .await;
    assert_eq!(expected.entries.len(), 299);
    assert_eq!(texts(&expected)[2], "agent: corrected");
    assert_eq!(expected.entries.last().unwrap().id, "a5");
    assert!(!expected.entries.iter().any(|e| e.id == "a10"));
    live.stop();
    drop(core);

    let core = env.core();
    let restored = core
        .cached_chat(host.clone(), "t1".into())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(restored.entries, expected.entries);
    assert_eq!(restored.cursor.as_deref(), Some("s:done"));
    env.beacon_printing("pages", FIRST);
    let live = watch(&core, &host);
    wait_for(&live, "new session", |s| {
        s.cursor.as_deref() == Some("cc:s:20")
    })
    .await;
    cached(&core, &host, "reset stored", |s| {
        s.cursor.as_deref() == Some("cc:s:20")
    })
    .await;
    live.stop();
    drop(core);
    let restored = env
        .core()
        .cached_chat(host, "t1".into())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        texts(&restored),
        ["user: the secret word is aubergine", "tool Bash Running"]
    );
}

#[tokio::test]
async fn slow_update_consumer_keeps_all_edits_removals_and_session_resets() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "delta");
    let first = env.dir.path().join("first");
    std::fs::write(&first, FIRST.replace("{\"v\":1,\"type\":\"state\"", "{\"v\":1,\"type\":\"item\",\"item\":{\"id\":\"stable\",\"kind\":\"agent\",\"text\":\"unchanged history\"}}\n{\"v\":1,\"type\":\"state\"")).unwrap();
    let edits = env.dir.path().join("edits");
    std::fs::write(&edits, concat!(
        "{\"v\":1,\"type\":\"item\",\"item\":{\"id\":\"u1\",\"kind\":\"user\",\"text\":\"changed user\"}}\n",
        "{\"v\":1,\"type\":\"item\",\"item\":{\"id\":\"a1\",\"kind\":\"agent\",\"text\":\"first reply\"}}\n",
        "{\"v\":1,\"type\":\"remove\",\"id\":\"t1\",\"cursor\":\"delta:done\"}\n"
    )).unwrap();
    let go = env.dir.path().join("go");
    let reset = env.dir.path().join("reset");
    env.beacon("delta", &format!("cat {}; while [ ! -f {} ]; do sleep 0.02; done; cat {}; while [ ! -f {} ]; do sleep 0.02; done; cat {}; sleep 30", first.display(), go.display(), edits.display(), reset.display(), first.display()));
    let live = watch(&core, &host);
    loop {
        let update = live.next_update().await.unwrap();
        if update.status == ChatStatus::Live {
            assert_eq!(update.order, ["u1", "t1", "stable"]);
            break;
        }
    }
    std::fs::write(go, "go").unwrap();
    // Intentionally do not consume while different IDs change and one is removed.
    cached(&core, &host, "all edits applied", |s| {
        s.cursor.as_deref() == Some("delta:done")
    })
    .await;
    let update = live.next_update().await.unwrap();
    assert!(!update.reset);
    assert_eq!(update.order, ["u1", "stable", "a1"]);
    assert_eq!(update.upserts.len(), 2);
    assert!(
        update
            .upserts
            .iter()
            .any(|e| matches!(&e.body, ChatEntryBody::User { text, .. } if text == "changed user"))
    );
    assert!(
        update
            .upserts
            .iter()
            .any(|e| matches!(&e.body, ChatEntryBody::Agent { text, .. } if text == "first reply"))
    );
    std::fs::write(reset, "go").unwrap();
    cached(&core, &host, "session replaced", |s| {
        s.cursor.as_deref() == Some("cc:s:20")
    })
    .await;
    let update = live.next_update().await.unwrap();
    assert!(update.reset);
    assert_eq!(update.order, ["u1", "t1", "stable"]);
    assert_eq!(update.upserts.len(), 3);
    live.stop();
}

#[tokio::test]
async fn an_active_watch_recovers_its_history_when_the_cached_base_is_cleared() {
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "clearbase");
    let initial = env.dir.path().join("initial");
    let after = env.dir.path().join("after");
    let go = env.dir.path().join("go");
    std::fs::write(&initial, FIRST).unwrap();
    std::fs::write(&after, AFTER).unwrap();
    env.beacon(
        "clearbase",
        &format!(
            "cat {}; while [ ! -f {} ]; do sleep 0.02; done; cat {}; sleep 30",
            initial.display(),
            go.display(),
            after.display()
        ),
    );
    let live = watch(&core, &host);
    wait_for(&live, "initial live Chat", |s| s.status == ChatStatus::Live).await;
    cached(&core, &host, "base saved", |s| s.entries.len() == 2).await;
    core.clear_chat_cache(host.clone()).await.unwrap();
    assert!(
        core.cached_chat(host.clone(), "t1".into())
            .await
            .unwrap()
            .is_none()
    );
    std::fs::write(go, "go").unwrap();
    wait_for(&live, "continued", |s| {
        s.cursor.as_deref() == Some("cc:s:40")
    })
    .await;
    cached(&core, &host, "full history recovered", |s| {
        s.cursor.as_deref() == Some("cc:s:40")
    })
    .await;
    live.stop();
    drop(core);
    let restored = env
        .core()
        .cached_chat(host, "t1".into())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        texts(&restored),
        [
            "user: the secret word is aubergine",
            "tool Bash Failed",
            "agent: it failed"
        ]
    );
}

/// Import fixture: the previous release's single encrypted JSON row. Encryption
/// here creates the old on-disk input; assertions use only the public cache API.
#[tokio::test]
async fn a_legacy_blob_migrates_without_losing_history_cursor_or_retention() {
    use ring::{aead, hkdf};
    let env = Env::new();
    let core = env.core();
    let host = env.host(&core, "legacy");
    env.cache_first(&core, "legacy", &host).await;
    drop(core);
    let db = rusqlite::Connection::open(env.data().join("chat-cache.sqlite")).unwrap();
    let id: Vec<u8> = db
        .query_row("SELECT chat FROM chats", [], |r| r.get(0))
        .unwrap();
    let master = common::TestVault
        .open(
            std::fs::read(env.data().join("chat-cache.key")).unwrap(),
            KeyProtection::Plain,
        )
        .unwrap();
    let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, b"aries chat cache v1").extract(&master);
    let key: aead::UnboundKey = prk
        .expand(&[b"seal"], &aead::CHACHA20_POLY1305)
        .unwrap()
        .into();
    let nonce = [7; aead::NONCE_LEN];
    let aad = [host.as_bytes(), &[0], id.as_slice()].concat();
    let mut plain = br#"{"items":[{"id":"u1","kind":"user","text":"the secret word is aubergine"},{"id":"t1","kind":"tool","name":"Bash","status":"running"}],"cursor":"cc:s:20"}"#.to_vec();
    aead::LessSafeKey::new(key)
        .seal_in_place_append_tag(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::from(aad),
            &mut plain,
        )
        .unwrap();
    let data = [nonce.as_slice(), plain.as_slice()].concat();
    db.execute("DELETE FROM chat_items", []).unwrap();
    db.execute(
        "UPDATE chats SET data=?1, updated=updated - 29 * 86400",
        [data],
    )
    .unwrap();
    drop(db);

    let core = env.core();
    let migrated = core
        .cached_chat(host.clone(), "t1".into())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        texts(&migrated),
        ["user: the secret word is aubergine", "tool Bash Running"]
    );
    assert_eq!(migrated.cursor.as_deref(), Some("cc:s:20"));
    // The same migrated records survive a second process launch too.
    drop(core);
    let core = env.core();
    let restored = core
        .cached_chat(host.clone(), "t1".into())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(restored.entries, migrated.entries);
    core.shift_clock(2 * DAY);
    assert!(core.cached_chat(host, "t1".into()).await.unwrap().is_none());
}
