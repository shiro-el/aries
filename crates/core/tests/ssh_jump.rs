//! Multi-hop jump hosts against real sshds in docker: the phone reaches only
//! the outer bastion; the inner bastion and the Host are reachable only by
//! name on a docker network. Each hop has its own key and Host key.
//!
//! Needs docker. Run with `cargo test -p aries-core --test ssh_jump -- --ignored`.

mod common;

use aries_core::{CoreError, Endpoint, JumpHost};
use aries_test_support::{DockerNetwork, SshdHost};
use common::core_in;

fn jump(address: &str, port: u16, key_id: &str) -> JumpHost {
    JumpHost {
        address: address.into(),
        port,
        user: SshdHost::USER.into(),
        key_id: Some(key_id.into()),
        host_key: None,
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn connects_through_two_jump_hosts_with_a_key_and_trust_each() {
    let net = DockerNetwork::create();
    let outer = SshdHost::start_on(&net);
    let inner = SshdHost::start_on(&net);
    let target = SshdHost::start_on(&net);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let default = core.ensure_key().unwrap();
    let outer_key = core.create_key("outer".into()).unwrap();
    let inner_key = core.create_key("inner".into()).unwrap();
    // Each sshd accepts only its own key.
    outer.authorize(&outer_key.openssh);
    inner.authorize(&inner_key.openssh);
    target.authorize(&default.openssh);

    let host = core
        .add_host(
            "behind".into(),
            Endpoint::Ssh {
                address: target.network_name().into(),
                port: 22,
                user: SshdHost::USER.into(),
            },
        )
        .unwrap();
    core.set_jump_hosts(
        host.id.clone(),
        vec![
            jump("127.0.0.1", outer.port(), &outer_key.id),
            jump(inner.network_name(), 22, &inner_key.id),
        ],
    )
    .unwrap();

    // Trust on first use, one hop at a time, outermost first.
    match core.connect(host.id.clone()).await {
        Err(CoreError::JumpHostKeyUnknown {
            hop: 0,
            fingerprint,
            ..
        }) => {
            assert_eq!(fingerprint, outer.host_key_fingerprint());
            core.trust_jump_host_key(host.id.clone(), 0, fingerprint)
                .unwrap();
        }
        other => panic!("expected JumpHostKeyUnknown(0), got {:?}", other.err()),
    }
    match core.connect(host.id.clone()).await {
        Err(CoreError::JumpHostKeyUnknown {
            hop: 1,
            address,
            fingerprint,
        }) => {
            assert_eq!(address, inner.network_name());
            assert_eq!(fingerprint, inner.host_key_fingerprint());
            core.trust_jump_host_key(host.id.clone(), 1, fingerprint)
                .unwrap();
        }
        other => panic!("expected JumpHostKeyUnknown(1), got {:?}", other.err()),
    }
    match core.connect(host.id.clone()).await {
        Err(CoreError::HostKeyUnknown { fingerprint }) => {
            assert_eq!(fingerprint, target.host_key_fingerprint());
            core.trust_host_key(host.id.clone(), fingerprint).unwrap();
        }
        other => panic!("expected HostKeyUnknown, got {:?}", other.err()),
    }

    let conn = core.connect(host.id.clone()).await.unwrap();
    assert_eq!(conn.terminals().await.unwrap(), vec![]);
    assert_eq!(conn.core_version(), "0.1.17");
    conn.disconnect().await;

    // The inner bastion is reinstalled or impersonated: warn about that hop.
    let trusted_inner = inner.host_key_fingerprint();
    inner.rotate_host_keys();
    match core.connect(host.id.clone()).await {
        Err(CoreError::JumpHostKeyChanged {
            hop: 1,
            expected,
            actual,
            ..
        }) => {
            assert_eq!(expected, trusted_inner);
            assert_eq!(actual, inner.host_key_fingerprint());
        }
        other => panic!("expected JumpHostKeyChanged(1), got {:?}", other.err()),
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn names_the_jump_host_that_rejected_its_key() {
    let net = DockerNetwork::create();
    let outer = SshdHost::start_on(&net);
    let target = SshdHost::start_on(&net);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let default = core.ensure_key().unwrap();
    let outer_key = core.create_key("outer".into()).unwrap();
    // The bastion only knows the default key, not the one chosen for it.
    outer.authorize(&default.openssh);
    target.authorize(&default.openssh);

    let host = core
        .add_host(
            "behind".into(),
            Endpoint::Ssh {
                address: target.network_name().into(),
                port: 22,
                user: SshdHost::USER.into(),
            },
        )
        .unwrap();
    core.set_jump_hosts(
        host.id.clone(),
        vec![jump("127.0.0.1", outer.port(), &outer_key.id)],
    )
    .unwrap();
    core.trust_jump_host_key(host.id.clone(), 0, outer.host_key_fingerprint())
        .unwrap();
    core.trust_host_key(host.id.clone(), target.host_key_fingerprint())
        .unwrap();

    match core.connect(host.id.clone()).await {
        Err(CoreError::JumpAuthRejected { hop: 0, address }) => assert_eq!(address, "127.0.0.1"),
        other => panic!("expected JumpAuthRejected(0), got {:?}", other.err()),
    }

    // Switching the hop to the default key works.
    core.set_jump_hosts(
        host.id.clone(),
        vec![jump("127.0.0.1", outer.port(), aries_core::DEFAULT_KEY_ID)],
    )
    .unwrap();
    let conn = core.connect(host.id).await.unwrap();
    assert_eq!(conn.terminals().await.unwrap(), vec![]);
    conn.disconnect().await;
}
