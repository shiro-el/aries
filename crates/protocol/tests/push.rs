//! The push envelope: what the Beacon seals for one phone and the phone
//! opens. Only the paired phone can open it, only the paired Beacon can
//! have sealed it, and every envelope has the same size.

use aries_protocol::push::{
    ENVELOPE_LEN, ENVELOPE_VERSION, EnvelopeError, KeyPair, PushMessage, open, seal,
};

struct Pair {
    phone: KeyPair,
    beacon: KeyPair,
}

fn pair() -> Pair {
    Pair {
        phone: KeyPair::generate(),
        beacon: KeyPair::generate(),
    }
}

fn test_message() -> PushMessage {
    PushMessage::Test {
        sent_at: 1_700_000_000,
    }
}

#[test]
fn the_paired_phone_opens_what_the_beacon_sealed() {
    let p = pair();
    let envelope = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();

    let opened = open(&envelope, &p.phone, &p.beacon.public).unwrap();

    assert_eq!(opened, test_message());
}

#[test]
fn starts_with_the_version_header() {
    let p = pair();
    let envelope = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();
    assert_eq!(envelope[0], ENVELOPE_VERSION);
    assert_eq!(ENVELOPE_VERSION, 1);
}

#[test]
fn every_envelope_has_the_same_size() {
    let p = pair();
    let short = seal(
        &PushMessage::Test { sent_at: 1 },
        &p.phone.public,
        &p.beacon,
    )
    .unwrap();
    let long = seal(
        &PushMessage::Test { sent_at: u64::MAX },
        &p.phone.public,
        &p.beacon,
    )
    .unwrap();
    assert_eq!(short.len(), ENVELOPE_LEN);
    assert_eq!(long.len(), ENVELOPE_LEN);
    // Fits an FCM data message (4096 bytes) after base64.
    assert!(ENVELOPE_LEN.div_ceil(3) * 4 < 4000);
}

#[test]
fn two_seals_of_the_same_message_differ() {
    let p = pair();
    let a = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();
    let b = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();
    assert_ne!(a, b);
}

#[test]
fn rejects_a_tampered_envelope() {
    let p = pair();
    let envelope = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();
    for at in [1, 40, ENVELOPE_LEN / 2, ENVELOPE_LEN - 1] {
        let mut bad = envelope.clone();
        bad[at] ^= 0x01;
        assert_eq!(
            open(&bad, &p.phone, &p.beacon.public),
            Err(EnvelopeError::Rejected),
            "flipped byte {at}"
        );
    }
}

#[test]
fn rejects_an_unknown_version() {
    let p = pair();
    let mut envelope = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();
    envelope[0] = 2;
    assert_eq!(
        open(&envelope, &p.phone, &p.beacon.public),
        Err(EnvelopeError::UnsupportedVersion(2))
    );
}

#[test]
fn rejects_a_truncated_envelope() {
    let p = pair();
    let envelope = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();
    assert_eq!(
        open(&envelope[..ENVELOPE_LEN - 1], &p.phone, &p.beacon.public),
        Err(EnvelopeError::Malformed)
    );
    assert_eq!(
        open(&[], &p.phone, &p.beacon.public),
        Err(EnvelopeError::Malformed)
    );
}

#[test]
fn another_phone_cannot_open_it() {
    let p = pair();
    let envelope = seal(&test_message(), &p.phone.public, &p.beacon).unwrap();
    let other = KeyPair::generate();
    assert_eq!(
        open(&envelope, &other, &p.beacon.public),
        Err(EnvelopeError::Rejected)
    );
}

#[test]
fn rejects_an_envelope_sealed_by_another_beacon() {
    let p = pair();
    let impostor = KeyPair::generate();
    let envelope = seal(&test_message(), &p.phone.public, &impostor).unwrap();
    assert_eq!(
        open(&envelope, &p.phone, &p.beacon.public),
        Err(EnvelopeError::Rejected)
    );
}

#[test]
fn keys_round_trip_through_their_text_form() {
    let k = KeyPair::generate();
    let text = k.public.to_text();
    assert_eq!(text.len(), 43, "unpadded base64url of 32 bytes");
    assert_eq!(
        text.parse::<aries_protocol::push::PublicKey>().unwrap(),
        k.public
    );
    let secret = k.secret_text();
    let back = KeyPair::from_secret_text(&secret).unwrap();
    assert_eq!(back.public, k.public);
    assert!(
        "not a key"
            .parse::<aries_protocol::push::PublicKey>()
            .is_err()
    );
}

#[test]
fn message_json_is_tagged_by_kind() {
    let json = serde_json::to_value(test_message()).unwrap();
    assert_eq!(
        json,
        serde_json::json!({"kind": "test", "sent_at": 1_700_000_000u64})
    );
}

#[test]
fn status_details_round_trip_and_old_readers_ignore_them() {
    use aries_protocol::agent_status::AgentStatus;
    use aries_protocol::push::PushQuestion;
    let message = PushMessage::Status {
        terminal: "t7".into(),
        name: None,
        title: None,
        top_level: true,
        status: AgentStatus::Done,
        at: 42,
        turn: Some(5),
        question: Some(PushQuestion {
            id: "ask-1".into(),
            text: "Ship?".into(),
            options: vec!["Yes".into(), "No".into()],
        }),
        excerpt: Some("Tests passed.".into()),
    };
    let p = pair();
    let envelope = seal(&message, &p.phone.public, &p.beacon).unwrap();
    assert_eq!(
        open(&envelope, &p.phone, &p.beacon.public).unwrap(),
        message
    );
    let json = serde_json::to_value(&message).unwrap();
    assert_eq!(
        json["question"],
        serde_json::json!({"id":"ask-1","text":"Ship?","options":["Yes","No"]})
    );
    // The old enum had these fields and serde's default unknown-field behavior.
    #[derive(serde::Deserialize)]
    #[serde(tag = "kind", rename_all = "snake_case")]
    enum OldPush {
        Status {
            terminal: String,
            status: AgentStatus,
            at: u64,
        },
    }
    let OldPush::Status {
        terminal,
        status,
        at,
    } = serde_json::from_value(json).unwrap();
    assert_eq!(
        (terminal.as_str(), status, at),
        ("t7", AgentStatus::Done, 42)
    );
}

#[test]
fn old_status_pushes_have_no_optional_details() {
    let json = serde_json::json!({"kind":"status","terminal":"t7","top_level":true,"status":{"status":"done"},"at":42});
    let message: PushMessage = serde_json::from_value(json.clone()).unwrap();
    assert!(matches!(
        &message,
        PushMessage::Status {
            turn: None,
            question: None,
            excerpt: None,
            ..
        }
    ));
    assert_eq!(serde_json::to_value(message).unwrap(), json);
}

#[test]
fn progress_round_trips_without_changing_the_v1_envelope_or_old_status_json() {
    let p = pair();
    let json = r#"{"kind":"progress","terminal":"t7","name":"Builder","elapsed_secs":125,"child_agents":3,"at":1700000000}"#;
    let message: PushMessage = serde_json::from_str(json).unwrap();
    let envelope = seal(&message, &p.phone.public, &p.beacon).unwrap();
    assert_eq!(envelope.len(), ENVELOPE_LEN);
    assert_eq!(
        open(&envelope, &p.phone, &p.beacon.public).unwrap(),
        message
    );
    assert!(matches!(
        message,
        PushMessage::Progress {
            elapsed_secs: 125,
            child_agents: 3,
            cancelled: false,
            ..
        }
    ));
    let old: PushMessage = serde_json::from_str(
        r#"{"kind":"status","terminal":"t7","top_level":true,"status":{"status":"done"},"at":1}"#,
    )
    .unwrap();
    assert!(matches!(old, PushMessage::Status { .. }));
}
