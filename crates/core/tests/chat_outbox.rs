//! Recovery storage must survive a restart without writing message text in clear.
mod common;

use aries_core::{CoreError, Endpoint};
use common::core_in;

#[test]
fn outbox_survives_restart_is_private_scoped_and_removed_with_its_host() {
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
    let message = r#"{"version":1,"messages":[{"text":"private queued message"}]}"#;
    core.save_chat_outbox(host.id.clone(), "remote/t1".into(), Some(message.into()))
        .unwrap();
    drop(core);
    let core = core_in(data.path());
    assert_eq!(
        core.chat_outbox(host.id.clone(), "remote/t1".into())
            .unwrap()
            .as_deref(),
        Some(message)
    );
    assert_eq!(
        core.chat_outbox(host.id.clone(), "t2".into()).unwrap(),
        None
    );
    let bytes = std::fs::read(
        data.path()
            .join("hosts")
            .join(&host.id)
            .join("chat-outbox.json"),
    )
    .unwrap();
    assert!(
        !bytes
            .windows(22)
            .any(|part| part == b"private queued message")
    );
    core.save_chat_outbox(host.id.clone(), "t2".into(), Some("other".into()))
        .unwrap();
    core.save_chat_outbox(host.id.clone(), "remote/t1".into(), None)
        .unwrap();
    assert_eq!(
        core.chat_outbox(host.id.clone(), "remote/t1".into())
            .unwrap(),
        None
    );
    assert_eq!(
        core.chat_outbox(host.id.clone(), "t2".into())
            .unwrap()
            .as_deref(),
        Some("other")
    );
    core.remove_host(host.id.clone()).unwrap();
    assert!(!data.path().join("hosts").join(&host.id).exists());
    assert!(matches!(
        core.save_chat_outbox(host.id, "t1".into(), Some("late".into())),
        Err(CoreError::UnknownHost { .. })
    ));
}

// D18: a malformed Terminal value must not hide another Terminal's saved draft.
#[test]
fn malformed_terminal_does_not_block_other_drafts_or_their_updates() {
    use aries_core::{KeyProtection, KeyVault};
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
    let path = data
        .path()
        .join("hosts")
        .join(&host.id)
        .join("chat-outbox.json");
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let mut bytes = b"aries-private-v1\0".to_vec();
    bytes.extend(
        common::TestVault
            .seal(
                br#"{"broken":{"version":2},"healthy":"saved draft"}"#.to_vec(),
                KeyProtection::Plain,
            )
            .unwrap(),
    );
    std::fs::write(&path, bytes).unwrap();
    assert_eq!(
        core.chat_outbox(host.id.clone(), "healthy".into())
            .unwrap()
            .as_deref(),
        Some("saved draft")
    );
    assert!(core.chat_outbox(host.id.clone(), "broken".into()).is_err());
    core.save_chat_outbox(
        host.id.clone(),
        "healthy".into(),
        Some("updated draft".into()),
    )
    .unwrap();
    assert_eq!(
        core.chat_outbox(host.id.clone(), "healthy".into())
            .unwrap()
            .as_deref(),
        Some("updated draft")
    );
    assert!(core.chat_outbox(host.id, "broken".into()).is_err());
}

// D18: discarding an unreadable Host journal must work without first opening/decrypting it.
#[test]
fn explicit_host_discard_recovers_from_an_unreadable_file() {
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
    core.save_chat_outbox(host.id.clone(), "t1".into(), Some("draft".into()))
        .unwrap();
    let path = data
        .path()
        .join("hosts")
        .join(&host.id)
        .join("chat-outbox.json");
    std::fs::write(&path, b"unreadable journal").unwrap();
    assert!(core.chat_outbox(host.id.clone(), "t1".into()).is_err());
    core.discard_chat_outbox(host.id.clone()).unwrap();
    core.discard_chat_outbox(host.id.clone()).unwrap();
    core.save_chat_outbox(host.id.clone(), "t2".into(), Some("new draft".into()))
        .unwrap();
    assert_eq!(
        core.chat_outbox(host.id.clone(), "t1".into()).unwrap(),
        None
    );
    assert_eq!(
        core.chat_outbox(host.id, "t2".into()).unwrap().as_deref(),
        Some("new draft")
    );
}
