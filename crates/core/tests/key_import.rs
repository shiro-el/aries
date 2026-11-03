//! Public core seam: import keys produced independently by OpenSSH.
mod common;

use common::core_in;
use std::process::Command;

fn openssh_key(kind: &str, bits: &str, passphrase: &str) -> (String, String) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("key");
    let status = Command::new("ssh-keygen")
        .args([
            "-q",
            "-t",
            kind,
            "-b",
            bits,
            "-N",
            passphrase,
            "-C",
            "import-test",
            "-f",
        ])
        .arg(&path)
        .status()
        .unwrap();
    assert!(status.success());
    (
        std::fs::read_to_string(&path).unwrap(),
        std::fs::read_to_string(path.with_extension("pub")).unwrap(),
    )
}

#[test]
fn imports_openssh_keys_and_preserves_their_public_identity_after_restart() {
    for (kind, bits) in [
        ("ed25519", "256"),
        ("rsa", "2048"),
        ("ecdsa", "256"),
        ("ecdsa", "384"),
        ("ecdsa", "521"),
    ] {
        for passphrase in ["", "test import passphrase"] {
            let (private, public) = openssh_key(kind, bits, passphrase);
            let data = tempfile::tempdir().unwrap();
            let core = core_in(data.path());
            core.ensure_key().unwrap();
            let imported = core
                .import_key("existing".into(), private, passphrase.into())
                .unwrap();
            assert_eq!(imported.openssh, public.trim());
            assert_eq!(imported.label, "existing");
            drop(core);
            let again = core_in(data.path());
            assert_eq!(again.keys().unwrap()[1], imported);
        }
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn imported_keys_connect_after_restart_without_the_original_passphrase() {
    use aries_core::Endpoint;
    use aries_test_support::SshdHost;
    let sshd = SshdHost::start();
    for (kind, bits) in [
        ("ed25519", "256"),
        ("rsa", "2048"),
        ("ecdsa", "256"),
        ("ecdsa", "384"),
        ("ecdsa", "521"),
    ] {
        let (private, public) = openssh_key(kind, bits, "discard after import");
        let data = tempfile::tempdir().unwrap();
        let core = core_in(data.path());
        // Exercise first-run import as well as named additional keys.
        if kind != "rsa" {
            core.ensure_key().unwrap();
        }
        let imported = core
            .import_key(kind.into(), private, "discard after import".into())
            .unwrap();
        sshd.authorize(public.trim());
        let host = core
            .add_host(
                kind.into(),
                Endpoint::Ssh {
                    address: "127.0.0.1".into(),
                    port: sshd.port(),
                    user: SshdHost::USER.into(),
                },
            )
            .unwrap();
        core.set_host_ssh_key(host.id.clone(), Some(imported.id))
            .unwrap();
        core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
            .unwrap();
        drop(core);
        let again = core_in(data.path());
        let connection = again
            .connect(host.id)
            .await
            .unwrap_or_else(|e| panic!("{kind}-{bits}: {e}"));
        assert_eq!(connection.core_version(), "0.1.17");
        assert!(connection.terminals().await.unwrap().is_empty());
        connection.disconnect().await;
    }
}

#[test]
fn bad_inputs_and_wrong_passphrases_do_not_add_keys_or_disclose_input() {
    use aries_core::CoreError;
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.ensure_key().unwrap();
    let before = core.keys().unwrap();
    let (encrypted, public) = openssh_key("ed25519", "256", "correct passphrase");
    for (private, passphrase) in [
        (encrypted.clone(), "wrong passphrase"),
        (encrypted, ""),
        (public, ""),
        ("not a private key".into(), ""),
        ("x".repeat(64 * 1024 + 1), ""),
    ] {
        let err = core
            .import_key("bad".into(), private, passphrase.into())
            .unwrap_err();
        assert!(matches!(err, CoreError::InvalidPrivateKey));
        assert_eq!(core.keys().unwrap(), before);
    }
}

#[tokio::test]
async fn imported_keys_follow_the_same_biometric_policy_as_generated_keys() {
    use aries_core::{CoreError, Endpoint, KeyProtection, KeyVault, VaultError};
    use std::sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    };
    struct Vault(AtomicBool);
    impl KeyVault for Vault {
        fn seal(&self, bytes: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
            if protection == KeyProtection::Biometric && self.0.load(Ordering::SeqCst) {
                return Err(VaultError::Locked);
            }
            // The vault must receive a decrypted key, never the import passphrase.
            let pem = std::str::from_utf8(&bytes).unwrap();
            assert!(
                !russh::keys::PrivateKey::from_openssh(pem)
                    .unwrap()
                    .is_encrypted()
            );
            common::TestVault.seal(bytes, protection)
        }
        fn open(&self, bytes: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
            if protection == KeyProtection::Biometric && self.0.load(Ordering::SeqCst) {
                return Err(VaultError::Locked);
            }
            common::TestVault.open(bytes, protection)
        }
    }
    let data = tempfile::tempdir().unwrap();
    let vault = Arc::new(Vault(AtomicBool::new(false)));
    let core =
        aries_core::AriesCore::new(data.path().to_string_lossy().into(), vault.clone()).unwrap();
    core.ensure_key().unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    let (private, _) = openssh_key("ed25519", "256", "forget me");
    let key = core
        .import_key("protected".into(), private.clone(), "forget me".into())
        .unwrap();
    let host = core
        .add_host(
            "desk".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: 1,
                user: "aries".into(),
            },
        )
        .unwrap();
    core.set_host_ssh_key(host.id.clone(), Some(key.id))
        .unwrap();
    assert!(!core.can_answer_in_background(host.id.clone()).unwrap());
    vault.0.store(true, Ordering::SeqCst);
    let before = core.keys().unwrap();
    assert!(matches!(
        core.import_key("cancelled".into(), private, "forget me".into()),
        Err(CoreError::KeyLocked)
    ));
    assert_eq!(core.keys().unwrap(), before);
    assert!(matches!(
        core.connect(host.id.clone()).await,
        Err(CoreError::KeyLocked)
    ));
    vault.0.store(false, Ordering::SeqCst);
    core.set_key_protection(KeyProtection::Plain).unwrap();
    assert!(core.can_answer_in_background(host.id.clone()).unwrap());
    assert!(matches!(
        core.connect(host.id).await,
        Err(CoreError::Unreachable { .. })
    ));
}

#[test]
fn first_import_becomes_the_default_key_without_generating_another_identity() {
    use aries_core::{DEFAULT_KEY_ID, KeyProtection};
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let (private, public) = openssh_key("ed25519", "256", "first import");
    let imported = core
        .import_key(String::new(), private, "first import".into())
        .unwrap();
    assert_eq!(imported.id, DEFAULT_KEY_ID);
    assert_eq!(core.ensure_key().unwrap().openssh, public.trim());
    assert_eq!(core.keys().unwrap(), [imported]);
    assert_eq!(core.key_protection().unwrap(), Some(KeyProtection::Plain));
}
