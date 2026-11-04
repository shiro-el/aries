//! Phone-local input history through the core API; no Host connection required.
mod common;

use aries_core::Endpoint;
use common::core_in;

#[test]
fn sent_input_survives_reopening_verbatim_and_is_scoped_to_host_and_terminal() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "home".into(),
            Endpoint::Ssh {
                address: "offline.invalid".into(),
                port: 22,
                user: "me".into(),
            },
        )
        .unwrap();
    let other = core
        .add_host(
            "work".into(),
            Endpoint::Ssh {
                address: "other.invalid".into(),
                port: 22,
                user: "me".into(),
            },
        )
        .unwrap();
    let text = "  echo '한국어'\nsecond line\n";
    core.remember_input(host.id.clone(), "t1".into(), text.into())
        .unwrap();
    drop(core);
    let core = core_in(data.path());
    assert_eq!(
        core.input_history(host.id.clone(), "t1".into()).unwrap(),
        [text]
    );
    assert!(core.input_history(host.id, "t2".into()).unwrap().is_empty());
    assert!(
        core.input_history(other.id, "t1".into())
            .unwrap()
            .is_empty()
    );
}

#[test]
fn removing_host_deletes_its_history_and_rejects_further_writes() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "home".into(),
            Endpoint::Ssh {
                address: "offline.invalid".into(),
                port: 22,
                user: "me".into(),
            },
        )
        .unwrap();
    core.remember_input(host.id.clone(), "machine/t1".into(), "old input".into())
        .unwrap();
    core.remove_host(host.id.clone()).unwrap();
    assert!(!data.path().join("hosts").join(&host.id).exists());
    assert!(matches!(
        core.remember_input(host.id, "t1".into(), "late input".into()),
        Err(aries_core::CoreError::UnknownHost { .. })
    ));
}

#[test]
fn keeps_last_fifty_submissions_and_clear_is_persistent_and_terminal_local() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "home".into(),
            Endpoint::Ssh {
                address: "offline.invalid".into(),
                port: 22,
                user: "me".into(),
            },
        )
        .unwrap();
    core.remember_input(host.id.clone(), "t2".into(), "keep me".into())
        .unwrap();
    for i in 0..52 {
        core.remember_input(host.id.clone(), "t1".into(), format!("command {i}"))
            .unwrap();
    }
    let history = core.input_history(host.id.clone(), "t1".into()).unwrap();
    assert_eq!(history.len(), 50);
    assert_eq!(history.first().unwrap(), "command 51");
    assert_eq!(history.last().unwrap(), "command 2");
    core.clear_input_history(host.id.clone(), "t1".into())
        .unwrap();
    core.clear_input_history(host.id.clone(), "t1".into())
        .unwrap();
    drop(core);
    let core = core_in(data.path());
    assert!(
        core.input_history(host.id.clone(), "t1".into())
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        core.input_history(host.id, "t2".into()).unwrap(),
        ["keep me"]
    );
}

#[test]
fn upgrade_and_new_submissions_leave_no_plaintext_input_files() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "home".into(),
            Endpoint::Ssh {
                address: "offline.invalid".into(),
                port: 22,
                user: "me".into(),
            },
        )
        .unwrap();
    let dir = data.path().join("hosts").join(&host.id);
    std::fs::create_dir_all(dir.join("github-drafts")).unwrap();
    std::fs::write(
        dir.join("input-history.json"),
        br#"{"t1":["legacy-secret-input"]}"#,
    )
    .unwrap();
    std::fs::write(dir.join("input-history.json.tmp"), "interrupted-secret").unwrap();
    std::fs::write(dir.join("github-drafts/old.txt"), "legacy-secret-draft").unwrap();
    std::fs::write(dir.join("github-drafts/old.tmp"), "interrupted-secret").unwrap();
    drop(core);
    let core = core_in(data.path());
    assert_eq!(
        core.input_history(host.id.clone(), "t1".into()).unwrap(),
        ["legacy-secret-input"]
    );
    core.remember_input(host.id.clone(), "t1".into(), "new-secret-input".into())
        .unwrap();
    fn inspect(path: &std::path::Path) {
        for entry in std::fs::read_dir(path).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                inspect(&path);
            } else {
                let bytes = std::fs::read(&path).unwrap();
                for secret in [
                    "legacy-secret-input",
                    "new-secret-input",
                    "legacy-secret-draft",
                    "interrupted-secret",
                ] {
                    assert!(
                        !bytes.windows(secret.len()).any(|w| w == secret.as_bytes()),
                        "plaintext in {}",
                        path.display()
                    );
                }
            }
        }
    }
    inspect(data.path());
    assert!(!dir.join("input-history.json.tmp").exists());
    assert!(!dir.join("github-drafts/old.tmp").exists());
}
