//! Push reception at the core API seam, with a restored pairing and real HPKE.
mod common;

use std::path::Path;

use aries_core::{CoreError, KeyProtection, KeyVault};
use aries_protocol::push::{self, KeyPair, PushMessage};
use base64::{Engine, engine::general_purpose::STANDARD};
use common::{TestVault, core_in, script_endpoint};

/// Restore a paired phone without needing a Relay or SSH Host for every delivery.
fn paired_phone(data: &Path) -> (KeyPair, KeyPair) {
    let core = core_in(data);
    let host = core
        .add_host("box".into(), script_endpoint("true"))
        .unwrap();
    let phone = KeyPair::generate();
    let beacon = KeyPair::generate();
    let sealed = TestVault
        .seal(
            serde_json::to_vec(&serde_json::json!({
                "phone_secret": phone.secret_text(), "token": "test-pairing"
            }))
            .unwrap(),
            KeyProtection::Plain,
        )
        .unwrap();
    let dir = data.join("hosts").join(host.id);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(
        dir.join("push.json"),
        serde_json::to_vec(&serde_json::json!({
            "relay_url": "https://relay.invalid", "beacon_key": beacon.public.to_text(),
            "sealed": STANDARD.encode(sealed)
        }))
        .unwrap(),
    )
    .unwrap();
    (phone, beacon)
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn assert_rejected(result: Result<aries_core::PushNotice, CoreError>, expected: &str) {
    match result {
        Err(CoreError::PushRejected { reason }) => assert!(reason.contains(expected), "{reason}"),
        other => panic!("expected {expected}, got {other:?}"),
    }
}

#[test]
fn a_delivered_envelope_is_rejected_after_the_phone_restarts() {
    let data = tempfile::tempdir().unwrap();
    let (phone, beacon) = paired_phone(data.path());
    let bytes = push::seal(
        &PushMessage::Test {
            sent_at: now_secs(),
        },
        &phone.public,
        &beacon,
    )
    .unwrap();
    let core = core_in(data.path());
    assert_eq!(core.open_push(bytes.clone()).unwrap().host_name, "box");
    drop(core);
    assert_rejected(core_in(data.path()).open_push(bytes), "already received");
}

fn messages_at(secs: u64) -> Vec<PushMessage> {
    vec![
        PushMessage::Test { sent_at: secs },
        PushMessage::Status {
            terminal: "t7".into(),
            name: None,
            title: None,
            top_level: true,
            status: aries_protocol::agent_status::AgentStatus::Error { message: None },
            turn: None,
            question: None,
            excerpt: None,
            at: secs,
        },
        PushMessage::Progress {
            terminal: "t7".into(),
            name: None,
            elapsed_secs: 60,
            child_agents: 0,
            at: secs,
            cancelled: false,
        },
        PushMessage::Call {
            id: "call-1".into(),
            terminal: "t7".into(),
            top_level: true,
            title: "Help".into(),
            body: "Please review".into(),
            urgency: aries_protocol::calls::Urgency::Check,
            choices: vec![],
            at_ms: secs.saturating_mul(1_000),
        },
    ]
}

#[test]
fn rejects_expired_and_future_pushes_of_every_kind() {
    let data = tempfile::tempdir().unwrap();
    let (phone, beacon) = paired_phone(data.path());
    let core = core_in(data.path());
    let now = now_secs();
    for (at, reason) in [
        (now - 86_410, "expired"),
        (now + 310, "future"),
        (u64::MAX, "future"),
    ] {
        for message in messages_at(at) {
            let bytes = push::seal(&message, &phone.public, &beacon).unwrap();
            assert_rejected(core.open_push(bytes), reason);
        }
    }
    // A small Host clock skew and a nearly day-old delivery are still usable.
    for at in [now - 86_390, now, now + 290] {
        for message in messages_at(at) {
            let bytes = push::seal(&message, &phone.public, &beacon).unwrap();
            core.open_push(bytes).unwrap();
        }
    }
}

#[test]
fn expired_records_are_pruned_on_the_next_delivery() {
    let data = tempfile::tempdir().unwrap();
    let (phone, beacon) = paired_phone(data.path());
    let core = core_in(data.path());
    let now = now_secs();
    // Distinct envelopes with identical content are independent deliveries.
    for _ in 0..24 {
        let bytes = push::seal(
            &PushMessage::Test {
                sent_at: now - 86_390,
            },
            &phone.public,
            &beacon,
        )
        .unwrap();
        core.open_push(bytes).unwrap();
    }
    let ledger = data
        .path()
        .join("hosts")
        .join(&core.hosts()[0].id)
        .join("push-replay.json");
    assert!(std::fs::metadata(&ledger).unwrap().len() > 1_000);
    core.shift_clock(std::time::Duration::from_secs(20));
    let bytes = push::seal(
        &PushMessage::Test { sent_at: now + 20 },
        &phone.public,
        &beacon,
    )
    .unwrap();
    core.open_push(bytes).unwrap();
    // Check the storage budget without depending on the ledger's JSON schema.
    assert!(std::fs::metadata(&ledger).unwrap().len() < 512);
}

#[test]
fn a_full_ledger_keeps_recent_ids_and_evicts_the_oldest() {
    let data = tempfile::tempdir().unwrap();
    let (phone, beacon) = paired_phone(data.path());
    let core = core_in(data.path());
    let message = PushMessage::Test {
        sent_at: now_secs(),
    };
    let oldest = push::seal(&message, &phone.public, &beacon).unwrap();
    core.open_push(oldest.clone()).unwrap();
    let mut newest = Vec::new();
    for _ in 0..4_096 {
        newest = push::seal(&message, &phone.public, &beacon).unwrap();
        core.open_push(newest.clone()).unwrap();
    }
    assert_rejected(core.open_push(newest), "already received");
    // Capacity eviction is FIFO; a still-fresh evicted envelope can be received again.
    core.open_push(oldest).unwrap();
    let ledger = data
        .path()
        .join("hosts")
        .join(&core.hosts()[0].id)
        .join("push-replay.json");
    assert!(std::fs::metadata(ledger).unwrap().len() < 512 * 1_024);
}

#[test]
fn simultaneous_delivery_to_independent_core_handles_notifies_once() {
    let data = tempfile::tempdir().unwrap();
    let (phone, beacon) = paired_phone(data.path());
    let bytes = push::seal(
        &PushMessage::Test {
            sent_at: now_secs(),
        },
        &phone.public,
        &beacon,
    )
    .unwrap();
    let handles: Vec<_> = (0..8).map(|_| core_in(data.path())).collect();
    let start = std::sync::Barrier::new(handles.len());
    let accepted = std::thread::scope(|scope| {
        let threads: Vec<_> = handles
            .into_iter()
            .map(|core| {
                let bytes = &bytes;
                let start = &start;
                scope.spawn(move || {
                    start.wait();
                    match core.open_push(bytes.clone()) {
                        Ok(_) => 1,
                        rejected => {
                            assert_rejected(rejected, "already received");
                            0
                        }
                    }
                })
            })
            .collect();
        threads.into_iter().map(|t| t.join().unwrap()).sum::<u32>()
    });
    assert_eq!(accepted, 1);
}

#[test]
fn rejected_envelopes_do_not_consume_a_replay_id() {
    let data = tempfile::tempdir().unwrap();
    let (phone, beacon) = paired_phone(data.path());
    let core = core_in(data.path());
    let bytes = push::seal(
        &PushMessage::Test {
            sent_at: now_secs() + 310,
        },
        &phone.public,
        &beacon,
    )
    .unwrap();
    let mut tampered = bytes.clone();
    tampered[100] ^= 1;
    assert_rejected(core.open_push(tampered), "paired Host");
    assert_rejected(core.open_push(bytes.clone()), "future");
    core.shift_clock(std::time::Duration::from_secs(20));
    core.open_push(bytes.clone()).unwrap();
    assert_rejected(core.open_push(bytes), "already received");
}

#[test]
fn a_notice_is_not_returned_if_its_replay_id_cannot_be_persisted() {
    let data = tempfile::tempdir().unwrap();
    let (phone, beacon) = paired_phone(data.path());
    let core = core_in(data.path());
    let bytes = push::seal(
        &PushMessage::Test {
            sent_at: now_secs(),
        },
        &phone.public,
        &beacon,
    )
    .unwrap();
    let ledger = data
        .path()
        .join("hosts")
        .join(&core.hosts()[0].id)
        .join("push-replay.json");
    std::fs::create_dir(&ledger).unwrap();
    assert!(matches!(
        core.open_push(bytes.clone()),
        Err(CoreError::Storage { .. })
    ));
    std::fs::remove_dir(&ledger).unwrap();
    core.open_push(bytes.clone()).unwrap();
    assert_rejected(
        core_in(data.path()).open_push(bytes.clone()),
        "already received",
    );
    // Corruption also fails closed, rather than resetting an existing ledger.
    std::fs::write(ledger, "broken ledger").unwrap();
    assert!(matches!(
        core.open_push(bytes),
        Err(CoreError::Storage { .. })
    ));
}
