//! Core API seam: real isolated herdr-core, a fake herdr-app on its PATH.
mod common;

use aries_core::LayoutEdit;
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};
use std::os::unix::fs::PermissionsExt;

#[tokio::test]
async fn edits_pass_names_as_one_json_argument_without_a_shell() {
    let tools = tempfile::tempdir().unwrap();
    let app = tools.path().join("herdr-app");
    std::fs::write(&app, "#!/bin/sh\nif [ \"$1\" = status ]; then printf '{}'; exit 0; fi\n[ \"$#\" = 3 ] && [ \"$1\" = do ] || exit 2\nprintf '%s\\n%s' \"$2\" \"$3\"\n").unwrap();
    std::fs::set_permissions(app, std::fs::Permissions::from_mode(0o755)).unwrap();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    assert!(conn.herdr_app_available().await.unwrap());
    let name = "한글 ' \" $(touch /tmp/aries-86-should-never-exist)\nnext";
    let result = conn
        .edit_layout(LayoutEdit::RenameSpace {
            id: "s1".into(),
            name: name.into(),
        })
        .await
        .unwrap();
    let (command, args) = result.split_once('\n').unwrap();
    assert_eq!(command, "space.rename");
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(args).unwrap(),
        serde_json::json!({"id":"s1", "name":name})
    );
    for (edit, expected_command, expected_args) in [
        (
            LayoutEdit::NewSpace {
                name: "".into(),
                folder: Some("/repo '한글'".into()),
            },
            "space.new",
            serde_json::json!({"name":"", "folder":"/repo '한글'"}),
        ),
        (
            LayoutEdit::CloseSpace { id: "s1".into() },
            "space.close",
            serde_json::json!({"id":"s1", "confirmed":true}),
        ),
        (
            LayoutEdit::NewTab {
                space_id: "s1".into(),
                name: "task".into(),
                folder: Some("/repo".into()),
                argv: Some(vec!["codex".into(), "a b".into()]),
            },
            "tab.new",
            serde_json::json!({"space":"s1", "name":"task", "folder":"/repo", "argv":["codex", "a b"]}),
        ),
        (
            LayoutEdit::RenameTab {
                id: "tab1".into(),
                name: "".into(),
            },
            "tab.rename",
            serde_json::json!({"id":"tab1", "name":""}),
        ),
        (
            LayoutEdit::CloseTab { id: "tab1".into() },
            "tab.close",
            serde_json::json!({"id":"tab1", "confirmed":true}),
        ),
    ] {
        let result = conn.edit_layout(edit).await.unwrap();
        let (command, args) = result.split_once('\n').unwrap();
        assert_eq!(command, expected_command);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(args).unwrap(),
            expected_args
        );
    }
    conn.disconnect().await;
}

#[tokio::test]
async fn missing_or_stopped_app_disables_edits_and_terminal_kill_is_independent() {
    let tools = tempfile::tempdir().unwrap();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let address = hc.run_bg("build", &["/bin/sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    assert!(!conn.herdr_app_available().await.unwrap());
    assert!(
        conn.edit_layout(LayoutEdit::CloseSpace { id: "s1".into() })
            .await
            .is_err()
    );
    let app = tools.path().join("herdr-app");
    std::fs::write(&app, "#!/bin/sh\nexit 1\n").unwrap();
    std::fs::set_permissions(app, std::fs::Permissions::from_mode(0o755)).unwrap();
    assert!(!conn.herdr_app_available().await.unwrap());
    let killed = conn.kill_terminal(address.clone()).await.unwrap();
    assert_eq!(killed.address, address);
    assert_eq!(killed.state, "finished");
    assert!(conn.terminals().await.unwrap().is_empty());
    conn.disconnect().await;
}

#[tokio::test]
async fn app_command_errors_reach_the_caller_instead_of_becoming_empty_errors() {
    let tools = tempfile::tempdir().unwrap();
    let app = tools.path().join("herdr-app");
    std::fs::write(
        &app,
        "#!/bin/sh\nprintf '%s' '{\"error\":\"no such space\"}'\nexit 1\n",
    )
    .unwrap();
    std::fs::set_permissions(app, std::fs::Permissions::from_mode(0o755)).unwrap();
    let hc = HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let error = conn
        .edit_layout(LayoutEdit::CloseSpace {
            id: "missing".into(),
        })
        .await
        .unwrap_err();
    assert!(error.to_string().contains("no such space"), "{error}");
    conn.disconnect().await;
}
