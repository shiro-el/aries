//! Core API seam: Git output and herdr-app control over an isolated core.
mod common;

use aries_test_support::HcSession;
use common::{core_in, local_endpoint};
use std::os::unix::fs::PermissionsExt;

fn executable(path: &std::path::Path, script: &str) {
    std::fs::write(path, script).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
}

#[tokio::test]
async fn lists_porcelain_records_without_splitting_or_unquoting_paths() {
    let tools = tempfile::tempdir().unwrap();
    let output = tools.path().join("porcelain");
    std::fs::write(&output, b"worktree /repo with spaces\0HEAD abc123\0branch refs/heads/main\0\0worktree /linked\n'\"$(false)\0HEAD def456\0detached\0locked reason with spaces\0\0worktree /bare\0bare\0\0worktree /gone\0HEAD abc123\0branch refs/heads/topic\0prunable gitdir file points to non-existent location\0\0").unwrap();
    executable(
        &tools.path().join("git"),
        &format!(
            "#!/bin/sh\n[ \"$*\" = '--no-optional-locks worktree list --porcelain -z' ] || exit 9\n/bin/cat '{}'\n",
            output.display()
        ),
    );
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let list = conn.list_worktrees("/".into()).await.unwrap();
    assert_eq!(list.len(), 4);
    assert_eq!(list[0].path, "/repo with spaces");
    assert_eq!(list[0].head.as_deref(), Some("abc123"));
    assert_eq!(list[0].branch.as_deref(), Some("refs/heads/main"));
    assert!(!list[0].detached);
    assert_eq!(list[1].path, "/linked\n'\"$(false)");
    assert!(list[1].detached);
    assert_eq!(list[1].locked.as_deref(), Some("reason with spaces"));
    assert!(list[2].bare);
    assert_eq!(list[2].head, None);
    assert_eq!(
        list[3].prunable.as_deref(),
        Some("gitdir file points to non-existent location")
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn opens_a_new_tab_with_the_agent_preset_and_never_retries_a_failed_edit() {
    use aries_core::{AgentPreset, WorktreeOpen};
    let tools = tempfile::tempdir().unwrap();
    let app = tools.path().join("herdr-app");
    let args = tools.path().join("args");
    executable(
        &app,
        &format!(
            "#!/bin/sh\nif [ \"$1\" = status ]; then printf '{{}}'; exit 0; fi\n[ \"$1\" = do ] && [ \"$2\" = tab.new ] && [ \"$#\" = 3 ] || exit 9\nprintf '%s' \"$3\" > '{}'\nprintf '%s' '{{\"space\":\"s1\",\"tab\":\"tb2\",\"terminal\":\"t9\"}}'\n",
            args.display()
        ),
    );
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let folder = "/repo '한글'\n$(false)";
    let result = conn
        .open_worktree(folder.into(), Some("s1".into()), Some(AgentPreset::Codex))
        .await
        .unwrap();
    assert!(
        matches!(result, WorktreeOpen::Tab { space_id, tab_id, address } if space_id == "s1" && tab_id == "tb2" && address == "t9")
    );
    let passed: serde_json::Value = serde_json::from_slice(&std::fs::read(&args).unwrap()).unwrap();
    assert_eq!(
        passed,
        serde_json::json!({"space":"s1", "name":"", "folder":folder, "argv":["codex", "--no-daemon"]})
    );
    // Status succeeds but the mutation fails: do not launch a second program.
    executable(
        &app,
        "#!/bin/sh\nif [ \"$1\" = status ]; then exit 0; fi\nprintf 'no such space'; exit 1\n",
    );
    assert!(
        conn.open_worktree("/".into(), Some("s1".into()), None)
            .await
            .is_err()
    );
    assert!(conn.terminals().await.unwrap().is_empty());
    conn.disconnect().await;
}

#[tokio::test]
async fn a_stopped_or_missing_app_launches_a_terminal_in_the_selected_worktree() {
    use aries_core::WorktreeOpen;
    let tools = tempfile::tempdir().unwrap();
    let folder = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    for stopped in [false, true] {
        if stopped {
            executable(&tools.path().join("herdr-app"), "#!/bin/sh\nexit 1\n");
        }
        let result = conn
            .open_worktree(
                folder.path().to_str().unwrap().into(),
                Some("s1".into()),
                None,
            )
            .await
            .unwrap();
        let WorktreeOpen::Terminal { address } = result else {
            panic!("expected launch fallback")
        };
        let terminal = conn
            .terminals()
            .await
            .unwrap()
            .into_iter()
            .find(|t| t.address == address)
            .unwrap();
        assert_eq!(terminal.cwd.as_deref(), folder.path().to_str());
        assert_eq!(terminal.state, "live");
        conn.kill_terminal(terminal.address).await.unwrap();
    }
    assert_eq!(conn.recent_cwds(), [folder.path().to_str().unwrap()]);
    conn.disconnect().await;
}

#[tokio::test]
async fn lists_real_git_worktrees_from_a_nested_folder_and_reports_non_repositories() {
    let repo = tempfile::tempdir().unwrap();
    let linked_parent = tempfile::tempdir().unwrap();
    let linked = linked_parent.path().join("checkout '한글'\nnext");
    let git = |args: &[&str]| {
        let out = std::process::Command::new("git")
            .current_dir(repo.path())
            .args(args)
            .output()
            .unwrap();
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
    };
    git(&["init", "-q", "-b", "main"]);
    git(&[
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "--allow-empty",
        "-qm",
        "initial",
    ]);
    git(&["worktree", "add", "--detach", linked.to_str().unwrap()]);
    std::fs::create_dir(linked.join("nested")).unwrap();
    let hc = HcSession::start_with_env(&[("LANG", "ko_KR.UTF-8"), ("LC_ALL", "ko_KR.UTF-8")]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let list = conn
        .list_worktrees(linked.join("nested").to_str().unwrap().into())
        .await
        .unwrap();
    assert_eq!(list.len(), 2);
    assert_eq!(list[0].path, repo.path().to_str().unwrap());
    assert_eq!(list[0].branch.as_deref(), Some("refs/heads/main"));
    assert_eq!(list[1].path, linked.to_str().unwrap());
    assert!(list[1].detached);
    let error = conn
        .list_worktrees(data.path().to_str().unwrap().into())
        .await
        .unwrap_err();
    assert!(
        error.to_string().contains("not a git repository"),
        "{error}"
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn unplaced_worktree_opens_without_using_the_desktops_active_space() {
    use aries_core::WorktreeOpen;
    let tools = tempfile::tempdir().unwrap();
    // Even with an app executable, no implicit Tab can be requested.
    executable(&tools.path().join("herdr-app"), "#!/bin/sh\nexit 9\n");
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let opened = conn
        .open_worktree(data.path().to_str().unwrap().into(), None, None)
        .await
        .unwrap();
    let WorktreeOpen::Terminal { address } = opened else {
        panic!("expected unplaced Terminal")
    };
    assert!(
        conn.structure()
            .await
            .unwrap()
            .unplaced
            .iter()
            .any(|t| t.address == address)
    );
    conn.kill_terminal(address).await.unwrap();
    conn.disconnect().await;
}

#[tokio::test]
async fn incomplete_or_non_utf8_porcelain_is_an_error_instead_of_a_damaged_path() {
    let tools = tempfile::tempdir().unwrap();
    let output = tools.path().join("porcelain");
    executable(
        &tools.path().join("git"),
        &format!("#!/bin/sh\n/bin/cat '{}'\n", output.display()),
    );
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    for invalid in [
        b"worktree /partial\0HEAD abc123\0".as_slice(),
        b"worktree /bad\xff\0\0".as_slice(),
    ] {
        std::fs::write(&output, invalid).unwrap();
        assert!(conn.list_worktrees("/".into()).await.is_err());
    }
    conn.disconnect().await;
}

#[tokio::test]
async fn launch_fallback_preserves_trailing_whitespace_in_a_worktree_path() {
    let parent = tempfile::tempdir().unwrap();
    let folder = parent.path().join("worktree\n  ");
    std::fs::create_dir(&folder).unwrap();
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let result = conn
        .open_worktree(folder.to_str().unwrap().into(), None, None)
        .await
        .unwrap();
    let aries_core::WorktreeOpen::Terminal { address } = result else {
        panic!("expected fallback")
    };
    let terminal = conn
        .terminals()
        .await
        .unwrap()
        .into_iter()
        .find(|t| t.address == address)
        .unwrap();
    assert_eq!(terminal.cwd.as_deref(), folder.to_str());
    assert_eq!(conn.recent_cwds(), [folder.to_str().unwrap()]);
    conn.kill_terminal(address).await.unwrap();
    conn.disconnect().await;
}
