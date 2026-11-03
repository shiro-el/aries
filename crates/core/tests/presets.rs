//! Seam 1: launching Agents from presets and saved launch configs, against a
//! real herdr-core whose PATH holds fake `claude`/`codex`/`opencode`/`pi`
//! programs that record how they were started.

mod common;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use aries_core::{AgentPreset, AriesCore, HostConnection, LaunchRequest, SavedLaunch};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

const AGENTS: [&str; 4] = ["claude", "codex", "opencode", "pi"];

/// A dir of fake agents: each writes `<cwd>/<name>.out` with its argv (one
/// per line), then `--`, then its `HC_TERMINAL_ID` and the names (never the
/// values) of its `CLAUDE*` variables, and
/// stays alive like an interactive Agent.
fn fake_agents(dir: &Path) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let bin = dir.join("bin");
    std::fs::create_dir_all(&bin).unwrap();
    for name in AGENTS {
        let path = bin.join(name);
        std::fs::write(
            &path,
            format!(
                "#!/bin/sh\n\
                 out=\"$PWD/{name}.out\"\n\
                 for a in \"$0\" \"$@\"; do printf '%s\\n' \"$a\"; done > \"$out\"\n\
                 echo -- >> \"$out\"\n\
                 echo \"HC_TERMINAL_ID=$HC_TERMINAL_ID\" >> \"$out\"\n\
                 env | sed -n 's/^\\(CLAUDE[A-Za-z0-9_]*\\)=.*/\\1/p' >> \"$out\"\n\
                 exec sleep 300\n"
            ),
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    bin
}

/// A core whose environment looks like one started inside a Claude Code
/// session, with the fake agents first on its PATH.
fn session(dir: &Path) -> HcSession {
    let bin = fake_agents(dir);
    let path = format!("{}:{}", bin.display(), std::env::var("PATH").unwrap());
    HcSession::start_with_env(&[
        ("PATH", &path),
        ("CLAUDE_CODE_CHILD_SESSION", "1"),
        ("CLAUDECODE", "1"),
        ("CLAUDE_CODE_SESSION_ID", "parent-session"),
    ])
}

/// (argv with argv[0] as its basename, env lines) the fake agent recorded.
fn recorded(cwd: &Path, name: &str) -> (Vec<String>, Vec<String>) {
    let file = cwd.join(format!("{name}.out"));
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(text) = std::fs::read_to_string(&file)
            && text.lines().any(|l| l == "--")
        {
            let mut lines = text.lines().map(str::to_owned);
            let mut argv: Vec<String> = lines.by_ref().take_while(|l| l != "--").collect();
            argv[0] = Path::new(&argv[0])
                .file_name()
                .unwrap()
                .to_string_lossy()
                .into_owned();
            return (argv, lines.collect());
        }
        assert!(Instant::now() < deadline, "{name} never started");
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn preset_request(preset: AgentPreset, extra: &str, cwd: &Path) -> LaunchRequest {
    LaunchRequest {
        command: extra.into(),
        cwd: Some(cwd.to_string_lossy().into_owned()),
        space_id: Some("s1".into()),
        tab_id: Some("tab1".into()),
        preset: Some(preset),
        model: None,
        effort: None,
    }
}

async fn connect(core: &Arc<AriesCore>, host_id: &str) -> Arc<HostConnection> {
    core.connect(host_id.into()).await.unwrap()
}

#[tokio::test]
async fn model_and_effort_reach_each_agent_as_separate_arguments() {
    let tmp = tempfile::tempdir().unwrap();
    let hc = session(tmp.path());
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = connect(&core, &host.id).await;
    for (preset, name, model, effort, expected) in [
        (
            AgentPreset::ClaudeCode,
            "claude",
            "sonnet",
            "high",
            vec!["claude", "--model", "sonnet", "--effort", "high"],
        ),
        (
            AgentPreset::Codex,
            "codex",
            "custom model;$(echo nope)",
            "xhigh",
            vec![
                "codex",
                "--no-daemon",
                "-m",
                "custom model;$(echo nope)",
                "-c",
                "model_reasoning_effort=\"xhigh\"",
            ],
        ),
        (
            AgentPreset::OpenCode,
            "opencode",
            "provider/model",
            "",
            vec!["opencode", "--model", "provider/model"],
        ),
        (
            AgentPreset::Pi,
            "pi",
            "provider/model",
            "off",
            vec!["pi", "--model", "provider/model", "--thinking", "off"],
        ),
    ] {
        let cwd = tmp.path().join(name);
        std::fs::create_dir_all(&cwd).unwrap();
        let mut request = preset_request(preset, "", &cwd);
        request.model = Some(model.into());
        request.effort = Some(effort.into());
        conn.launch(request).await.unwrap();
        assert_eq!(recorded(&cwd, name).0, expected, "{name}");
    }
    conn.disconnect().await;
}

#[tokio::test]
async fn saved_launches_from_before_model_and_effort_still_launch() {
    let tmp = tempfile::tempdir().unwrap();
    let hc = session(tmp.path());
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let host_dir = data.path().join("hosts").join(&host.id);
    std::fs::create_dir_all(&host_dir).unwrap();
    std::fs::write(
        host_dir.join("saved-launches.json"),
        serde_json::to_vec(&serde_json::json!([
            {"id":"old", "name":"Codex", "request":{
                "command":"-m old-model", "cwd":tmp.path(),
                "space_id":null, "tab_id":null, "preset":"codex"
            }}
        ]))
        .unwrap(),
    )
    .unwrap();
    let conn = connect(&core, &host.id).await;
    let saved = conn.saved_launches();
    assert_eq!(saved.len(), 1);
    assert_eq!(saved[0].request.model, None);
    assert_eq!(saved[0].request.effort, None);
    conn.launch(saved[0].request.clone()).await.unwrap();
    assert_eq!(
        recorded(tmp.path(), "codex").0,
        ["codex", "--no-daemon", "-m", "old-model"]
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn each_preset_starts_its_agent_with_the_settings_it_needs() {
    let tmp = tempfile::tempdir().unwrap();
    let hc = session(tmp.path());
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = connect(&core, &host.id).await;

    let cases = [
        (
            AgentPreset::ClaudeCode,
            "claude",
            "--model haiku",
            vec!["claude", "--model", "haiku"],
        ),
        (
            AgentPreset::Codex,
            "codex",
            "-m gpt-5",
            vec!["codex", "--no-daemon", "-m", "gpt-5"],
        ),
        (AgentPreset::OpenCode, "opencode", "", vec!["opencode"]),
        (
            AgentPreset::Pi,
            "pi",
            " --thinking  high ",
            vec!["pi", "--thinking", "high"],
        ),
    ];
    for (preset, name, extra, want_argv) in cases {
        let cwd = tmp.path().join(format!("work-{name}"));
        std::fs::create_dir_all(&cwd).unwrap();
        let t = conn
            .launch(preset_request(preset, extra, &cwd))
            .await
            .unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert_eq!(t.state, "live", "{name}");
        assert_eq!(t.cwd.as_deref(), Some(cwd.to_str().unwrap()), "{name}");

        let (argv, env) = recorded(&cwd, name);
        assert_eq!(argv, want_argv, "{name}");
        // Every Agent can report to herdr-core (OpenCode's and Pi's Beacon
        // extensions label their Terminal through HC_TERMINAL_ID).
        assert!(
            env.iter()
                .any(|l| l == &format!("HC_TERMINAL_ID={}", t.address)),
            "{name}: {env:?}"
        );
        if preset == AgentPreset::ClaudeCode {
            // An inherited CLAUDE* marker turns off Claude Code's transcript.
            assert!(
                env.iter().all(|l| !l.starts_with("CLAUDE")),
                "claude inherited {env:?}"
            );
        }
        // Placed like any launch.
        let labels =
            &hc.rpc("terminal.get", serde_json::json!({ "terminal": t.address }))["labels"];
        assert_eq!(labels["herdr.space"], "s1", "{name}");
        assert_eq!(labels["herdr.tab"], "tab1", "{name}");
    }
    assert_eq!(conn.recent_cwds().len(), 4);
    conn.disconnect().await;
}

#[tokio::test]
async fn saved_launches_are_kept_per_host_and_relaunch_in_one_call() {
    let tmp = tempfile::tempdir().unwrap();
    let hc = session(tmp.path());
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let other = core.add_host("other".into(), local_endpoint(&hc)).unwrap();
    let conn = connect(&core, &host.id).await;
    let cwd = tmp.path().join("proj");
    std::fs::create_dir_all(&cwd).unwrap();

    assert!(conn.saved_launches().is_empty());
    let codex = conn
        .save_launch(SavedLaunch {
            id: String::new(),
            name: " Codex on proj ".into(),
            request: preset_request(AgentPreset::Codex, "-m gpt-5", &cwd),
        })
        .unwrap();
    assert!(!codex.id.is_empty());
    assert_eq!(codex.name, "Codex on proj");
    let custom = conn
        .save_launch(SavedLaunch {
            id: String::new(),
            name: "logs".into(),
            request: LaunchRequest {
                command: "tail -f /dev/null".into(),
                cwd: None,
                space_id: None,
                tab_id: None,
                preset: None,
                model: None,
                effort: None,
            },
        })
        .unwrap();
    assert_ne!(codex.id, custom.id);
    assert_eq!(conn.saved_launches(), vec![codex.clone(), custom.clone()]);

    // Edit keeps its id and place in the list.
    let edited = SavedLaunch {
        name: "Codex high".into(),
        request: LaunchRequest {
            model: Some("custom-model".into()),
            effort: Some("high".into()),
            ..preset_request(AgentPreset::Codex, "--search", &cwd)
        },
        ..codex.clone()
    };
    assert_eq!(conn.save_launch(edited.clone()).unwrap(), edited);
    assert_eq!(conn.saved_launches(), vec![edited.clone(), custom.clone()]);

    // Survives an app restart, per Host.
    conn.disconnect().await;
    let core = core_in(data.path());
    // Launcher shortcuts can enumerate saved configs while offline, without opening a key.
    assert_eq!(
        core.saved_launches(host.id.clone()).unwrap(),
        vec![edited.clone(), custom.clone()]
    );
    assert!(core.saved_launches(other.id.clone()).unwrap().is_empty());
    let conn = connect(&core, &host.id).await;
    assert_eq!(conn.saved_launches(), vec![edited.clone(), custom.clone()]);
    assert!(connect(&core, &other.id).await.saved_launches().is_empty());

    // One call relaunches it exactly as saved.
    let t = conn
        .launch(conn.saved_launches()[0].request.clone())
        .await
        .unwrap();
    let (argv, _) = recorded(&cwd, "codex");
    assert_eq!(
        argv,
        vec![
            "codex",
            "--no-daemon",
            "--search",
            "-m",
            "custom-model",
            "-c",
            "model_reasoning_effort=\"high\""
        ]
    );
    assert_eq!(t.cwd.as_deref(), Some(cwd.to_str().unwrap()));

    conn.delete_saved_launch(edited.id.clone()).unwrap();
    assert_eq!(conn.saved_launches(), vec![custom.clone()]);
    // Deleting what is already gone is fine.
    conn.delete_saved_launch(edited.id.clone()).unwrap();
    conn.disconnect().await;

    // Host removal deletes them.
    core.remove_host(host.id.clone()).unwrap();
    assert!(!data.path().join("hosts").join(&host.id).exists());
}
