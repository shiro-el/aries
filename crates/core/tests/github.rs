//! Public core API over a real, isolated herdr-core; gh is an external-service fake.
mod common;

use aries_core::{CoreError, GithubKind, GithubState};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};
use std::os::unix::fs::PermissionsExt;

fn gh_tools() -> tempfile::TempDir {
    let tools = tempfile::tempdir().unwrap();
    let gh = tools.path().join("gh");
    std::fs::write(&gh, include_str!("fixtures/github.py")).unwrap();
    std::fs::set_permissions(gh, std::fs::Permissions::from_mode(0o755)).unwrap();
    tools
}

#[tokio::test]
async fn missing_gh_has_actionable_error() {
    let tools = tempfile::tempdir().unwrap();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let address = hc.run_bg("github", &["/bin/sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    assert!(matches!(
        conn.github_repository(address).await,
        Err(CoreError::Rpc { kind, .. }) if kind == "github_missing"
    ));
    conn.disconnect().await;
}

#[tokio::test]
async fn github_search_parses_json_and_pages_in_the_selected_repository() {
    let tools = gh_tools();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let address = hc.run_bg("github", &["/bin/sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let repo = conn.github_repository(address).await.unwrap();
    assert_eq!(repo.name_with_owner, "octo/project");
    assert_eq!(repo.hostname, "github.example.com");
    let page = conn
        .github_list(
            repo,
            GithubKind::PullRequest,
            GithubState::Closed,
            "a b 한글".into(),
            2,
        )
        .await
        .unwrap();
    assert_eq!(page.items.len(), 1);
    assert_eq!(page.items[0].number, 42);
    assert_eq!(page.items[0].title, "한국어 PR");
    assert_eq!(page.items[0].author, "octocat");
    assert!(page.has_more);
    assert!(page.limited);
    conn.disconnect().await;
}

#[tokio::test]
async fn detail_keeps_markdown_deleted_authors_and_all_comment_pages() {
    let tools = gh_tools();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let address = hc.run_bg("github", &["/bin/sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let repo = conn.github_repository(address).await.unwrap();
    let detail = conn.github_detail(repo, 42).await.unwrap();
    assert_eq!(detail.item.body, "# Hello\n```rust\nlet n = 1;\n```");
    assert_eq!(detail.item.author, "");
    assert_eq!(detail.item.kind, GithubKind::Issue);
    assert!(detail.head_branch.is_none());
    assert!(detail.base_branch.is_none());
    assert_eq!(detail.comments.len(), 2);
    assert_eq!(detail.comments[0].body, "**first**");
    assert_eq!(detail.comments[1].body, "한국어 댓글");
    assert_eq!(detail.comments[1].author, "");
    conn.disconnect().await;
}

#[tokio::test]
async fn comments_preserve_one_literal_body_argument_and_issue_state_changes_are_explicit() {
    let tools = gh_tools();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let address = hc.run_bg("github", &["/bin/sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let repo = conn.github_repository(address).await.unwrap();
    for kind in [GithubKind::Issue, GithubKind::PullRequest] {
        conn.github_comment(
            repo.clone(),
            kind,
            42,
            "--flag '한글'\n$(exit 99); `exit 98` **markdown**".into(),
        )
        .await
        .unwrap();
    }
    conn.github_set_issue_closed(repo.clone(), 42, true)
        .await
        .unwrap();
    conn.github_set_issue_closed(repo.clone(), 42, false)
        .await
        .unwrap();
    assert!(
        conn.github_comment(repo, GithubKind::Issue, 42, " \n".into())
            .await
            .is_err()
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn unauthenticated_and_malformed_responses_do_not_become_empty_lists() {
    let tools = gh_tools();
    for (scenario, expected) in [
        ("auth", "github_auth"),
        ("bad_json", "protocol"),
        ("failure", "github_failed"),
    ] {
        let hc = HcSession::start_with_env(&[
            ("PATH", tools.path().to_str().unwrap()),
            ("GH_TEST_SCENARIO", scenario),
        ]);
        let address = hc.run_bg("github", &["/bin/sleep", "300"]);
        let data = tempfile::tempdir().unwrap();
        let core = core_in(data.path());
        let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
        let conn = core.connect(host.id).await.unwrap();
        let err = conn.github_repository(address).await.unwrap_err();
        match err {
            CoreError::Rpc { kind, .. } => assert_eq!(kind, expected),
            CoreError::Protocol { .. } => assert_eq!(expected, "protocol"),
            other => panic!("unexpected error: {other}"),
        }
        conn.disconnect().await;
    }
}

#[tokio::test]
async fn drafts_survive_reopening_and_are_scoped_to_host_machine_repo_and_number() {
    let tools = gh_tools();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let address = hc.run_bg("github", &["/bin/sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id.clone()).await.unwrap();
    let repo = conn.github_repository(address).await.unwrap();
    conn.github_save_draft(repo.clone(), 42, "**unfinished** 한글".into())
        .unwrap();
    assert_eq!(conn.github_load_draft(repo.clone(), 43).unwrap(), "");
    let mut other = repo.clone();
    other.on = Some("remote".into());
    assert_eq!(conn.github_load_draft(other, 42).unwrap(), "");
    conn.disconnect().await;
    let reopened = core_in(data.path());
    let connection = reopened.connect(host.id.clone()).await.unwrap();
    assert_eq!(
        connection.github_load_draft(repo.clone(), 42).unwrap(),
        "**unfinished** 한글"
    );
    reopened.remove_host(host.id).unwrap();
    assert!(connection.github_load_draft(repo.clone(), 42).is_err());
    assert!(
        connection
            .github_save_draft(repo, 42, "late write".into())
            .is_err()
    );
    connection.disconnect().await;
}
