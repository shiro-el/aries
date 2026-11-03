//! Host edits keep identity and local state, and invalidate only changed endpoints.
mod common;

use aries_core::{CoreError, Endpoint, JumpHost, LaunchRequest, SavedLaunch};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

fn ssh(address: &str, port: u16, user: &str) -> Endpoint {
    Endpoint::Ssh {
        address: address.into(),
        port,
        user: user.into(),
    }
}

#[tokio::test]
async fn edit_keeps_id_routes_keys_and_every_per_host_file_after_relaunch() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host("old".into(), ssh("old.example", 22, "me"))
        .unwrap();
    let key = core.create_key("work".into()).unwrap();
    core.set_host_ssh_key(host.id.clone(), Some(key.id.clone()))
        .unwrap();
    let jump = JumpHost {
        address: "jump.example".into(),
        port: 22,
        user: "jump".into(),
        key_id: Some(key.id.clone()),
        host_key: None,
    };
    core.set_jump_hosts(host.id.clone(), vec![jump]).unwrap();
    core.trust_jump_host_key(host.id.clone(), 0, "SHA256:jump".into())
        .unwrap();
    core.trust_host_key(host.id.clone(), "SHA256:old".into())
        .unwrap();
    let routes = core.jump_hosts(host.id.clone()).unwrap();
    // All Host-bound state (pairing, cache, launch history, etc.) lives here.
    let host_dir = data.path().join("hosts").join(&host.id);
    std::fs::create_dir_all(&host_dir).unwrap();
    for name in [
        "push.json",
        "chat-cache",
        "saved-launches.json",
        "recent-cwds.json",
    ] {
        std::fs::write(host_dir.join(name), b"preserve verbatim").unwrap();
    }
    let changed = core
        .edit_host(
            host.id.clone(),
            "new".into(),
            ssh("new.example", 2222, "dev"),
        )
        .await
        .unwrap();
    assert_eq!(changed.id, host.id);
    assert_eq!(changed.host_key, None);
    let reloaded = core_in(data.path());
    assert_eq!(reloaded.hosts(), vec![changed]);
    assert_eq!(reloaded.jump_hosts(host.id.clone()).unwrap(), routes);
    assert_eq!(reloaded.host_ssh_key(host.id).unwrap(), Some(key.id));
    for name in [
        "push.json",
        "chat-cache",
        "saved-launches.json",
        "recent-cwds.json",
    ] {
        assert_eq!(
            std::fs::read(host_dir.join(name)).unwrap(),
            b"preserve verbatim"
        );
    }
}

#[tokio::test]
async fn validates_add_and_edit_without_changing_the_original() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let original = core.add_host("first".into(), ssh("box", 22, "me")).unwrap();
    core.add_host("taken".into(), ssh("other", 22, "me"))
        .unwrap();
    for (name, endpoint) in [
        (" ", ssh("box", 22, "me")),
        ("taken", ssh("box", 22, "me")),
        ("ok", ssh(" ", 22, "me")),
        ("ok", ssh("bad address", 22, "me")),
        ("ok", ssh("box", 0, "me")),
        ("ok", ssh("box", 22, " ")),
    ] {
        assert!(matches!(
            core.edit_host(original.id.clone(), name.into(), endpoint.clone())
                .await,
            Err(CoreError::InvalidHost { .. })
        ));
        assert!(matches!(
            core.add_host(name.into(), endpoint),
            Err(CoreError::InvalidHost { .. })
        ));
        assert_eq!(core.hosts()[0], original);
        assert_eq!(core_in(data.path()).hosts(), core.hosts());
    }
    assert!(matches!(
        core.edit_host("missing".into(), "ok".into(), ssh("box", 22, "me"))
            .await,
        Err(CoreError::UnknownHost { .. })
    ));
    // The same Host is excluded from duplicate checking.
    core.edit_host(original.id, "first".into(), ssh("box", 22, "me"))
        .await
        .unwrap();
}

#[tokio::test]
async fn every_ssh_endpoint_change_requires_new_trust_but_rename_keeps_trust() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("box".into(), ssh("box", 22, "me")).unwrap();
    core.trust_host_key(host.id.clone(), "SHA256:key".into())
        .unwrap();
    let renamed = core
        .edit_host(host.id.clone(), "renamed".into(), host.endpoint.clone())
        .await
        .unwrap();
    assert_eq!(renamed.host_key.as_deref(), Some("SHA256:key"));
    for endpoint in [
        ssh("other", 22, "me"),
        ssh("other", 2222, "me"),
        ssh("other", 2222, "dev"),
    ] {
        core.trust_host_key(host.id.clone(), "SHA256:key".into())
            .unwrap();
        assert_eq!(
            core.edit_host(host.id.clone(), "renamed".into(), endpoint)
                .await
                .unwrap()
                .host_key,
            None
        );
    }
}

#[tokio::test]
async fn endpoint_edit_closes_all_old_connections_and_next_connect_uses_new_endpoint() {
    let old = HcSession::start();
    let new = HcSession::start();
    new.run_bg("new-host", &["sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("box".into(), local_endpoint(&old)).unwrap();
    let first = core.connect(host.id.clone()).await.unwrap();
    let second = core.connect(host.id.clone()).await.unwrap();
    let saved = first
        .save_launch(SavedLaunch {
            id: String::new(),
            name: "kept command".into(),
            request: LaunchRequest {
                command: "echo hello".into(),
                cwd: None,
                space_id: None,
                tab_id: None,
                preset: None,
            },
        })
        .unwrap();
    core.edit_host(host.id.clone(), "renamed".into(), host.endpoint)
        .await
        .unwrap();
    first.terminals().await.unwrap();
    core.edit_host(host.id.clone(), "renamed".into(), local_endpoint(&new))
        .await
        .unwrap();
    assert!(matches!(first.terminals().await, Err(CoreError::Closed)));
    assert!(matches!(second.terminals().await, Err(CoreError::Closed)));
    let conn = core.connect(host.id).await.unwrap();
    assert_eq!(conn.saved_launches(), vec![saved]);
    assert_eq!(
        conn.terminals().await.unwrap()[0].name.as_deref(),
        Some("new-host")
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn endpoint_edit_ends_streams_on_the_old_connection() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("box".into(), local_endpoint(&hc)).unwrap();
    let connection = core.connect(host.id.clone()).await.unwrap();
    let watch = connection.watch_terminals().await.unwrap();
    watch.next().await.unwrap();
    core.edit_host(host.id, "box".into(), common::script_endpoint("exit 1"))
        .await
        .unwrap();
    let result = tokio::time::timeout(std::time::Duration::from_secs(1), watch.next()).await;
    assert!(
        matches!(result, Ok(Err(CoreError::Closed))),
        "old stream should end, got {result:?}"
    );
}

#[tokio::test]
async fn failed_persistence_keeps_settings_trust_and_live_connection() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("box".into(), local_endpoint(&hc)).unwrap();
    core.trust_host_key(host.id.clone(), "SHA256:key".into())
        .unwrap();
    let before = core.hosts();
    let connection = core.connect(host.id.clone()).await.unwrap();
    std::fs::remove_file(data.path().join("hosts.json")).unwrap();
    std::fs::create_dir(data.path().join("hosts.json")).unwrap();
    assert!(matches!(
        core.edit_host(host.id, "changed".into(), common::script_endpoint("exit 1"))
            .await,
        Err(CoreError::Storage { .. })
    ));
    assert_eq!(core.hosts(), before);
    connection.terminals().await.unwrap();
    connection.disconnect().await;
}

#[tokio::test]
async fn an_in_flight_connection_to_the_old_address_cannot_escape_an_edit() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let started = data.path().join("started");
    let proceed = data.path().join("proceed");
    let Endpoint::Local { argv, env } = local_endpoint(&hc) else {
        unreachable!()
    };
    let script = format!(
        "touch '{}'; while [ ! -f '{}' ]; do sleep 0.01; done; exec \"$@\"",
        started.display(),
        proceed.display()
    );
    let mut delayed = vec![
        "/bin/sh".into(),
        "-c".into(),
        script,
        "delayed-connect".into(),
    ];
    delayed.extend(argv);
    let host = core
        .add_host("box".into(), Endpoint::Local { argv: delayed, env })
        .unwrap();
    let task = tokio::spawn({
        let core = core.clone();
        let id = host.id.clone();
        async move { core.connect(id).await }
    });
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while !started.exists() {
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    core.edit_host(host.id, "box".into(), local_endpoint(&hc))
        .await
        .unwrap();
    std::fs::write(proceed, b"go").unwrap();
    assert!(matches!(task.await.unwrap(), Err(CoreError::Closed)));
}
