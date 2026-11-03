//! Git reader seam: byte-safe porcelain records and numbered unified diffs.
use aries_core::parse_git_status;

#[test]
fn porcelain_preserves_paths_and_consumes_rename_sources() {
    let files = parse_git_status(" M a b\n한글.rs\0R  new\0old\0?? -[x].txt\0MM both\0".as_bytes())
        .unwrap();
    assert_eq!(files.len(), 4);
    assert_eq!(files[0].path, "a b\n한글.rs");
    assert!(!files[0].staged && files[0].unstaged);
    assert_eq!(files[1].original_path.as_deref(), Some("old"));
    assert!(files[1].staged && !files[1].unstaged);
    assert!(files[2].untracked);
    assert!(files[3].staged && files[3].unstaged);
    assert!(parse_git_status(b" M incomplete").is_err());
    assert!(parse_git_status(b"R  new\0").is_err());
}

#[test]
fn diff_tracks_each_side_across_hunks_and_no_newline_markers() {
    let diff = aries_core::parse_git_diff(
        "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -2,2 +2,3 @@\n same\n-old\n+new\n+extra\n@@ -9 +10 @@\n-last\n+done\n\\ No newline at end of file\n",
        false,
    );
    assert_eq!(diff.lines[4].old_line, Some(2));
    assert_eq!(diff.lines[5].new_line, None);
    assert_eq!(diff.lines[6].new_line, Some(3));
    assert_eq!(diff.lines[10].new_line, Some(10));
    assert_eq!(diff.lines[11].new_line, None);
    assert!(aries_core::parse_git_diff("Binary files a/x and b/x differ\n", true).binary);
}

mod common;
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};
use std::process::Command;

fn git(repo: &std::path::Path, args: &[&str]) {
    let result = Command::new("git")
        .args(args)
        .current_dir(repo)
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
}

#[tokio::test]
async fn reader_uses_launch_folder_and_reads_staged_unstaged_and_untracked_without_writes() {
    let repo = tempfile::tempdir().unwrap();
    git(repo.path(), &["init", "-q"]);
    std::fs::write(repo.path().join("both [1]"), "first\n").unwrap();
    git(repo.path(), &["add", "."]);
    git(
        repo.path(),
        &[
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.invalid",
            "commit",
            "-qm",
            "base",
        ],
    );
    std::fs::write(repo.path().join("both [1]"), "staged\n").unwrap();
    git(repo.path(), &["add", "."]);
    std::fs::write(repo.path().join("both [1]"), "unstaged\n").unwrap();
    std::fs::write(repo.path().join("-[x] 한글\n.txt"), "untracked\n").unwrap();
    // Both configured hooks fail if executed. Read-only previews must bypass them.
    git(repo.path(), &["config", "diff.external", "/bin/false"]);
    git(
        repo.path(),
        &["config", "diff.preview.textconv", "/bin/false"],
    );
    std::fs::write(repo.path().join(".git/info/attributes"), "* diff=preview\n").unwrap();
    let before = std::fs::read(repo.path().join(".git/index")).unwrap();
    let hc = HcSession::start();
    let launched = hc.rpc(
        "terminal.run",
        serde_json::json!({"argv":["/bin/sleep", "300"], "cwd":repo.path(), "no_parent":true}),
    );
    let address = launched["address"].as_str().unwrap().to_owned();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let changes = conn.git_changes(address.clone()).await.unwrap();
    assert!(!changes.not_repository);
    assert_eq!(changes.files.len(), 2);
    let both = changes.files.iter().find(|f| f.path == "both [1]").unwrap();
    assert!(both.staged && both.unstaged);
    for (kind, path, expected) in [
        (aries_core::GitDiffKind::Staged, "both [1]", "+staged"),
        (aries_core::GitDiffKind::Unstaged, "both [1]", "+unstaged"),
        (
            aries_core::GitDiffKind::Untracked,
            "-[x] 한글\n.txt",
            "+untracked",
        ),
    ] {
        let diff = conn
            .git_diff(address.clone(), changes.cwd.clone(), path.into(), kind)
            .await
            .unwrap();
        assert!(diff.lines.iter().any(|l| l.text == expected));
    }
    let outside = tempfile::tempdir().unwrap();
    assert!(
        conn.git_changes_in(address, Some(outside.path().display().to_string()))
            .await
            .unwrap()
            .not_repository
    );
    assert_eq!(
        before,
        std::fs::read(repo.path().join(".git/index")).unwrap()
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn exec_decodes_binary_output_exit_timeout_and_truncation() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let out = conn
        .machine_exec(
            vec![
                "/bin/sh".into(),
                "-c".into(),
                "printf '\\000\\377'; printf problem >&2; exit 7".into(),
            ],
            None,
            3,
        )
        .await
        .unwrap();
    assert_eq!(out.stdout, vec![0, 255]);
    assert_eq!(out.stderr, b"problem");
    assert_eq!(out.code, Some(7));
    let out = conn
        .machine_exec(
            vec![
                "/usr/bin/python3".into(),
                "-c".into(),
                "import sys; sys.stdout.write('a' * (9 * 1024 * 1024))".into(),
            ],
            None,
            10,
        )
        .await
        .unwrap();
    assert!(out.truncated);
    assert_eq!(out.stdout.len(), 8 * 1024 * 1024);
    let out = conn
        .machine_exec(vec!["/bin/sleep".into(), "10".into()], None, 1)
        .await
        .unwrap();
    assert!(out.timed_out);
    conn.disconnect().await;
}

#[tokio::test]
async fn current_foreground_folder_takes_priority_over_launch_folder() {
    let repo = tempfile::tempdir().unwrap();
    git(repo.path(), &["init", "-q"]);
    std::fs::write(repo.path().join("current"), "here\n").unwrap();
    let launch = tempfile::tempdir().unwrap();
    let hc = HcSession::start();
    let terminal = hc.rpc(
        "terminal.run",
        serde_json::json!({
            "argv":["/bin/sh", "-c", "cd \"$1\"; exec /bin/sleep 300", "sh", repo.path()],
            "cwd": launch.path(), "no_parent": true
        }),
    );
    let address = terminal["address"].as_str().unwrap().to_owned();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    loop {
        let changes = conn.git_changes(address.clone()).await.unwrap();
        if !changes.not_repository {
            assert_eq!(changes.cwd, repo.path().display().to_string());
            assert_eq!(changes.files[0].path, "current");
            break;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "foreground cwd was never used"
        );
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    conn.disconnect().await;
}
