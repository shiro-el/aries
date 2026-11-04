//! Key policy: whether the app's SSH key needs a biometric unlock, and what
//! that means for answering from a notification (ticket 23).

mod common;

use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use aries_core::{AriesCore, CoreError, Endpoint, KeyProtection, KeyVault, VaultError};
use common::{TestVault, script_endpoint};

/// Behaves like the Android Keystore vault on a phone: biometric-protected
/// keys work only while the user is there to authenticate (the app is in
/// the foreground) and only if a biometric is enrolled.
struct PhoneVault {
    invalidated: AtomicBool,
    enrolled: AtomicBool,
    user_present: AtomicBool,
    inner: TestVault,
    asked: Mutex<Vec<KeyProtection>>,
    /// When set, `seal` fails once this many more seals have succeeded.
    seals_left: Mutex<Option<usize>>,
}

impl PhoneVault {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            invalidated: AtomicBool::new(false),
            enrolled: AtomicBool::new(true),
            user_present: AtomicBool::new(true),
            inner: TestVault,
            asked: Mutex::new(Vec::new()),
            seals_left: Mutex::new(None),
        })
    }

    fn check(&self, protection: KeyProtection) -> Result<(), VaultError> {
        self.asked.lock().unwrap().push(protection);
        if protection == KeyProtection::Biometric {
            if !self.enrolled.load(Ordering::SeqCst) {
                return Err(VaultError::BiometricUnavailable {
                    detail: "no biometric enrolled".into(),
                });
            }
            if !self.user_present.load(Ordering::SeqCst) {
                return Err(VaultError::Locked);
            }
        }
        Ok(())
    }
}

impl KeyVault for PhoneVault {
    fn seal(&self, plaintext: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
        self.check(protection)?;
        if let Some(left) = self.seals_left.lock().unwrap().as_mut() {
            if *left == 0 {
                return Err(VaultError::BiometricUnavailable {
                    detail: "the Keystore gave up".into(),
                });
            }
            *left -= 1;
        }
        self.inner.seal(plaintext, protection)
    }

    fn open(&self, sealed: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
        self.check(protection)?;
        if protection == KeyProtection::Biometric && self.invalidated.load(Ordering::SeqCst) {
            return Err(VaultError::Invalidated);
        }
        self.inner.open(sealed, protection)
    }
}

fn core_with(dir: &std::path::Path, vault: &Arc<PhoneVault>) -> Arc<AriesCore> {
    AriesCore::new(dir.to_string_lossy().into_owned(), vault.clone()).expect("core")
}

/// An SSH Host nobody listens on: a connect that gets past the key fails as
/// Unreachable, so a KeyLocked error proves the key was refused first.
fn closed_ssh() -> Endpoint {
    Endpoint::Ssh {
        address: "127.0.0.1".into(),
        port: 1,
        user: "aries".into(),
    }
}

#[test]
fn a_new_key_needs_no_biometric_and_can_answer_in_the_background() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    assert_eq!(core.key_protection().unwrap(), None);

    core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();

    assert_eq!(core.key_protection().unwrap(), Some(KeyProtection::Plain));
    assert!(core.can_answer_in_background(host.id).unwrap());
}

#[test]
fn requiring_biometric_keeps_the_same_key_and_survives_a_relaunch() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    let before = core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();

    core.set_key_protection(KeyProtection::Biometric).unwrap();

    let again = core_with(dir.path(), &vault);
    assert_eq!(again.public_key().unwrap(), Some(before));
    assert_eq!(
        again.key_protection().unwrap(),
        Some(KeyProtection::Biometric)
    );
    assert!(!again.can_answer_in_background(host.id).unwrap());
}

#[tokio::test]
async fn a_biometric_key_fails_clearly_in_the_background() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();

    vault.user_present.store(false, Ordering::SeqCst);
    vault.asked.lock().unwrap().clear();
    let err = core
        .connect(host.id.clone())
        .await
        .err()
        .expect("must fail");

    assert!(matches!(err, CoreError::KeyLocked), "{err:?}");
    // Only the biometric key was asked for: no quiet fallback to another key.
    assert_eq!(*vault.asked.lock().unwrap(), vec![KeyProtection::Biometric]);
}

#[tokio::test]
async fn a_biometric_key_still_signs_in_while_the_user_is_present() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();

    let err = core.connect(host.id).await.err().expect("nobody listens");

    assert!(matches!(err, CoreError::Unreachable { .. }), "{err:?}");
}

#[tokio::test]
async fn chat_reconnect_requires_a_fresh_key_authorization() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    let watch = core
        .watch_chat_retrying(
            host.id,
            "t1".into(),
            None,
            std::time::Duration::from_secs(60),
        )
        .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        while watch.next().await.unwrap().status != aries_core::ChatStatus::Reconnecting {}
    })
    .await
    .unwrap();
    vault.user_present.store(false, Ordering::SeqCst);
    vault.asked.lock().unwrap().clear();
    watch.retry_now();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        while vault.asked.lock().unwrap().is_empty() {
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("reconnect must ask the vault again");
    watch.stop();
    assert_eq!(*vault.asked.lock().unwrap(), [KeyProtection::Biometric]);
}

#[tokio::test]
async fn call_reconnect_requires_a_fresh_key_authorization() {
    struct Listener(AtomicBool);
    impl aries_core::CallListener for Listener {
        fn on_calls(&self, _: Vec<aries_core::CallInfo>) {}
        fn on_new_call(&self, _: aries_core::CallInfo) {}
        fn on_state(&self, _: aries_core::CallFeedState) {
            self.0.store(true, Ordering::SeqCst);
        }
    }
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    let listener = Arc::new(Listener(AtomicBool::new(false)));
    let watch = core.watch_calls(host.id, listener.clone()).await.unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        while !listener.0.load(Ordering::SeqCst) {
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    vault.user_present.store(false, Ordering::SeqCst);
    vault.asked.lock().unwrap().clear();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        while vault.asked.lock().unwrap().is_empty() {
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("Call reconnect must ask the vault again");
    watch.stop();
    assert_eq!(*vault.asked.lock().unwrap(), [KeyProtection::Biometric]);
}

#[test]
fn turning_biometric_off_allows_background_answers_again() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    let before = core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();

    core.set_key_protection(KeyProtection::Plain).unwrap();

    assert_eq!(core.key_protection().unwrap(), Some(KeyProtection::Plain));
    assert_eq!(core.public_key().unwrap(), Some(before));
    assert!(core.can_answer_in_background(host.id).unwrap());
}

#[test]
fn without_an_enrolled_biometric_the_policy_stays_unchanged() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    vault.enrolled.store(false, Ordering::SeqCst);
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();

    let err = core
        .set_key_protection(KeyProtection::Biometric)
        .expect_err("must fail");

    assert!(
        matches!(err, CoreError::BiometricUnavailable { .. }),
        "{err:?}"
    );
    assert_eq!(core.key_protection().unwrap(), Some(KeyProtection::Plain));
}

#[test]
fn changing_the_policy_needs_a_key() {
    let dir = tempfile::tempdir().unwrap();
    let core = core_with(dir.path(), &PhoneVault::new());

    let err = core.set_key_protection(KeyProtection::Biometric).err();

    assert!(matches!(err, Some(CoreError::NoKey)), "{err:?}");
}

#[test]
fn background_answers_need_a_key_for_ssh_hosts_but_not_for_local_ones() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    let ssh = core.add_host("desk".into(), closed_ssh()).unwrap();
    let local = core
        .add_host("dev".into(), script_endpoint("true"))
        .unwrap();

    assert!(!core.can_answer_in_background(ssh.id).unwrap());
    core.ensure_key().unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    assert!(core.can_answer_in_background(local.id).unwrap());
}

#[test]
fn background_answers_for_an_unknown_host_are_an_error() {
    let dir = tempfile::tempdir().unwrap();
    let core = core_with(dir.path(), &PhoneVault::new());

    let err = core.can_answer_in_background("nope".into()).err();

    assert!(
        matches!(err, Some(CoreError::UnknownHost { .. })),
        "{err:?}"
    );
}

#[test]
fn a_key_from_before_key_policies_counts_as_plain() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    let info = core.ensure_key().unwrap();
    // Before this ticket `identity.sealed` held only the vault's bytes.
    let pem = TestVault
        .open(
            std::fs::read(dir.path().join("identity.sealed"))
                .map(|b| strip_header(&b))
                .unwrap(),
            KeyProtection::Plain,
        )
        .unwrap();
    let legacy = TestVault.seal(pem, KeyProtection::Plain).unwrap();
    std::fs::write(dir.path().join("identity.sealed"), legacy).unwrap();

    let again = core_with(dir.path(), &vault);
    assert_eq!(again.key_protection().unwrap(), Some(KeyProtection::Plain));
    assert_eq!(again.public_key().unwrap(), Some(info));
    again.set_key_protection(KeyProtection::Biometric).unwrap();
}

/// `identity.sealed` is `ARIESKEY1` + one protection byte + the vault's bytes.
fn strip_header(bytes: &[u8]) -> Vec<u8> {
    bytes
        .strip_prefix(b"ARIESKEY1")
        .map(|rest| rest[1..].to_vec())
        .unwrap_or_else(|| bytes.to_vec())
}

/// A Host signed in with key `host_key` through one jump host signed in with
/// `jump_key` (`None`: the default key).
fn routed_host(core: &AriesCore, host_key: Option<String>, jump_key: Option<String>) -> String {
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_host_ssh_key(host.id.clone(), host_key).unwrap();
    core.set_jump_hosts(
        host.id.clone(),
        vec![aries_core::JumpHost {
            address: "127.0.0.1".into(),
            port: 1,
            user: "jump".into(),
            key_id: jump_key,
            host_key: None,
        }],
    )
    .unwrap();
    host.id
}

/// Connects with nobody there to authenticate: `KeyLocked` if some key on
/// the route needs a biometric unlock, `Unreachable` once all keys opened.
async fn background_connect(core: &AriesCore, vault: &PhoneVault, host_id: &str) -> CoreError {
    vault.user_present.store(false, Ordering::SeqCst);
    let err = core
        .connect(host_id.to_owned())
        .await
        .err()
        .expect("nobody listens");
    vault.user_present.store(true, Ordering::SeqCst);
    err
}

#[tokio::test]
async fn the_policy_covers_every_key_on_the_route_to_a_host() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let extra = core.create_key("bastion".into()).unwrap().id;
    // The Host uses the default key; only its jump host uses the extra one.
    let via_extra = routed_host(&core, None, Some(extra.clone()));
    let direct_extra = core.add_host("box".into(), closed_ssh()).unwrap().id;
    core.set_host_ssh_key(direct_extra.clone(), Some(extra.clone()))
        .unwrap();
    assert!(core.can_answer_in_background(via_extra.clone()).unwrap());

    core.set_key_protection(KeyProtection::Biometric).unwrap();

    assert!(!core.can_answer_in_background(via_extra.clone()).unwrap());
    assert!(!core.can_answer_in_background(direct_extra.clone()).unwrap());
    let err = background_connect(&core, &vault, &direct_extra).await;
    assert!(matches!(err, CoreError::KeyLocked), "{err:?}");
    // The extra key's public half is unchanged, so authorized_keys stays valid.
    assert_eq!(core.keys().unwrap()[1].id, extra);

    core.set_key_protection(KeyProtection::Plain).unwrap();

    assert!(core.can_answer_in_background(via_extra.clone()).unwrap());
    let err = background_connect(&core, &vault, &via_extra).await;
    assert!(matches!(err, CoreError::Unreachable { .. }), "{err:?}");
}

#[tokio::test]
async fn a_key_created_under_biometric_needs_biometric_too() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();

    let extra = core.create_key("later".into()).unwrap().id;
    let host = core.add_host("box".into(), closed_ssh()).unwrap().id;
    core.set_host_ssh_key(host.clone(), Some(extra)).unwrap();

    assert!(!core.can_answer_in_background(host.clone()).unwrap());
    let err = background_connect(&core, &vault, &host).await;
    assert!(matches!(err, CoreError::KeyLocked), "{err:?}");
}

#[tokio::test]
async fn a_failed_policy_change_keeps_the_old_protection_on_every_key() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let a = core.create_key("a".into()).unwrap().id;
    let b = core.create_key("b".into()).unwrap().id;
    let host = routed_host(&core, Some(a), Some(b));
    // Two of the three re-seals succeed, then the Keystore fails.
    *vault.seals_left.lock().unwrap() = Some(2);

    let err = core
        .set_key_protection(KeyProtection::Biometric)
        .expect_err("must fail");
    *vault.seals_left.lock().unwrap() = None;

    assert!(
        matches!(err, CoreError::BiometricUnavailable { .. }),
        "{err:?}"
    );
    assert_eq!(core.key_protection().unwrap(), Some(KeyProtection::Plain));
    assert!(core.can_answer_in_background(host.clone()).unwrap());
    let err = background_connect(&core, &vault, &host).await;
    assert!(matches!(err, CoreError::Unreachable { .. }), "{err:?}");
}

#[tokio::test]
async fn a_background_question_answer_never_asks_to_unlock_a_biometric_key() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    vault.asked.lock().unwrap().clear();
    // Even with authorization available, background work must not open it.
    let err = core
        .answer_question_background(host.id, "t1".into(), "q1".into(), vec![vec![0]])
        .await
        .unwrap_err();
    assert!(matches!(err, CoreError::KeyLocked), "{err:?}");
    assert!(vault.asked.lock().unwrap().is_empty());
}

#[tokio::test]
async fn permanent_invalidation_is_distinct_from_a_cancelled_unlock() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    core.ensure_key().unwrap();
    let host = core.add_host("desk".into(), closed_ssh()).unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    vault.invalidated.store(true, Ordering::SeqCst);
    let err = core.connect(host.id).await.err().unwrap();
    assert!(matches!(err, CoreError::KeyInvalidated), "{err:?}");
}

#[test]
fn recovery_replaces_only_the_chosen_key_and_preserves_routes_after_restart() {
    let dir = tempfile::tempdir().unwrap();
    let vault = PhoneVault::new();
    let core = core_with(dir.path(), &vault);
    let before = core.ensure_key().unwrap();
    let extra = core.create_key("bastion".into()).unwrap();
    let host = routed_host(&core, None, Some(extra.id.clone()));
    core.trust_host_key(host.clone(), "SHA256:trusted".into())
        .unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    vault.invalidated.store(true, Ordering::SeqCst);
    let replacement = core
        .recover_key("default".into(), None, String::new())
        .unwrap();
    assert_ne!(replacement.openssh, before.openssh);
    assert_eq!(core.keys().unwrap()[1], extra);
    // A broken import must leave the replacement intact.
    assert!(matches!(
        core.recover_key(
            "default".into(),
            Some("bad private key".into()),
            "bad".into()
        ),
        Err(CoreError::InvalidPrivateKey)
    ));
    assert_eq!(
        core.public_key().unwrap().unwrap().openssh,
        replacement.openssh
    );
    let again = core_with(dir.path(), &vault);
    assert_eq!(
        again.public_key().unwrap().unwrap().openssh,
        replacement.openssh
    );
    assert_eq!(again.host_ssh_key(host.clone()).unwrap(), None);
    assert_eq!(
        again.jump_hosts(host.clone()).unwrap()[0].key_id,
        Some(extra.id)
    );
    assert_eq!(again.hosts()[0].host_key.as_deref(), Some("SHA256:trusted"));
    assert_eq!(
        again.key_protection().unwrap(),
        Some(KeyProtection::Biometric)
    );
}
