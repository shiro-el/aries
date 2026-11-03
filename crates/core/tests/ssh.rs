//! The SSH transport against a real sshd in docker: key auth, trust on
//! first use, a changed Host key, and a login shell that prints a banner.
//!
//! Needs docker. Run with `cargo test -p aries-core --test ssh -- --ignored`
//! (CI does); skipped by a plain `cargo test`.

mod common;

use aries_core::{CoreError, Endpoint};
use aries_test_support::SshdHost;
use common::core_in;

fn endpoint(sshd: &SshdHost, user: &str) -> Endpoint {
    Endpoint::Ssh { address: "127.0.0.1".into(), port: sshd.port(), user: user.into() }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn trusts_the_host_key_on_first_use_then_refuses_a_changed_one() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    sshd.authorize(&core.ensure_key().unwrap().openssh);
    let host = core.add_host("box".into(), endpoint(&sshd, SshdHost::USER)).unwrap();

    // First connection: nothing trusted yet, so the core reports the key.
    let fingerprint = match core.connect(host.id.clone()).await {
        Err(CoreError::HostKeyUnknown { fingerprint }) => fingerprint,
        other => panic!("expected HostKeyUnknown, got {:?}", other.err()),
    };
    assert_eq!(fingerprint, sshd.host_key_fingerprint());

    core.trust_host_key(host.id.clone(), fingerprint.clone()).unwrap();
    let conn = core.connect(host.id.clone()).await.unwrap();
    assert_eq!(conn.terminals().await.unwrap(), vec![]);
    assert_eq!(conn.core_version(), "0.1.17");
    conn.close().await;

    // Trust survives a relaunch.
    drop(core);
    let core = core_in(data.path());
    core.connect(host.id.clone()).await.unwrap().close().await;

    // The Host now presents another key: warn and refuse.
    sshd.rotate_host_keys();
    match core.connect(host.id.clone()).await {
        Err(CoreError::HostKeyChanged { expected, actual }) => {
            assert_eq!(expected, fingerprint);
            assert_eq!(actual, sshd.host_key_fingerprint());
        }
        other => panic!("expected HostKeyChanged, got {:?}", other.err()),
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn works_through_a_login_shell_that_prints_a_banner() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    sshd.authorize(&core.ensure_key().unwrap().openssh);
    let host = core.add_host("noisy".into(), endpoint(&sshd, SshdHost::NOISY_USER)).unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint()).unwrap();

    let conn = core.connect(host.id).await.unwrap();
    assert_eq!(conn.terminals().await.unwrap(), vec![]);
    conn.close().await;
}

#[tokio::test]
#[ignore = "needs docker"]
async fn reports_a_key_the_host_does_not_accept() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap(); // never authorized
    let host = core.add_host("box".into(), endpoint(&sshd, SshdHost::USER)).unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint()).unwrap();

    assert!(matches!(core.connect(host.id).await, Err(CoreError::AuthRejected)));
}
