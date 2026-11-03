mod common;
use aries_core::AgentPreset;
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

fn put(root: &std::path::Path, path: &str, text: &str) {
    let path = root.join(path);
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, text).unwrap();
}

#[tokio::test]
async fn catalogs_collect_agent_specific_commands_skills_and_refresh_cached_results() {
    let home = tempfile::tempdir().unwrap();
    let repo = tempfile::tempdir().unwrap();
    put(
        home.path(),
        ".claude/commands/team/review.md",
        "---\ndescription: Review changes\nargument-hint: [branch]\n---\nNever execute this body",
    );
    put(
        home.path(),
        ".claude/skills/testing/SKILL.md",
        "---\nname: testing\ndescription: Test the changes\n---\nbody",
    );
    put(
        home.path(),
        ".codex/prompts/release.md",
        "---\ndescription: Prepare release\n---\nbody",
    );
    put(
        home.path(),
        ".agents/skills/shared/SKILL.md",
        "---\nname: shared\ndescription: Shared skill\n---\nbody",
    );
    put(
        repo.path(),
        ".claude/commands/local.md",
        "---\ndescription: Local command\n---\nbody",
    );
    let hc = HcSession::start_with_env(&[("HOME", home.path().to_str().unwrap())]);
    let terminal = hc.rpc(
        "terminal.run",
        serde_json::json!({"argv":["/bin/sleep","300"], "cwd":repo.path(), "no_parent":true}),
    );
    let address = terminal["address"].as_str().unwrap().to_owned();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let claude = conn
        .agent_catalog(address.clone(), AgentPreset::ClaudeCode, false)
        .await
        .unwrap();
    assert!(
        claude
            .commands
            .iter()
            .any(|e| e.token == "/help" && e.source.is_none())
    );
    let review = claude
        .commands
        .iter()
        .find(|e| e.token == "/team:review")
        .unwrap();
    assert_eq!(review.description, "Review changes");
    assert_eq!(review.argument_hint, "[branch]");
    assert!(claude.commands.iter().any(|e| e.token == "/local"));
    assert!(claude.skills.iter().any(|e| e.token == "/testing"));
    assert!(
        !claude
            .commands
            .iter()
            .any(|e| e.token == "/prompts:release")
    );
    let codex = conn
        .agent_catalog(address.clone(), AgentPreset::Codex, false)
        .await
        .unwrap();
    assert!(codex.commands.iter().any(|e| e.token == "/prompts:release"));
    assert!(codex.skills.iter().any(|e| e.token == "$shared"));
    assert!(!codex.skills.iter().any(|e| e.token == "/testing"));
    put(home.path(), ".claude/commands/new.md", "new command");
    assert!(
        !conn
            .agent_catalog(address.clone(), AgentPreset::ClaudeCode, false)
            .await
            .unwrap()
            .commands
            .iter()
            .any(|e| e.token == "/new")
    );
    assert!(
        conn.agent_catalog(address, AgentPreset::ClaudeCode, true)
            .await
            .unwrap()
            .commands
            .iter()
            .any(|e| e.token == "/new")
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn forwarded_catalogs_use_the_terminal_machine_and_agent_skill_syntax() {
    let home = tempfile::tempdir().unwrap();
    let repo = tempfile::tempdir().unwrap();
    put(
        home.path(),
        ".pi/agent/prompts/plan.md",
        "---\ndescription: Plan work\n---\nbody",
    );
    put(
        home.path(),
        ".pi/agent/skills/verify/SKILL.md",
        "---\nname: verify\ndescription: >-\n  Verify changes\n  carefully\n---\nbody",
    );
    put(
        home.path(),
        ".agents/skills/shared/SKILL.md",
        "---\nname: shared\ndescription: Shared skill\n---\nbody",
    );
    put(
        home.path(),
        ".config/opencode/commands/check.md",
        "---\ndescription: Check work\n---\nbody",
    );
    let hc = HcSession::start();
    let config = home.path().join(".config");
    let remote = HcSession::start_with_env(&[
        ("HOME", home.path().to_str().unwrap()),
        ("XDG_CONFIG_HOME", config.to_str().unwrap()),
    ]);
    hc.add_machine("remote", &remote);
    let launched = remote.rpc(
        "terminal.run",
        serde_json::json!({"argv":["/bin/sleep","300"], "cwd":repo.path(), "no_parent":true}),
    );
    let address = format!("remote/{}", launched["address"].as_str().unwrap());
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let pi = conn
        .agent_catalog(address.clone(), AgentPreset::Pi, false)
        .await
        .unwrap();
    assert!(pi.commands.iter().any(|e| e.token == "/plan"));
    let verify = pi.skills.iter().find(|e| e.name == "verify").unwrap();
    assert_eq!(verify.token, "/skill:verify");
    assert_eq!(verify.description, "Verify changes carefully");
    let opencode = conn
        .agent_catalog(address, AgentPreset::OpenCode, false)
        .await
        .unwrap();
    assert!(opencode.commands.iter().any(|e| e.token == "/check"));
    assert!(opencode.skills.iter().any(|e| e.name == "shared"));
    conn.disconnect().await;
}
