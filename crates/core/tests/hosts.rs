//! Seam 1: the app's SSH key and Hosts survive a relaunch.

mod common;

use aries_core::{CoreError, Endpoint};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

#[test]
fn creates_one_ed25519_key_and_keeps_it() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    assert_eq!(core.public_key().unwrap(), None);

    let key = core.ensure_key().unwrap();
    assert!(key.openssh.starts_with("ssh-ed25519 AAAA"), "{}", key.openssh);
    assert!(key.fingerprint.starts_with("SHA256:"), "{}", key.fingerprint);
    assert_eq!(core.ensure_key().unwrap(), key);

    drop(core);
    let relaunched = core_in(data.path());
    assert_eq!(relaunched.public_key().unwrap(), Some(key));
}

#[test]
fn never_writes_the_private_key_in_the_clear() {
    let data = tempfile::tempdir().unwrap();
    core_in(data.path()).ensure_key().unwrap();
    for entry in std::fs::read_dir(data.path()).unwrap() {
        let bytes = std::fs::read(entry.unwrap().path()).unwrap();
        let text = String::from_utf8_lossy(&bytes);
        assert!(!text.contains("PRIVATE KEY"), "plaintext private key at rest");
    }
}

#[tokio::test]
async fn reconnects_to_the_same_host_after_a_relaunch() {
    let hc = HcSession::start();
    hc.run_bg("kept", &["sleep", "300"]);
    let data = tempfile::tempdir().unwrap();

    let id = {
        let core = core_in(data.path());
        let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
        core.connect(host.id.clone()).await.unwrap().close().await;
        host.id
    };

    let core = core_in(data.path());
    let hosts = core.hosts();
    assert_eq!(hosts.len(), 1);
    assert_eq!(hosts[0].id, id);
    assert_eq!(hosts[0].name, "dev");
    let conn = core.connect(id).await.unwrap();
    let names: Vec<_> = conn.terminals().await.unwrap().into_iter().map(|t| t.name).collect();
    assert_eq!(names, vec![Some("kept".to_owned())]);
    conn.close().await;
}

#[tokio::test]
async fn ssh_hosts_need_a_key_first() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "nokey".into(),
            Endpoint::Ssh { address: "127.0.0.1".into(), port: 1, user: "x".into() },
        )
        .unwrap();
    assert!(matches!(core.connect(host.id).await, Err(CoreError::NoKey)));
}
