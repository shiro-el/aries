//! The Beacon's push side as a black box: `pair` takes the phone's key and
//! Relay token, `push-test` sends a sealed envelope to the Relay, `unpair`
//! deletes the pairing on the Relay and on the Host. The Relay is a fake
//! local HTTP endpoint that records what arrives.

use std::io::Write;
use std::process::{Command, Output, Stdio};
use std::time::Duration;

use aries_protocol::push::{KeyPair, PairReply, PairRequest, PushMessage, UnpairReply, open};
use aries_test_support::{FakeRelay, http_request};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

struct Host {
    home: tempfile::TempDir,
}

impl Host {
    fn new() -> Self {
        Self {
            home: tempfile::tempdir().unwrap(),
        }
    }

    fn run(&self, arg: &str, stdin: &[u8]) -> Output {
        let mut child = Command::new(BEACON)
            .arg(arg)
            .env_clear()
            .env("HOME", self.home.path())
            .env("PATH", "/usr/bin:/bin")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        child.stdin.take().unwrap().write_all(stdin).unwrap();
        child.wait_with_output().unwrap()
    }

    fn pairing_file(&self) -> std::path::PathBuf {
        self.home.path().join(".local/state/aries-beacon/pairing.json")
    }
}

/// Registers a phone on the Relay, as the app does: returns the token.
fn register(relay: &FakeRelay, device: &str) -> String {
    let body = serde_json::json!({"platform": "fcm", "device_token": device}).to_string();
    let (status, body) = http_request(
        "POST",
        &format!("{}/v1/pairings", relay.url()),
        &[("Content-Type".into(), "application/json".into())],
        body.as_bytes(),
    )
    .unwrap();
    assert_eq!(status, 201);
    let v: serde_json::Value = serde_json::from_slice(&body).unwrap();
    v["token"].as_str().unwrap().to_owned()
}

fn pair(host: &Host, relay: &FakeRelay, token: &str, phone: &KeyPair) -> String {
    let req = PairRequest {
        relay_url: relay.url().into(),
        token: token.into(),
        phone_key: phone.public.to_text(),
    };
    let out = host.run("pair", serde_json::to_string(&req).unwrap().as_bytes());
    assert!(out.status.success(), "pair failed: {out:?}");
    let reply: PairReply = serde_json::from_slice(&out.stdout).unwrap();
    reply.beacon_key
}

#[test]
fn pairs_and_sends_a_test_push_the_phone_can_open() {
    let relay = FakeRelay::start();
    let host = Host::new();
    let phone = KeyPair::generate();
    let token = register(&relay, "device-1");

    let beacon_key = pair(&host, &relay, &token, &phone);
    let out = host.run("push-test", b"");
    assert!(out.status.success(), "push-test failed: {out:?}");

    let pushes = relay.wait_pushes(1, Duration::from_secs(5));
    assert_eq!(pushes[0].0, token);
    let message = open(&pushes[0].1, &phone, &beacon_key.parse().unwrap()).unwrap();
    assert!(matches!(message, PushMessage::Test { sent_at } if sent_at > 1_700_000_000));
}

#[test]
fn keeps_the_pairing_private_to_the_user() {
    use std::os::unix::fs::PermissionsExt;
    let relay = FakeRelay::start();
    let host = Host::new();
    let token = register(&relay, "device-1");
    pair(&host, &relay, &token, &KeyPair::generate());

    let mode = std::fs::metadata(host.pairing_file()).unwrap().permissions().mode();
    assert_eq!(mode & 0o777, 0o600);
}

#[test]
fn unpair_deletes_the_pairing_on_the_relay_and_the_host() {
    let relay = FakeRelay::start();
    let host = Host::new();
    let token = register(&relay, "device-1");
    pair(&host, &relay, &token, &KeyPair::generate());

    let out = host.run("unpair", b"");
    assert!(out.status.success(), "unpair failed: {out:?}");
    let reply: UnpairReply = serde_json::from_slice(&out.stdout).unwrap();

    assert_eq!(
        reply,
        UnpairReply {
            unpaired: true,
            relay_deleted: true
        }
    );
    assert_eq!(relay.log().deleted, vec![token]);
    assert!(relay.log().pairings.is_empty());
    assert!(!host.pairing_file().exists());

    let out = host.run("push-test", b"");
    assert!(!out.status.success());
    assert!(String::from_utf8_lossy(&out.stderr).contains("not paired"));
}

#[test]
fn unpair_without_a_pairing_is_a_no_op() {
    let host = Host::new();
    let out = host.run("unpair", b"");
    assert!(out.status.success(), "{out:?}");
    let reply: UnpairReply = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(
        reply,
        UnpairReply {
            unpaired: false,
            relay_deleted: false
        }
    );
}

#[test]
fn unpair_still_forgets_the_pairing_when_the_relay_is_down() {
    let relay = FakeRelay::start();
    let host = Host::new();
    let token = register(&relay, "device-1");
    let req = PairRequest {
        relay_url: "http://127.0.0.1:9".into(), // discard port: nothing listens
        token,
        phone_key: KeyPair::generate().public.to_text(),
    };
    let out = host.run("pair", serde_json::to_string(&req).unwrap().as_bytes());
    assert!(out.status.success(), "{out:?}");

    let out = host.run("unpair", b"");
    assert!(out.status.success(), "{out:?}");
    let reply: UnpairReply = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(
        reply,
        UnpairReply {
            unpaired: true,
            relay_deleted: false
        }
    );
    assert!(!host.pairing_file().exists());
}

#[test]
fn pairing_again_revokes_the_previous_token() {
    let relay = FakeRelay::start();
    let host = Host::new();
    let old = register(&relay, "device-1");
    pair(&host, &relay, &old, &KeyPair::generate());
    let new = register(&relay, "device-2");
    let phone = KeyPair::generate();

    let beacon_key = pair(&host, &relay, &new, &phone);

    assert_eq!(relay.log().deleted, vec![old]);
    assert!(host.run("push-test", b"").status.success());
    let pushes = relay.wait_pushes(1, Duration::from_secs(5));
    assert_eq!(pushes[0].0, new);
    open(&pushes[0].1, &phone, &beacon_key.parse().unwrap()).unwrap();
}

#[test]
fn reports_a_relay_that_refuses_the_push() {
    let relay = FakeRelay::start();
    let host = Host::new();
    // A token the Relay never issued.
    pair(&host, &relay, "made-up", &KeyPair::generate());

    let out = host.run("push-test", b"");

    assert!(!out.status.success());
    assert!(String::from_utf8_lossy(&out.stderr).contains("401"), "{out:?}");
}

#[test]
fn rejects_a_bad_pair_request() {
    let host = Host::new();
    let out = host.run("pair", br#"{"relay_url":"http://x","token":"t","phone_key":"nope"}"#);
    assert!(!out.status.success());
    assert!(!host.pairing_file().exists());
}
