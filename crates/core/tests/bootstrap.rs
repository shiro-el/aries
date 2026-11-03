//! Password bootstrap against a real sshd in docker: one password login
//! installs the app's public key, and every later connection uses the key.
//!
//! Needs docker. Run with `cargo test -p aries-core --test bootstrap -- --ignored`.

mod common;

use aries_core::{CoreError, Endpoint, KeyInstall};
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
async fn a_password_login_installs_the_key_then_the_key_alone_connects() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let key = core.ensure_key().unwrap();
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::PASSWORD_USER))
        .unwrap();

    // Before the key is installed, key auth alone is refused.
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();
    assert!(matches!(
        core.connect(host.id.clone()).await,
        Err(CoreError::AuthRejected)
    ));

    let outcome = core
        .install_key_with_password(host.id.clone(), SshdHost::PASSWORD.into())
        .await
        .unwrap();
    assert_eq!(outcome, KeyInstall::Added);
    assert_eq!(
        sshd.authorized_keys(SshdHost::PASSWORD_USER).trim(),
        key.openssh
    );

    let conn = core.connect(host.id.clone()).await.unwrap();
    assert_eq!(conn.terminals().await.unwrap(), vec![]);
    conn.disconnect().await;

    // The password was used once and never written to app storage.
    for entry in std::fs::read_dir(data.path()).unwrap() {
        let bytes = std::fs::read(entry.unwrap().path()).unwrap();
        let text = String::from_utf8_lossy(&bytes);
        assert!(!text.contains(SshdHost::PASSWORD), "password stored");
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn does_not_add_a_key_that_is_already_authorized() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let key = core.ensure_key().unwrap();
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::PASSWORD_USER))
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();

    let install = || core.install_key_with_password(host.id.clone(), SshdHost::PASSWORD.into());
    assert_eq!(install().await.unwrap(), KeyInstall::Added);
    assert_eq!(install().await.unwrap(), KeyInstall::AlreadyPresent);

    let lines: Vec<_> = sshd
        .authorized_keys(SshdHost::PASSWORD_USER)
        .lines()
        .map(str::to_owned)
        .collect();
    assert_eq!(lines, vec![key.openssh]);
}

#[tokio::test]
#[ignore = "needs docker"]
async fn reports_a_wrong_password() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap();
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::PASSWORD_USER))
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();

    assert!(matches!(
        core.install_key_with_password(host.id, "not-the-password".into())
            .await,
        Err(CoreError::PasswordRejected)
    ));
    assert_eq!(sshd.authorized_keys(SshdHost::PASSWORD_USER), "");
}

#[tokio::test]
#[ignore = "needs docker"]
async fn reports_a_permission_problem_on_the_host() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap();
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::LOCKED_USER))
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();

    match core
        .install_key_with_password(host.id, SshdHost::PASSWORD.into())
        .await
    {
        Err(CoreError::KeyInstallFailed { detail }) => {
            assert!(detail.contains("/home/locked/.ssh"), "detail: {detail}")
        }
        other => panic!("expected KeyInstallFailed, got {other:?}"),
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn checks_the_host_key_before_sending_the_password() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host("box".into(), endpoint(&sshd, SshdHost::PASSWORD_USER))
        .unwrap();

    match core
        .install_key_with_password(host.id, SshdHost::PASSWORD.into())
        .await
    {
        Err(CoreError::HostKeyUnknown { fingerprint }) => {
            assert_eq!(fingerprint, sshd.host_key_fingerprint())
        }
        other => panic!("expected HostKeyUnknown, got {other:?}"),
    }
    assert_eq!(sshd.authorized_keys(SshdHost::PASSWORD_USER), "");
}
