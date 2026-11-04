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
