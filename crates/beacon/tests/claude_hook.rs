use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixListener;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use aries_beacon::chat::{TerminalFacts, TerminalLookup};
use aries_beacon::claude_hook::{self as hook, Request};
use aries_beacon::claude_plugin as plugin;
use serde_json::{Value, json};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

// Tests exercising the public answer/chat seams must agree with their hook's
// runtime path. Set the environment only in a child, never in parallel tests.
fn isolated_runtime(test: &str) -> bool {
    const CHILD: &str = "ARIES_HOOK_TEST_CHILD";
    if std::env::var(CHILD).as_deref() == Ok(test) {
        return false;
    }
    let runtime = tempfile::tempdir().unwrap();
    let output = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", test, "--nocapture"])
        .env(CHILD, test)
        .env("XDG_RUNTIME_DIR", runtime.path())
        .output()
        .unwrap();
    assert!(output.status.success(), "{output:?}");
    assert!(
        std::fs::read_dir(runtime.path().join("aries-beacon/claude"))
            .unwrap()
            .next()
            .is_none(),
        "hook left runtime files behind"
    );
    true
}

struct Offline;
impl TerminalLookup for Offline {
    fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
        Err("offline".into())
    }
}
fn request(path: &Path) -> Request {
    Request {
        session_id: "session".into(),
        transcript_path: path.into(),
        cwd: "/work".into(),
        permission_mode: "default".into(),
        hook_event_name: "PermissionRequest".into(),
        tool_name: "AskUserQuestion".into(),
        tool_input: json!({"questions":[{"question":"Color?", "header":"Color", "multiSelect":false,"options":[{"label":"Blue","description":"blue"},{"label":"Red","description":"red"}]},{"question":"Other?","header":"Other","multiSelect":false,"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}),
    }
}
fn record(request: &Request) {
    std::fs::write(&request.transcript_path, format!("{}\n", json!({"type":"assistant", "uuid":"m1", "message":{"id":"m1", "role":"assistant", "content":[{"type":"tool_use","id":"tool1","name":request.tool_name,"input":request.tool_input}]}}))).unwrap();
}
fn ready(path: &Path) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !path.with_extension("json").exists() {
        assert!(Instant::now() < deadline);
        std::thread::sleep(Duration::from_millis(10));
    }
}

use aries_beacon::chat::{Agent, Located, answer, claude::ClaudeCode};
struct Claude {
    located: Located,
}
impl Agent for Claude {
    fn name(&self) -> &'static str {
        "claude-code"
    }
    fn locate(&self, _: &TerminalFacts) -> Option<Located> {
        Some(self.located.clone())
    }
    fn interpreter(&self) -> Box<dyn aries_beacon::chat::Interpreter> {
        ClaudeCode {
            config_dir: "/unused".into(),
        }
        .interpreter()
    }
    fn answer_keys(
        &self,
        q: &aries_protocol::chat::ChatQuestion,
        p: &[Vec<usize>],
    ) -> Result<Vec<String>, answer::KeysError> {
        ClaudeCode {
            config_dir: "/unused".into(),
        }
        .answer_keys(q, p)
    }
}

#[test]
fn mixed_answers_travel_through_the_hook_socket() {
    if isolated_runtime("mixed_answers_travel_through_the_hook_socket") {
        return;
    }
    let dir = tempfile::tempdir().unwrap();
    let terminal = format!("test-{:016x}", rand::random::<u64>());
    let path = hook::socket_path(dir.path(), hook::runtime_dir().as_deref(), &terminal).unwrap();
    let req = request(&dir.path().join("transcript"));
    let other = req.clone();
    let socket = path.clone();
    let term = terminal.clone();
    let resolved = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let done = resolved.clone();
    let worker = std::thread::spawn(move || {
        let output =
            hook::wait(&socket, &term, other, Duration::from_secs(5), &mut Offline).unwrap();
        done.store(true, std::sync::atomic::Ordering::SeqCst);
        output
    });
    ready(&path);
    let pending: hook::Pending =
        serde_json::from_slice(&std::fs::read(path.with_extension("json")).unwrap()).unwrap();
    struct Link(std::sync::Arc<std::sync::atomic::AtomicBool>);
    impl TerminalLookup for Link {
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            Ok(Some(TerminalFacts::from_json(&json!({"status":{
                "state": if self.0.load(std::sync::atomic::Ordering::SeqCst) { "working" } else { "blocked" }, "kind":"question"
            }}))))
        }
    }
    impl aries_beacon::chat::TerminalInput for Link {
        fn keys(&mut self, _: &str, _: &[String]) -> Result<(), String> {
            panic!("structured mixed answer must not send keys")
        }
    }
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(Claude {
        located: Located {
            session: req.session_id.clone(),
            path: req.transcript_path.clone(),
        },
    })];
    let result = answer::answer(
        dir.path(),
        &terminal,
        &aries_protocol::answer::Answer {
            question: pending.id,
            picks: vec![vec![0], vec![]],
            free_text: vec![None, Some("my own answer".into())],
        },
        &mut Link(resolved),
        &agents,
    )
    .unwrap();
    assert_eq!(
        result.outcome,
        aries_protocol::answer::AnswerOutcome::Sent,
        "{result:?}"
    );
    let output = worker.join().unwrap().unwrap();
    assert_eq!(
        output["hookSpecificOutput"]["decision"]["updatedInput"]["answers"],
        json!({"Color?":"Blue","Other?":"my own answer"})
    );
    assert_eq!(
        output["hookSpecificOutput"]["decision"]["updatedInput"]["questions"],
        req.tool_input["questions"]
    );
    assert!(!path.exists());
    assert!(!path.with_extension("json").exists());
}

#[test]
fn tui_result_and_timeout_clean_up_without_output() {
    for resolved in [true, false] {
        let dir = tempfile::tempdir().unwrap();
        let path = hook::socket_path(dir.path(), None, "t2").unwrap();
        let req = request(&dir.path().join("transcript"));
        let socket = path.clone();
        let other = req.clone();
        let worker = std::thread::spawn(move || {
            hook::wait(
                &socket,
                "t2",
                other,
                Duration::from_millis(400),
                &mut Offline,
            )
            .unwrap()
        });
        ready(&path);
        if resolved {
            record(&req);
            let mut file = std::fs::OpenOptions::new()
                .append(true)
                .open(req.transcript_path)
                .unwrap();
            writeln!(file,"{}",json!({"message":{"content":[{"type":"tool_result","tool_use_id":"tool1","content":"PC answered"}]}})).unwrap();
        }
        assert!(worker.join().unwrap().is_none());
        assert!(!path.exists());
        assert!(!path.with_extension("json").exists());
        assert_eq!(
            std::fs::read_dir(path.parent().unwrap()).unwrap().count(),
            0
        );
    }
}

/// A tool use can arrive on one poll and a result can be split across later
/// polls, including in the middle of a UTF-8 character.
#[test]
fn appended_resolution_survives_partial_lines_between_polls() {
    let dir = tempfile::tempdir().unwrap();
    let path = hook::socket_path(dir.path(), None, "partial").unwrap();
    let req = request(&dir.path().join("transcript"));
    std::fs::write(&req.transcript_path, "old history\n").unwrap();
    let (tick, ticks) = std::sync::mpsc::channel();
    let (done, finished) = std::sync::mpsc::channel();
    struct Poll(std::sync::mpsc::Sender<Instant>);
    impl TerminalLookup for Poll {
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            self.0.send(Instant::now()).unwrap();
            Ok(None)
        }
    }
    let socket = path.clone();
    let other = req.clone();
    let worker = std::thread::spawn(move || {
        let result = hook::wait(
            &socket,
            "partial",
            other,
            Duration::from_secs(5),
            &mut Poll(tick),
        )
        .unwrap();
        done.send(result).unwrap();
    });
    ready(&path);
    let next_poll = || ticks.recv_timeout(Duration::from_secs(2)).unwrap();
    let first = next_poll();
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(&req.transcript_path)
        .unwrap();
    writeln!(file, "{}", json!({"message":{"content":[{"type":"tool_use","id":"split","name":req.tool_name,"input":req.tool_input}]}})).unwrap();
    let second = next_poll();
    assert!(second.duration_since(first) >= Duration::from_millis(250));
    let result = format!(
        "{}\n",
        json!({"message":{"content":[{"type":"tool_result","tool_use_id":"split","content":"답"}]}})
    );
    let split = result.find('답').unwrap() + 1;
    file.write_all(&result.as_bytes()[..split]).unwrap();
    next_poll(); // The reader has encountered the incomplete result.
    assert!(matches!(
        finished.try_recv(),
        Err(std::sync::mpsc::TryRecvError::Empty)
    ));
    file.write_all(&result.as_bytes()[split..]).unwrap();
    assert!(
        finished
            .recv_timeout(Duration::from_secs(2))
            .unwrap()
            .is_none()
    );
    worker.join().unwrap();
    assert!(!path.exists());
    assert!(!path.with_extension("json").exists());
}

#[test]
fn outside_hc_does_not_even_read_stdin_or_create_state() {
    let dir = tempfile::tempdir().unwrap();
    let out = Command::new(BEACON)
        .args(["claude-hook", "permission-request"])
        .env_remove("HC_TERMINAL_ID")
        .env("XDG_STATE_HOME", dir.path())
        .env("XDG_RUNTIME_DIR", dir.path())
        .stdin(Stdio::piped())
        .output()
        .unwrap();
    assert!(out.status.success());
    assert!(out.stdout.is_empty());
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
}

#[test]
fn edited_plugin_files_and_symlinks_survive_install_and_remove() {
    for name in [
        "plugins/aries/hooks/hooks.json",
        "plugins/aries/.claude-plugin/plugin.json",
        ".claude-plugin/marketplace.json",
    ] {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("marketplace");
        let files = plugin::files(Path::new("/beacon"));
        assert_eq!(plugin::write_files(&root, &files).unwrap(), "installed");
        assert_eq!(plugin::write_files(&root, &files).unwrap(), "unchanged");
        std::fs::write(root.join(name), "user contents").unwrap();
        assert_eq!(
            plugin::write_files(&root, &files).unwrap(),
            "kept_user_copy"
        );
        assert_eq!(plugin::remove_files(&root).unwrap(), "kept_user_copy");
        assert_eq!(
            std::fs::read_to_string(root.join(name)).unwrap(),
            "user contents"
        );
    }
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("marketplace");
    let files = plugin::files(Path::new("/beacon"));
    plugin::write_files(&root, &files).unwrap();
    let outside = dir.path().join("outside");
    std::fs::create_dir(&outside).unwrap();
    std::fs::remove_dir_all(root.join("plugins/aries/hooks")).unwrap();
    std::os::unix::fs::symlink(&outside, root.join("plugins/aries/hooks")).unwrap();
    assert_eq!(
        plugin::write_files(&root, &files).unwrap(),
        "kept_user_copy"
    );
    assert_eq!(plugin::remove_files(&root).unwrap(), "kept_user_copy");
    assert_eq!(std::fs::read_dir(&outside).unwrap().count(), 0);
}

#[test]
fn binary_free_removal_keeps_edits_and_removes_only_managed_files() {
    for edited in [false, true] {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("aries-beacon/claude-marketplace");
        plugin::write_files(&root, &plugin::files(Path::new("/beacon"))).unwrap();
        if edited {
            std::fs::write(root.join("plugins/aries/hooks/hooks.json"), "edit").unwrap();
        }
        std::fs::write(root.join("user-file"), "keep").unwrap();
        let output = Command::new("sh")
            .args(["-c", include_str!("../../core/src/claude_plugin_remove.sh")])
            .env("XDG_DATA_HOME", dir.path())
            .env("PATH", "/usr/bin:/bin")
            .output()
            .unwrap();
        assert!(output.status.success(), "{output:?}");
        assert_eq!(root.join(plugin::MARKER).exists(), edited);
        assert!(root.join("user-file").exists());
    }
}

#[test]
fn unconfirmed_socket_answer_falls_back_to_keys() {
    if isolated_runtime("unconfirmed_socket_answer_falls_back_to_keys") {
        return;
    }
    // Exercise the public answer seam with the actual Claude transcript interpreter.
    use aries_beacon::chat::TerminalInput;
    use aries_protocol::answer::{Answer, AnswerOutcome};
    #[derive(Default)]
    struct Link {
        keys: Vec<String>,
    }
    impl TerminalLookup for Link {
        fn screen(&mut self, _: &str) -> Result<String, String> {
            Ok("Color?\n1. Blue\n2. Red".into())
        }
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            Ok(Some(TerminalFacts::from_json(
                &json!({"status":{"state":"blocked","kind":"question"}}),
            )))
        }
    }
    impl TerminalInput for Link {
        fn keys(&mut self, _: &str, keys: &[String]) -> Result<(), String> {
            self.keys.extend_from_slice(keys);
            Ok(())
        }
    }
    let dir = tempfile::tempdir().unwrap();
    let req = request(&dir.path().join("transcript"));
    std::fs::write(&req.transcript_path, "").unwrap();
    let terminal = format!("test-{:016x}", rand::random::<u64>());
    let path = hook::socket_path(dir.path(), hook::runtime_dir().as_deref(), &terminal).unwrap();
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let listener = UnixListener::bind(&path).unwrap();
    let pending = hook::Pending {
        request: req.clone(),
        id: "claude-hook:0123456789abcdef".into(),
        transcript_offset: 0,
        created_at: 0,
        nonce: "test".into(),
    };
    std::fs::write(
        path.with_extension("json"),
        serde_json::to_vec(&pending).unwrap(),
    )
    .unwrap();
    let socket_path = path.clone();
    let worker = std::thread::spawn(move || {
        let (socket, _) = listener.accept().unwrap();
        let mut line = String::new();
        BufReader::new(socket).read_line(&mut line).unwrap();
        std::fs::remove_file(&socket_path).unwrap();
        std::fs::remove_file(socket_path.with_extension("json")).unwrap();
        serde_json::from_str::<Value>(&line).unwrap()
    });
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(Claude {
        located: Located {
            session: "session".into(),
            path: req.transcript_path.clone(),
        },
    })];
    let mut link = Link::default();
    let result = answer::answer(
        dir.path(),
        &terminal,
        &Answer {
            question: pending.id.clone(),
            picks: vec![vec![0], vec![1]],
            free_text: vec![],
        },
        &mut link,
        &agents,
    )
    .unwrap();
    assert_eq!(result.outcome, AnswerOutcome::Sent, "{result:?}");
    assert_eq!(
        worker.join().unwrap()["answer"],
        json!({"Color?":"Blue","Other?":"B"})
    );
    assert_eq!(link.keys, ["1", "2", "1"]);
    assert!(!path.exists());
    assert!(!path.with_extension("json").exists());
    // A cached transcript id must retain its key path even while a hook
    // overlay would hide that tool from a new Chat snapshot.
    let listener = UnixListener::bind(&path).unwrap();
    listener.set_nonblocking(true).unwrap();
    record(&req);
    std::fs::write(
        path.with_extension("json"),
        serde_json::to_vec(&pending).unwrap(),
    )
    .unwrap();
    link.keys.clear();
    let result = answer::answer(
        dir.path(),
        &terminal,
        &Answer {
            question: "tool1".into(),
            picks: vec![vec![0], vec![1]],
            free_text: vec![],
        },
        &mut link,
        &agents,
    )
    .unwrap();
    assert_eq!(result.outcome, AnswerOutcome::Sent);
    assert_eq!(link.keys, ["1", "2", "1"]);
    assert_eq!(
        listener.accept().unwrap_err().kind(),
        std::io::ErrorKind::WouldBlock
    );
    std::fs::remove_file(&path).unwrap();
    std::fs::remove_file(path.with_extension("json")).unwrap();
}

#[test]
fn hook_and_late_transcript_answers_share_one_claim() {
    if isolated_runtime("hook_and_late_transcript_answers_share_one_claim") {
        return;
    }
    // Exercise the public answer seam with the actual Claude transcript interpreter.
    use aries_beacon::chat::TerminalInput;
    use aries_protocol::answer::{Answer, AnswerOutcome};
    #[derive(Default)]
    struct Link {
        keys: Vec<String>,
    }
    impl TerminalLookup for Link {
        fn screen(&mut self, _: &str) -> Result<String, String> {
            Ok("Color?\n1. Blue\n2. Red".into())
        }
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            Ok(Some(TerminalFacts::from_json(
                &json!({"status":{"state":"blocked","kind":"question"}}),
            )))
        }
    }
    impl TerminalInput for Link {
        fn keys(&mut self, _: &str, keys: &[String]) -> Result<(), String> {
            self.keys.extend_from_slice(keys);
            Ok(())
        }
    }
    let dir = tempfile::tempdir().unwrap();
    let req = request(&dir.path().join("transcript"));
    std::fs::write(&req.transcript_path, "").unwrap();
    let terminal = format!("test-{:016x}", rand::random::<u64>());
    let path = hook::socket_path(dir.path(), hook::runtime_dir().as_deref(), &terminal).unwrap();
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let listener = UnixListener::bind(&path).unwrap();
    let pending = hook::Pending {
        request: req.clone(),
        id: "claude-hook:0123456789abcdef".into(),
        transcript_offset: 0,
        created_at: 0,
        nonce: "test".into(),
    };
    std::fs::write(
        path.with_extension("json"),
        serde_json::to_vec(&pending).unwrap(),
    )
    .unwrap();
    let socket_path = path.clone();
    let worker = std::thread::spawn(move || {
        let (socket, _) = listener.accept().unwrap();
        let mut line = String::new();
        BufReader::new(socket).read_line(&mut line).unwrap();
        std::fs::remove_file(&socket_path).unwrap();
        std::fs::remove_file(socket_path.with_extension("json")).unwrap();
        serde_json::from_str::<Value>(&line).unwrap()
    });
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(Claude {
        located: Located {
            session: "session".into(),
            path: req.transcript_path.clone(),
        },
    })];
    let mut link = Link::default();
    let result = answer::answer(
        dir.path(),
        &terminal,
        &Answer {
            question: pending.id.clone(),
            picks: vec![vec![0], vec![1]],
            free_text: vec![],
        },
        &mut link,
        &agents,
    )
    .unwrap();
    assert_eq!(result.outcome, AnswerOutcome::Sent, "{result:?}");
    assert_eq!(
        worker.join().unwrap()["answer"],
        json!({"Color?":"Blue","Other?":"B"})
    );
    assert_eq!(link.keys, ["1", "2", "1"]);
    assert!(!path.exists());
    assert!(!path.with_extension("json").exists());
    record(&req);
    link.keys.clear();
    let result = answer::answer(
        dir.path(),
        &terminal,
        &Answer {
            question: "tool1".into(),
            picks: vec![vec![1], vec![0]],
            free_text: vec![],
        },
        &mut link,
        &agents,
    )
    .unwrap();
    assert_eq!(result.outcome, AnswerOutcome::Failed, "{result:?}");
    assert!(result.detail.unwrap().contains("already attempted"));
    assert!(link.keys.is_empty());
}

/// Isolated real TUI: the installed plugin, mixed answers, multi-select and Bash.
/// The only connection to real Claude config is the explicit credentials symlink.
#[test]
#[ignore = "runs real Claude Code haiku using an isolated config"]
fn real_claude_plugin_questions_and_bash() {
    if std::env::var("ARIES_REAL_CLAUDE").as_deref() != Ok("1") {
        return;
    }
    use aries_test_support::HcSession;
    let dir = tempfile::Builder::new()
        .prefix("aries-real-claude-")
        .tempdir_in("/tmp")
        .unwrap();
    let home = dir.path().join("home");
    let config = dir.path().join("config");
    let data = dir.path().join("data");
    let cwd = dir.path().join("work");
    for p in [&home, &config, &cwd] {
        std::fs::create_dir_all(p).unwrap();
    }
    let credentials = std::env::var_os("ARIES_CLAUDE_CREDENTIALS")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| aries_beacon::home().join(".claude/.credentials.json"));
    std::os::unix::fs::symlink(credentials, config.join(".credentials.json")).unwrap();
    let path = format!(
        "{}:{}",
        aries_test_support::herdr_core_bin()
            .parent()
            .unwrap()
            .display(),
        std::env::var("PATH").unwrap_or_default()
    );
    let setup = |args: &[&str]| {
        let output = Command::new(BEACON)
            .args(args)
            .env("HOME", &home)
            .env("CLAUDE_CONFIG_DIR", &config)
            .env("XDG_DATA_HOME", &data)
            .env("PATH", &path)
            .output()
            .unwrap();
        assert!(output.status.success(), "{output:?}");
        output
    };
    setup(&["claude-plugin", "install"]);
    setup(&["claude-plugin", "install"]); // CLI updates must also be repeatable.
    // Observe the actual plugin process output without changing the protocol.
    let log_dir = dir.path().join("hook-output");
    std::fs::create_dir(&log_dir).unwrap();
    let wrapper = dir.path().join("hook-beacon");
    std::fs::write(&wrapper, format!("#!/bin/sh\noutput=$(mktemp '{}/hook.XXXXXX')\n'{}' \"$@\" > \"$output\"\ncode=$?\ncat \"$output\"\nprintf '%s' \"$code\" > \"$output.done\"\nexit \"$code\"\n", log_dir.display(), BEACON)).unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&wrapper, std::fs::Permissions::from_mode(0o700)).unwrap();
    plugin::write_files(
        &data.join("aries-beacon/claude-marketplace"),
        &plugin::files(&wrapper),
    )
    .unwrap();
    // Onboarding and project trust belong only to this disposable config.
    let prefs = config.join(".claude.json");
    let mut value: Value = std::fs::read(&prefs)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or(json!({}));
    value["hasCompletedOnboarding"] = json!(true);
    value["theme"] = json!("dark");
    value["projects"][cwd.to_string_lossy().as_ref()]["hasTrustDialogAccepted"] = json!(true);
    std::fs::write(&prefs, serde_json::to_vec(&value).unwrap()).unwrap();
    let hc = HcSession::start_with_env(&[
        ("HOME", home.to_str().unwrap()),
        ("CLAUDE_CONFIG_DIR", config.to_str().unwrap()),
        ("XDG_DATA_HOME", data.to_str().unwrap()),
        ("PATH", &path),
        ("CLAUDECODE", ""),
    ]);
    let terminal = hc.run_bg(
        "real-claude",
        &[
            "sh",
            "-c",
            &format!(
                "cd '{}' && exec claude --model haiku --permission-mode default",
                cwd.display()
            ),
        ],
    );
    struct Kill<'a>(&'a HcSession, String);
    impl Drop for Kill<'_> {
        fn drop(&mut self) {
            let _ = self.0.command().args(["kill", &self.1]).output();
        }
    }
    let _kill = Kill(&hc, terminal.clone());
    let beacon = |args: &[&str]| {
        let out = Command::new(BEACON)
            .args(args)
            .envs(hc.env())
            .env("HOME", &home)
            .env("CLAUDE_CONFIG_DIR", &config)
            .env("PATH", &path)
            .output()
            .unwrap();
        assert!(out.status.success(), "{out:?}");
        String::from_utf8(out.stdout).unwrap()
    };
    let screen = || hc.hc(&["read", &terminal]);
    let wait_for = |test: &dyn Fn(&str) -> bool| {
        let deadline = Instant::now() + Duration::from_secs(45);
        loop {
            let text = screen();
            if test(&text) {
                break;
            }
            assert!(Instant::now() < deadline, "Claude did not start: {text}");
            // Dismiss only disposable setup prompts, never an agent permission.
            if text.contains("Choose the text style") || text.contains("Choose a theme") {
                hc.hc(&["keys", &terminal, "enter"]);
            }
            if text.contains("Yes, I trust this folder") {
                hc.hc(&["keys", &terminal, "enter"]);
            }
            std::thread::sleep(Duration::from_millis(300));
        }
    };
    wait_for(&|text| text.contains("for shortcuts") || text.contains("Try "));
    hc.hc(&["send",&terminal,"Use AskUserQuestion once with exactly two questions: 'Color?' with options Blue and Red, single select; and 'Other?' with options A and B, single select. Do not answer yourself. After I answer, say OK and stop."]);
    let env: std::collections::HashMap<_, _> = hc.env().into_iter().collect();
    let socket = hook::socket_path(
        dir.path(),
        Some(Path::new(&env["XDG_RUNTIME_DIR"])),
        &terminal,
    )
    .unwrap();
    let pending = |name: &str| {
        let deadline = Instant::now() + Duration::from_secs(120);
        loop {
            if let Ok(bytes) = std::fs::read(socket.with_extension("json"))
                && let Ok(p) = serde_json::from_slice::<hook::Pending>(&bytes)
                && p.request.tool_name == name
            {
                break p;
            }
            assert!(Instant::now() < deadline, "no {name} hook: {}", screen());
            std::thread::sleep(Duration::from_millis(100));
        }
    };
    let wait_completed = |p: &hook::Pending| {
        let deadline = Instant::now() + Duration::from_secs(15);
        while !hook::completed(p) {
            assert!(
                Instant::now() < deadline,
                "no transcript result: {}",
                screen()
            );
            std::thread::sleep(Duration::from_millis(50));
        }
    };
    let ask = pending("AskUserQuestion");
    let chat = beacon(&["chat", &terminal]);
    let items: Vec<Value> = chat
        .lines()
        .filter_map(|line| serde_json::from_str::<Value>(line).ok())
        .collect();
    assert_eq!(
        items
            .iter()
            .filter(|line| line["type"] == "item"
                && line["item"]["question"].is_object()
                && line["item"]["status"] == "running")
            .count(),
        1,
        "{chat}"
    );
    assert!(
        items.iter().any(|line| line["item"]["id"] == ask.id),
        "{chat}"
    );
    // A real answer must close the menu, not merely write to the socket.
    std::thread::sleep(Duration::from_secs(1));
    let reply = beacon(&["answer", &terminal, &ask.id, "0", "text:my custom answer"]);
    assert!(reply.contains("\"sent\""), "{reply}");
    wait_completed(&ask);
    let transcript = std::fs::read_to_string(&ask.request.transcript_path).unwrap();
    assert!(transcript.contains("my custom answer"), "{transcript}");
    // Wait for the model to finish before sending the next turn.
    let idle = || {
        let deadline = Instant::now() + Duration::from_secs(60);
        loop {
            let facts: Value = serde_json::from_str(&hc.hc(&["get", &terminal, "--json"])).unwrap();
            if matches!(facts["status"]["state"].as_str(), Some("idle" | "done")) {
                break;
            }
            assert!(Instant::now() < deadline, "not idle: {facts} {}", screen());
            std::thread::sleep(Duration::from_millis(100));
        }
    };
    idle();
    hc.hc(&["send",&terminal,"Use AskUserQuestion once: 'Toppings?' with options Cheese, Tomato, Olive, multiSelect true. After I answer say OK and stop."]);
    let multi = pending("AskUserQuestion");
    std::thread::sleep(Duration::from_secs(1));
    beacon(&["answer", &terminal, &multi.id, "0,2"]);
    wait_completed(&multi);
    let records: Vec<Value> = std::fs::read_to_string(&multi.request.transcript_path)
        .unwrap()
        .lines()
        .filter_map(|s| serde_json::from_str(s).ok())
        .collect();
    assert!(
        records
            .iter()
            .any(|r| r["toolUseResult"]["answers"]["Toppings?"] == "Cheese, Olive"),
        "{records:?}"
    );
    idle();
    hc.hc(&["send",&terminal,"Use Bash to execute exactly: printf hook-bash > permission-hook-proof.txt . Do not use Write or any other tool. Afterward say OK and stop."]);
    let bash = pending("Bash");
    std::thread::sleep(Duration::from_secs(1));
    let facts = aries_beacon::chat::TerminalFacts::from_json(
        &serde_json::from_str::<Value>(&hc.hc(&["get", &terminal, "--json"])).unwrap(),
    );
    let permission = aries_beacon::chat::permission::detect(
        "claude-code",
        &bash.request.session_id,
        &facts,
        &screen(),
    )
    .unwrap_or_else(|| panic!("Bash permission menu: facts={facts:?} screen={}", screen()));
    beacon(&["answer", &terminal, &permission.item.id, "0"]);
    wait_completed(&bash);
    let deadline = Instant::now() + Duration::from_secs(10);
    while !cwd.join("permission-hook-proof.txt").exists() {
        assert!(Instant::now() < deadline);
        std::thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(
        std::fs::read_to_string(cwd.join("permission-hook-proof.txt")).unwrap(),
        "hook-bash"
    );
    idle();
    hc.hc(&["send", &terminal, "Use AskUserQuestion once: 'PC choice?' with options Yes and No, single select. After I answer say OK and stop."]);
    let pc = pending("AskUserQuestion");
    std::thread::sleep(Duration::from_secs(1));
    hc.hc(&["keys", &terminal, "1", "enter"]);
    wait_completed(&pc);
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let done: Vec<_> = std::fs::read_dir(&log_dir)
            .unwrap()
            .map(Result::unwrap)
            .filter(|e| e.path().extension().is_some_and(|x| x == "done"))
            .collect();
        if done.len() == 4 && !socket.exists() && !socket.with_extension("json").exists() {
            let mut decisions = vec![];
            let mut empty = 0;
            for entry in done {
                assert_eq!(std::fs::read_to_string(entry.path()).unwrap(), "0");
                let output = std::fs::read_to_string(entry.path().with_extension("")).unwrap();
                if output.is_empty() {
                    empty += 1;
                } else {
                    decisions.push(serde_json::from_str::<Value>(&output).unwrap()["hookSpecificOutput"]["decision"].clone());
                }
            }
            assert_eq!(empty, 1, "TUI-first hook must exit without output");
            assert!(
                decisions.iter().any(|d| d["updatedInput"]["answers"]
                    == json!({"Color?":"Blue","Other?":"my custom answer"})),
                "{decisions:?}"
            );
            assert!(
                decisions
                    .iter()
                    .any(|d| d["updatedInput"]["answers"] == json!({"Toppings?":"Cheese, Olive"})),
                "{decisions:?}"
            );
            assert!(
                decisions.iter().any(|d| d == &json!({"behavior":"allow"})),
                "{decisions:?}"
            );
            println!(
                "Real Claude: mixed pick/text, Cheese + Olive multi-select, Bash approval, and TUI-first empty-output cleanup passed"
            );
            break;
        }
        assert!(Instant::now() < deadline, "hook did not exit: {}", screen());
        std::thread::sleep(Duration::from_millis(50));
    }
    setup(&["claude-plugin", "remove"]);
}

#[test]
fn hook_question_is_visible_before_transcript_and_removed_on_resume() {
    if isolated_runtime("hook_question_is_visible_before_transcript_and_removed_on_resume") {
        return;
    }
    use aries_beacon::chat::Batch;
    use aries_protocol::chat::{ChatBody, ChatEvent, ToolStatus};
    let dir = tempfile::tempdir().unwrap();
    let req = request(&dir.path().join("transcript"));
    // An empty transcript has a resumable checkpoint, but no tool_use.
    std::fs::write(&req.transcript_path, "").unwrap();
    let terminal = format!("test-{:016x}", rand::random::<u64>());
    let path = hook::socket_path(dir.path(), hook::runtime_dir().as_deref(), &terminal).unwrap();
    let socket = path.clone();
    let other = req.clone();
    let worker = std::thread::spawn(move || {
        hook::wait(
            &socket,
            &terminal,
            other,
            Duration::from_millis(700),
            &mut Offline,
        )
        .unwrap()
    });
    ready(&path);
    let pending: hook::Pending =
        serde_json::from_slice(&std::fs::read(path.with_extension("json")).unwrap()).unwrap();
    assert!(pending.id.starts_with("claude-hook:"));
    assert_eq!(pending.id.len(), "claude-hook:".len() + 16);
    let terminal = path.file_stem().unwrap().to_str().unwrap();
    let agent = Claude {
        located: Located {
            session: req.session_id.clone(),
            path: req.transcript_path.clone(),
        },
    };
    let mut source = hook::open(&agent, &agent.located, None, dir.path(), terminal).source;
    let Batch::Events { events, .. } = source.poll().unwrap() else {
        panic!("no pending item")
    };
    let item = events
        .iter()
        .find_map(|(event, _)| match event {
            ChatEvent::Item { item } => Some(item),
            _ => None,
        })
        .unwrap();
    assert_eq!(item.id, pending.id);
    let ChatBody::Tool {
        question: Some(q),
        status: ToolStatus::Running,
        ..
    } = &item.body
    else {
        panic!("not a running question")
    };
    assert_eq!(q.questions.len(), 2);
    // If Claude flushes the tool before its result, it still has only
    // the hook item as the actionable question.
    record(&req);
    let Batch::Events { events, .. } = source.poll().unwrap() else {
        panic!()
    };
    assert!(
        !events
            .iter()
            .any(|(e, _)| matches!(e, ChatEvent::Item { item } if item.id == "tool1"))
    );
    let cursor = source.position();
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(&req.transcript_path)
        .unwrap();
    writeln!(file, "{}", json!({"type":"user","uuid":"m2","parentUuid":"m1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool1","content":"PC answered"}]},"toolUseResult":{"answers":{"Color?":"Red","Other?":"A"}}})).unwrap();
    assert!(worker.join().unwrap().is_none());
    let Batch::Events { events, .. } = source.poll().unwrap() else {
        panic!()
    };
    assert!(
        events
            .iter()
            .any(|(e, _)| matches!(e, ChatEvent::Remove { id } if id == &pending.id))
    );
    assert!(events.iter().any(|(e, _)| matches!(e, ChatEvent::Item { item } if item.id == "tool1" && matches!(&item.body, ChatBody::Tool { status: ToolStatus::Done, .. }))));
    let mut reopened = hook::open(&agent, &agent.located, Some(&cursor), dir.path(), terminal);
    assert!(reopened.resumed);
    let Batch::Events { events, .. } = reopened.source.poll().unwrap() else {
        panic!("no removal")
    };
    assert!(
        events
            .iter()
            .any(|(e, _)| matches!(e, ChatEvent::Remove { id } if id == &pending.id))
    );
}

#[test]
fn status_transition_resolves_without_a_transcript_result() {
    struct Status(usize);
    impl TerminalLookup for Status {
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            self.0 += 1;
            Ok(Some(TerminalFacts::from_json(
                &json!({"status":{"state":if self.0 == 1 {"blocked"} else {"working"}}}),
            )))
        }
    }
    let dir = tempfile::tempdir().unwrap();
    let path = hook::socket_path(dir.path(), None, "status").unwrap();
    assert!(
        hook::wait(
            &path,
            "status",
            request(&dir.path().join("missing")),
            Duration::from_secs(5),
            &mut Status(0)
        )
        .unwrap()
        .is_none()
    );
    assert!(!path.exists());
    assert!(!path.with_extension("json").exists());
}

#[test]
fn old_identical_results_do_not_resolve_a_new_request() {
    let dir = tempfile::tempdir().unwrap();
    let req = request(&dir.path().join("transcript"));
    record(&req);
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(&req.transcript_path)
        .unwrap();
    writeln!(
        file,
        "{}",
        json!({"message":{"content":[{"type":"tool_result","tool_use_id":"tool1"}]}})
    )
    .unwrap();
    let p = hook::Pending {
        request: req.clone(),
        id: "claude-hook:0123456789abcdef".into(),
        transcript_offset: file.metadata().unwrap().len(),
        created_at: 0,
        nonce: "test".into(),
    };
    assert!(!hook::completed(&p));
    writeln!(file, "{}", json!({"message":{"content":[{"type":"tool_use","id":"different","name":"Bash","input":{}} ,{"type":"tool_result","tool_use_id":"different"}]}})).unwrap();
    assert!(!hook::completed(&p));
    let mut input = req.tool_input;
    input["answers"] = json!({"Color?":"Blue", "Other?":"text"});
    writeln!(file, "{}", json!({"message":{"content":[{"type":"tool_use","id":"new-question","name":"AskUserQuestion","input":input},{"type":"tool_result","tool_use_id":"new-question"}]}})).unwrap();
    assert!(hook::completed(&p));
}

#[test]
fn four_row_claude_approval_keeps_rule_and_mode_changes_on_keys() {
    use aries_beacon::chat::{TerminalInput, permission};
    use aries_protocol::{
        answer::{Answer, AnswerOutcome},
        chat::ChatBody,
    };
    struct Link {
        keys: Vec<String>,
    }
    const SCREEN: &str = "Bash command\n printf hook-bash > permission-hook-proof.txt\n Do you want to proceed?\n ❯ 1. Yes\n   2. Yes, and always allow access to /tmp/work from this project\n   3. Yes, and switch to auto mode · auto mode handles these prompts for you\n   4. No\n\n Esc to cancel · Tab to amend\n";
    impl TerminalLookup for Link {
        fn get(&mut self, _: &str) -> Result<Option<TerminalFacts>, String> {
            Ok(Some(TerminalFacts::from_json(
                &json!({"turn":1,"status":{"state":"blocked","kind":"permission"}}),
            )))
        }
        fn screen(&mut self, _: &str) -> Result<String, String> {
            Ok(SCREEN.into())
        }
    }
    impl TerminalInput for Link {
        fn claim_structured(&mut self) -> Result<(), String> {
            panic!("rule/mode choices must use keys")
        }
        fn keys(&mut self, _: &str, keys: &[String]) -> Result<(), String> {
            self.keys.extend_from_slice(keys);
            Ok(())
        }
    }
    for (pick, key) in [(1, "2"), (2, "3")] {
        let dir = tempfile::tempdir().unwrap();
        let mut link = Link { keys: vec![] };
        let facts = link.get("t").unwrap().unwrap();
        let p = permission::detect("claude-code", "session", &facts, SCREEN).unwrap();
        let ChatBody::Tool {
            question: Some(q), ..
        } = &p.item.body
        else {
            panic!()
        };
        assert_eq!(
            q.questions[0]
                .options
                .iter()
                .map(|o| o.label.as_str())
                .collect::<Vec<_>>(),
            ["Allow", "Allow always", "Switch to auto mode", "Deny"]
        );
        let agents: Vec<Box<dyn Agent>> = vec![Box::new(Claude {
            located: Located {
                session: "session".into(),
                path: dir.path().join("transcript"),
            },
        })];
        let result = answer::answer(
            dir.path(),
            "t",
            &Answer {
                question: p.item.id,
                picks: vec![vec![pick]],
                free_text: vec![],
            },
            &mut link,
            &agents,
        )
        .unwrap();
        assert_eq!(result.outcome, AnswerOutcome::Sent);
        assert_eq!(link.keys, [key]);
    }
}
