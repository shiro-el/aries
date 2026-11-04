//! PR resource parsing at the public core API; gh is faked at the external boundary.
mod common;

use aries_core::{GithubRepository, HostConnection};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};
use std::{os::unix::fs::PermissionsExt, sync::Arc};

async fn setup(
    scenario: Option<&str>,
) -> (
    tempfile::TempDir,
    tempfile::TempDir,
    HcSession,
    Arc<HostConnection>,
    GithubRepository,
) {
    let tools = tempfile::tempdir().unwrap();
    let gh = tools.path().join("gh");
    std::fs::write(&gh, include_str!("fixtures/github.py")).unwrap();
    std::fs::set_permissions(gh, std::fs::Permissions::from_mode(0o755)).unwrap();
    let mut env = vec![("PATH", tools.path().to_str().unwrap())];
    if let Some(scenario) = scenario {
        env.push(("GH_TEST_SCENARIO", scenario));
    }
    let hc = HcSession::start_with_env(&env);
    let address = hc.run_bg("github-pr", &["/bin/sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let repo = conn.github_repository(address).await.unwrap();
    (tools, data, hc, conn, repo)
}

#[tokio::test]
async fn files_keep_all_pages_renames_unicode_and_distinguish_missing_patches() {
    let (_tools, _data, _hc, conn, repo) = setup(None).await;
    let page = conn.github_pr_files(repo, 42).await.unwrap();
    assert!(!page.limited);
    assert_eq!(page.files.len(), 2);
    let file = &page.files[0];
    assert_eq!(file.path, "새 이름.rs");
    assert_eq!(file.previous_path.as_deref(), Some("old.rs"));
    assert_eq!((file.additions, file.deletions), (1, 1));
    let patch = file.patch.as_ref().unwrap();
    assert_eq!(patch.lines[1].old_line, Some(7));
    assert_eq!(patch.lines[2].new_line, Some(7));
    assert_eq!(patch.lines[2].text, "+새 코드");
    assert!(page.files[1].patch.is_none());
    conn.disconnect().await;
}

#[tokio::test]
async fn commits_keep_all_pages_full_messages_and_git_authors_without_accounts() {
    let (_tools, _data, _hc, conn, repo) = setup(None).await;
    let page = conn.github_pr_commits(repo, 42).await.unwrap();
    assert!(!page.limited);
    assert_eq!(page.commits.len(), 2);
    assert_eq!(page.commits[0].sha, "a".repeat(40));
    assert_eq!(page.commits[0].message, "Fix parser\n\n한국어 설명");
    assert_eq!(page.commits[0].author, "octocat");
    assert_eq!(page.commits[1].author, "Git author");
    assert_eq!(page.commits[1].created_at, "2026-11-04T00:01:00Z");
    conn.disconnect().await;
}

#[tokio::test]
async fn checks_use_pr_head_and_include_pending_runs_and_legacy_status_pages() {
    let (_tools, _data, _hc, conn, repo) = setup(None).await;
    let result = conn.github_pr_checks(repo, 42).await.unwrap();
    assert_eq!(result.head_sha, "c".repeat(40));
    assert_eq!(result.checks.len(), 4);
    assert_eq!(result.checks[0].name, "Android CI");
    assert_eq!(result.checks[0].status, "completed");
    assert_eq!(result.checks[0].conclusion.as_deref(), Some("failure"));
    assert_eq!(result.checks[1].status, "in_progress");
    assert!(result.checks[1].conclusion.is_none());
    assert_eq!(result.checks[2].name, "external/build");
    assert_eq!(result.checks[2].status, "pending");
    assert_eq!(result.checks[3].status, "success");
    assert_eq!(result.checks[3].description, "All good");
    conn.disconnect().await;
}

#[tokio::test]
async fn reviews_keep_history_markdown_and_nullable_authors_and_submission_dates() {
    let (_tools, _data, _hc, conn, repo) = setup(None).await;
    let reviews = conn.github_pr_reviews(repo, 42).await.unwrap();
    assert_eq!(reviews.len(), 3);
    assert_eq!(reviews[0].id, 9);
    assert_eq!(reviews[0].state, "CHANGES_REQUESTED");
    assert_eq!(reviews[0].body, "**Fix** this\n```rust\nlet n = 1;\n```");
    assert_eq!(reviews[0].author, "octocat");
    assert_eq!(reviews[0].submitted_at, "2026-11-04T00:00:00Z");
    assert_eq!(reviews[1].state, "DISMISSED");
    assert_eq!(reviews[1].author, "");
    assert_eq!(reviews[2].state, "PENDING");
    assert_eq!(reviews[2].submitted_at, "");
    assert_eq!(reviews[2].body, "");
    conn.disconnect().await;
}

#[tokio::test]
async fn empty_resources_are_valid_but_malformed_responses_are_errors() {
    for scenario in ["pr_empty", "pr_bad_json"] {
        let (_tools, _data, _hc, conn, repo) = setup(Some(scenario)).await;
        let files = conn.github_pr_files(repo.clone(), 42).await;
        let commits = conn.github_pr_commits(repo.clone(), 42).await;
        let checks = conn.github_pr_checks(repo.clone(), 42).await;
        let reviews = conn.github_pr_reviews(repo, 42).await;
        if scenario == "pr_empty" {
            assert!(files.unwrap().files.is_empty());
            assert!(commits.unwrap().commits.is_empty());
            assert!(checks.unwrap().checks.is_empty());
            assert!(reviews.unwrap().is_empty());
        } else {
            assert!(matches!(files, Err(aries_core::CoreError::Protocol { .. })));
            assert!(matches!(
                commits,
                Err(aries_core::CoreError::Protocol { .. })
            ));
            assert!(matches!(
                checks,
                Err(aries_core::CoreError::Protocol { .. })
            ));
            assert!(matches!(
                reviews,
                Err(aries_core::CoreError::Protocol { .. })
            ));
        }
        conn.disconnect().await;
    }
}

#[tokio::test]
async fn detail_uses_pull_request_branches_and_draft_or_merged_state() {
    for (scenario, expected) in [("pr_detail_draft", "draft"), ("pr_detail_merged", "merged")] {
        let (_tools, _data, _hc, conn, repo) = setup(Some(scenario)).await;
        let detail = conn.github_detail(repo, 42).await.unwrap();
        assert_eq!(detail.item.state, expected);
        assert_eq!(detail.head_branch.as_deref(), Some("feature/한글"));
        assert_eq!(detail.base_branch.as_deref(), Some("main"));
        assert_eq!(detail.comments.len(), 2);
        conn.disconnect().await;
    }
}
