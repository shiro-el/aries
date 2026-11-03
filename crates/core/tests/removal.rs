//! Seam 1: removing a Host forgets its trusted key, its connection settings
//! and its local state, and the rest survive.

mod common;

use aries_core::{CoreError, Endpoint};
use common::core_in;

fn ssh(address: &str) -> Endpoint {
    Endpoint::Ssh {
        address: address.into(),
        port: 22,
        user: "me".into(),
    }
}

#[test]
fn removes_the_host_its_trusted_key_and_its_local_state() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let gone = core.add_host("gone".into(), ssh("gone.example")).unwrap();
    let kept = core.add_host("kept".into(), ssh("kept.example")).unwrap();
    core.trust_host_key(gone.id.clone(), "SHA256:gonekey".into())
        .unwrap();
    core.trust_host_key(kept.id.clone(), "SHA256:keptkey".into())
        .unwrap();
    // Per-Host local state lives under `hosts/<id>/` (caches, pairing, ...).
    let gone_dir = data.path().join("hosts").join(&gone.id);
    let kept_dir = data.path().join("hosts").join(&kept.id);
    for dir in [&gone_dir, &kept_dir] {
        std::fs::create_dir_all(dir.join("cache")).unwrap();
        std::fs::write(dir.join("cache/chat.db"), b"x").unwrap();
    }

    core.remove_host(gone.id.clone()).unwrap();

    let ids: Vec<_> = core.hosts().into_iter().map(|h| h.id).collect();
    assert_eq!(ids, vec![kept.id.clone()]);
    assert!(
        !gone_dir.exists(),
        "local state of the removed Host remains"
    );
    assert!(kept_dir.join("cache/chat.db").exists());

    // Nothing of it is left at rest, and it stays gone after a relaunch.
    let saved = std::fs::read_to_string(data.path().join("hosts.json")).unwrap();
    assert!(!saved.contains("SHA256:gonekey"), "{saved}");
    assert!(!saved.contains("gone.example"), "{saved}");
    drop(core);
    let core = core_in(data.path());
    let hosts = core.hosts();
    assert_eq!(hosts.len(), 1);
    assert_eq!(hosts[0].host_key.as_deref(), Some("SHA256:keptkey"));
}

#[test]
fn removes_a_host_that_never_stored_local_state() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("bare".into(), ssh("bare.example")).unwrap();

    core.remove_host(host.id).unwrap();

    assert!(core.hosts().is_empty());
}

#[test]
fn removing_an_unknown_host_is_an_error() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    assert!(matches!(
        core.remove_host("nope".into()),
        Err(CoreError::UnknownHost { id }) if id == "nope"
    ));
}
