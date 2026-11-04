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
    Endpoint::Ssh {
        address: "127.0.0.1".into(),
        port: sshd.port(),
        user: user.into(),
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn trusts_the_host_key_on_first_use_then_refuses_a_changed_one() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    sshd.authorize(&core.ensure_key().unwrap().openssh);
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::USER))
        .unwrap();

    // First connection: nothing trusted yet, so the core reports the key.
    let fingerprint = match core.connect(host.id.clone()).await {
        Err(CoreError::HostKeyUnknown { fingerprint }) => fingerprint,
        other => panic!("expected HostKeyUnknown, got {:?}", other.err()),
    };
    assert_eq!(fingerprint, sshd.host_key_fingerprint());

    core.trust_host_key(host.id.clone(), fingerprint.clone())
        .unwrap();
    let conn = core.connect(host.id.clone()).await.unwrap();
    assert_eq!(conn.terminals().await.unwrap(), vec![]);
    assert_eq!(conn.core_version(), "0.1.17");
    conn.disconnect().await;

    // Trust survives a relaunch.
    drop(core);
    let core = core_in(data.path());
    core.connect(host.id.clone())
        .await
        .unwrap()
        .disconnect()
        .await;

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
    let host = core
        .add_host("noisy".into(), endpoint(&sshd, SshdHost::NOISY_USER))
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();

    let conn = core.connect(host.id).await.unwrap();
    assert_eq!(conn.terminals().await.unwrap(), vec![]);
    conn.disconnect().await;
}

#[tokio::test]
#[ignore = "needs docker"]
async fn reports_a_key_the_host_does_not_accept() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap(); // never authorized
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::USER))
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();

    assert!(matches!(
        core.connect(host.id).await,
        Err(CoreError::AuthRejected)
    ));
}

#[tokio::test]
#[ignore = "needs docker"]
async fn a_removed_host_added_again_asks_to_trust_its_key_again() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    sshd.authorize(&core.ensure_key().unwrap().openssh);
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::USER))
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();
    core.connect(host.id.clone())
        .await
        .unwrap()
        .disconnect()
        .await;

    core.remove_host(host.id).unwrap();
    let again = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::USER))
        .unwrap();

    match core.connect(again.id).await {
        Err(CoreError::HostKeyUnknown { fingerprint }) => {
            assert_eq!(fingerprint, sshd.host_key_fingerprint())
        }
        other => panic!("expected HostKeyUnknown, got {:?}", other.err()),
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn editing_address_disconnects_old_host_and_rechecks_key_at_new_host() {
    let old = SshdHost::start();
    let new = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let public = core.ensure_key().unwrap().openssh;
    old.authorize(&public);
    new.authorize(&public);
    let host = core
        .add_host("box".into(), endpoint(&old, SshdHost::USER))
        .unwrap();
    core.trust_host_key(host.id.clone(), old.host_key_fingerprint())
        .unwrap();
    let connection = core.connect(host.id.clone()).await.unwrap();
    core.edit_host(
        host.id.clone(),
        "new box".into(),
        endpoint(&new, SshdHost::USER),
    )
    .await
    .unwrap();
    assert!(matches!(
        connection.terminals().await,
        Err(CoreError::Closed)
    ));
    let fingerprint = match core.connect(host.id.clone()).await {
        Err(CoreError::HostKeyUnknown { fingerprint }) => fingerprint,
        other => panic!("new address needs explicit trust, got {:?}", other.err()),
    };
    assert_eq!(fingerprint, new.host_key_fingerprint());
    core.trust_host_key(host.id.clone(), fingerprint).unwrap();
    let connected = core.connect(host.id).await.unwrap();
    connected.terminals().await.unwrap();
    connected.disconnect().await;
}

/// SSH can authenticate successfully yet forbid opening a command channel.
/// That is a configuration/permission failure, not a network retry.
#[tokio::test]
async fn a_denied_ssh_channel_preserves_its_permission_error() {
    struct Denied;
    impl russh::server::Handler for Denied {
        type Error = russh::Error;

        async fn auth_publickey(
            &mut self,
            _: &str,
            _: russh::server::VerifiedPublicKey<'_>,
        ) -> Result<russh::server::Auth, Self::Error> {
            Ok(russh::server::Auth::Accept)
        }

        async fn channel_open_session(
            &mut self,
            _: russh::Channel<russh::server::Msg>,
            reply: russh::server::ChannelOpenHandle,
            _: &mut russh::server::Session,
        ) -> Result<(), Self::Error> {
            reply
                .reject(russh::ChannelOpenFailure::AdministrativelyProhibited)
                .await;
            Ok(())
        }
    }
    let key =
        russh::keys::PrivateKey::random(&mut rand::rng(), russh::keys::Algorithm::Ed25519).unwrap();
    let fingerprint = key
        .public_key()
        .fingerprint(russh::keys::HashAlg::Sha256)
        .to_string();
    let config = std::sync::Arc::new(russh::server::Config {
        keys: vec![key],
        ..Default::default()
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = tokio::spawn(async move {
        let (socket, _) = listener.accept().await.unwrap();
        if let Ok(session) = russh::server::run_stream(config, socket, Denied).await {
            let _ = session.await;
        }
    });
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap();
    let host = core
        .add_host(
            "denied".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port,
                user: "me".into(),
            },
        )
        .unwrap();
    core.trust_host_key(host.id.clone(), fingerprint).unwrap();
    let result =
        tokio::time::timeout(std::time::Duration::from_secs(10), core.connect(host.id)).await;
    server.abort();
    let _ = server.await;
    let error = result.unwrap().err().expect("channel must be refused");
    assert!(
        matches!(error, CoreError::Rpc { ref kind, .. } if kind == "permission_denied"),
        "{error:?}"
    );
}
