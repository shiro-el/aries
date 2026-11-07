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

#[test]
fn mixed_answers_travel_through_the_hook_socket() {
    let dir = tempfile::tempdir().unwrap();
    let path = hook::socket_path(dir.path(), None, "t1").unwrap();
    let req = request(&dir.path().join("transcript"));
    record(&req);
    let other = req.clone();
    let socket = path.clone();
    let worker = std::thread::spawn(move || {
        hook::wait(&socket, "t1", other, Duration::from_secs(5), &mut Offline).unwrap()
    });
    ready(&path);
    let pending: hook::Pending =
        serde_json::from_slice(&std::fs::read(path.with_extension("json")).unwrap()).unwrap();
    let mut stream = std::os::unix::net::UnixStream::connect(&path).unwrap();
    writeln!(
        stream,
        "{}",
        json!({"nonce":pending.nonce,"answer":{"Color?":"Blue","Other?":"my own answer"}})
    )
    .unwrap();
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
        record(&req);
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
            let mut file = std::fs::OpenOptions::new()
                .append(true)
                .open(req.transcript_path)
                .unwrap();
            writeln!(file,"{}",json!({"message":{"content":[{"type":"tool_result","tool_use_id":"tool1","content":"PC answered"}]}})).unwrap();
        }
        assert!(worker.join().unwrap().is_none());
        assert!(!path.exists());
        assert!(!path.with_extension("json").exists());
    }
}

#[test]
fn outside_hc_does_not_even_read_stdin_or_create_state() {
    let dir = tempfile::tempdir().unwrap();
    let out = Command::new(BEACON)
        .args(["claude-hook", "permission-request"])
        .env_remove("HC_TERMINAL_ID")
        .env("XDG_STATE_HOME", dir.path())
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
    // Exercise the public answer seam with the actual Claude transcript interpreter.
    use aries_beacon::chat::{Agent, Located, TerminalInput, answer, claude::ClaudeCode};
    use aries_protocol::answer::{Answer, AnswerOutcome};
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
    #[derive(Default)]
    struct Link {
        keys: Vec<String>,
    }
    impl TerminalLookup for Link {
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
    record(&req);
    let terminal = format!("test-{:016x}", rand::random::<u64>());
    let path = hook::socket_path(dir.path(), hook::runtime_dir().as_deref(), &terminal).unwrap();
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let listener = UnixListener::bind(&path).unwrap();
    let pending = hook::Pending {
        request: req.clone(),
        tool_use_id: "tool1".into(),
        created_at: 0,
        nonce: "test".into(),
    };
    std::fs::write(
        path.with_extension("json"),
        serde_json::to_vec(&pending).unwrap(),
    )
    .unwrap();
    let worker = std::thread::spawn(move || {
        let (socket, _) = listener.accept().unwrap();
        let mut line = String::new();
        BufReader::new(socket).read_line(&mut line).unwrap();
        serde_json::from_str::<Value>(&line).unwrap()
    });
    let agents: Vec<Box<dyn Agent>> = vec![Box::new(Claude {
        located: Located {
            session: "session".into(),
            path: req.transcript_path,
        },
    })];
    let mut link = Link::default();
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
    assert_eq!(result.outcome, AnswerOutcome::Sent, "{result:?}");
    assert_eq!(
        worker.join().unwrap()["answer"],
        json!({"Color?":"Blue","Other?":"B"})
    );
    assert_eq!(link.keys, ["1", "2", "1"]);
    std::fs::remove_file(&path).unwrap();
    std::fs::remove_file(path.with_extension("json")).unwrap();
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
    let ask = pending("AskUserQuestion");
    // A real answer must close the menu, not merely write to the socket.
    std::thread::sleep(Duration::from_secs(1));
    let reply = beacon(&[
        "answer",
        &terminal,
        &ask.tool_use_id,
        "0",
        "text:my custom answer",
    ]);
    assert!(reply.contains("\"sent\""), "{reply}");
    assert!(hook::completed(
        &ask.request.transcript_path,
        &ask.tool_use_id
    ));
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
    beacon(&["answer", &terminal, &multi.tool_use_id, "0,2"]);
    assert!(hook::completed(
        &multi.request.transcript_path,
        &multi.tool_use_id
    ));
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
    .expect("Bash permission menu");
    beacon(&["answer", &terminal, &permission.item.id, "0"]);
    assert!(hook::completed(
        &bash.request.transcript_path,
        &bash.tool_use_id
    ));
    let deadline = Instant::now() + Duration::from_secs(10);
    while !cwd.join("permission-hook-proof.txt").exists() {
        assert!(Instant::now() < deadline);
        std::thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(
        std::fs::read_to_string(cwd.join("permission-hook-proof.txt")).unwrap(),
        "hook-bash"
    );
    setup(&["claude-plugin", "remove"]);
}
