//! Seam 1: extra SSH keys and multi-hop jump hosts are registered, edited and
//! survive a relaunch. Connecting through them is in `ssh.rs` (docker).

mod common;

use aries_core::{CoreError, DEFAULT_KEY_ID, Endpoint, JumpHost};
use common::core_in;

fn ssh(address: &str) -> Endpoint {
    Endpoint::Ssh {
        address: address.into(),
        port: 22,
        user: "me".into(),
    }
}

fn hop(address: &str, key_id: Option<&str>) -> JumpHost {
    JumpHost {
        address: address.into(),
        port: 22,
        user: "jump".into(),
        key_id: key_id.map(str::to_owned),
        host_key: None,
    }
}

#[test]
fn creates_more_keys_next_to_the_default_one() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    assert_eq!(core.keys().unwrap(), vec![]);

    let default = core.ensure_key().unwrap();
    let bastion = core.create_key("bastion".into()).unwrap();
    assert_eq!(bastion.label, "bastion");
    assert!(bastion.openssh.starts_with("ssh-ed25519 AAAA"));
    assert_ne!(bastion.fingerprint, default.fingerprint);

    let keys = core.keys().unwrap();
    assert_eq!(keys.len(), 2);
    assert_eq!(keys[0].id, DEFAULT_KEY_ID);
    assert_eq!(keys[0].fingerprint, default.fingerprint);
    assert_eq!(keys[1], bastion);

    drop(core);
    assert_eq!(core_in(data.path()).keys().unwrap(), keys);
}

#[test]
fn never_writes_an_extra_private_key_in_the_clear() {
    let data = tempfile::tempdir().unwrap();
    core_in(data.path()).create_key("bastion".into()).unwrap();
    for entry in std::fs::read_dir(data.path()).unwrap() {
        let bytes = std::fs::read(entry.unwrap().path()).unwrap();
        assert!(!String::from_utf8_lossy(&bytes).contains("PRIVATE KEY"));
    }
}

#[test]
fn registers_and_edits_jump_hosts_with_a_key_each() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap();
    let k1 = core.create_key("outer".into()).unwrap();
    let k2 = core.create_key("inner".into()).unwrap();
    let host = core.add_host("work".into(), ssh("10.1.0.5")).unwrap();
    assert_eq!(core.jump_hosts(host.id.clone()).unwrap(), vec![]);

    let jumps = vec![
        hop("bastion.example", Some(&k1.id)),
        hop("10.0.0.1", Some(&k2.id)),
    ];
    core.set_jump_hosts(host.id.clone(), jumps.clone()).unwrap();
    assert_eq!(core.jump_hosts(host.id.clone()).unwrap(), jumps);

    core.set_host_ssh_key(host.id.clone(), Some(k2.id.clone()))
        .unwrap();
    assert_eq!(
        core.host_ssh_key(host.id.clone()).unwrap(),
        Some(k2.id.clone())
    );

    // Edit: drop the inner hop, add one in front. Survives a relaunch.
    let edited = vec![
        hop("edge.example", None),
        hop("bastion.example", Some(&k1.id)),
    ];
    core.set_jump_hosts(host.id.clone(), edited.clone())
        .unwrap();
    drop(core);
    let core = core_in(data.path());
    assert_eq!(core.jump_hosts(host.id.clone()).unwrap(), edited);
    assert_eq!(core.host_ssh_key(host.id).unwrap(), Some(k2.id));
}

#[test]
fn keeps_a_hops_trust_while_its_address_stays_and_drops_it_otherwise() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("work".into(), ssh("10.1.0.5")).unwrap();
    core.set_jump_hosts(
        host.id.clone(),
        vec![hop("a.example", None), hop("b.example", None)],
    )
    .unwrap();
    core.trust_jump_host_key(host.id.clone(), 0, "SHA256:aaa".into())
        .unwrap();
    core.trust_jump_host_key(host.id.clone(), 1, "SHA256:bbb".into())
        .unwrap();
    let trusted: Vec<_> = core
        .jump_hosts(host.id.clone())
        .unwrap()
        .into_iter()
        .map(|h| h.host_key)
        .collect();
    assert_eq!(
        trusted,
        vec![Some("SHA256:aaa".into()), Some("SHA256:bbb".into())]
    );

    // The editor cannot smuggle in a fingerprint: trust only carries over from
    // the hop with the same address and port, wherever it moved.
    let mut forged = hop("c.example", None);
    forged.host_key = Some("SHA256:forged".into());
    let mut b_other_user = hop("b.example", None);
    b_other_user.user = "other".into();
    core.set_jump_hosts(host.id.clone(), vec![b_other_user, forged])
        .unwrap();
    let trusted: Vec<_> = core
        .jump_hosts(host.id)
        .unwrap()
        .into_iter()
        .map(|h| h.host_key)
        .collect();
    assert_eq!(trusted, vec![Some("SHA256:bbb".into()), None]);
}

#[test]
fn rejects_keys_and_hops_that_do_not_exist() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("work".into(), ssh("10.1.0.5")).unwrap();

    assert!(matches!(
        core.set_jump_hosts(host.id.clone(), vec![hop("a", Some("nope"))]),
        Err(CoreError::UnknownKey { id }) if id == "nope"
    ));
    assert!(matches!(
        core.set_host_ssh_key(host.id.clone(), Some("nope".into())),
        Err(CoreError::UnknownKey { .. })
    ));
    assert!(matches!(
        core.trust_jump_host_key(host.id.clone(), 0, "SHA256:x".into()),
        Err(CoreError::UnknownJumpHost { hop: 0 })
    ));
    assert!(matches!(
        core.set_jump_hosts("missing".into(), vec![]),
        Err(CoreError::UnknownHost { .. })
    ));
    assert!(core.jump_hosts(host.id).unwrap().is_empty());
}
