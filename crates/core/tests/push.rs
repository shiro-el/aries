//! Turning push on for a Host (seam 1): the core registers the phone on the
//! Relay, exchanges keys with the real Beacon over SSH, the Beacon sends a
//! test envelope to a fake Relay, and the core opens what the Relay got.
//! Unpairing (from the phone, by Host removal, by Beacon removal) deletes
//! the pairing on the Relay.
//!
//! The docker tests run with `cargo test -p aries-core --test push -- --ignored`.

mod common;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use aries_core::{
    AgentStatus, AriesCore, BeaconArtifact, BeaconFetcher, BeaconRelease, BlockedReason, CoreError,
    Endpoint, FetchError, HttpClient, HttpRequest, HttpResponse, PushContent, PushDevice,
    PushProgress, PushStep,
};
use aries_test_support::{FakeRelay, ReverseTunnel, SystemdHost, beacon_bin, http_request};
use common::core_in;

/// Plays the app's HTTP client (plain HTTP is enough for the fake Relay).
struct Http;

impl HttpClient for Http {
    fn send(&self, request: HttpRequest) -> Result<HttpResponse, FetchError> {
        let headers: Vec<(String, String)> = request.headers.into_iter().collect();
        let (status, body) = http_request(&request.method, &request.url, &headers, &request.body)
            .map_err(|e| FetchError::Failed {
            detail: e.to_string(),
        })?;
        Ok(HttpResponse { status, body })
    }
}

#[derive(Default)]
struct Steps(Mutex<Vec<PushStep>>);

impl PushProgress for Steps {
    fn on_step(&self, step: PushStep) {
        self.0.lock().unwrap().push(step);
    }
}

fn device(token: &str) -> PushDevice {
    PushDevice {
        platform: "fcm".into(),
        token: token.into(),
    }
}

/// An SSH Host nobody listens on.
fn dead_ssh_host(core: &AriesCore) -> String {
    let port = std::net::TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let h = core
        .add_host(
            "gone".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port,
                user: "nobody".into(),
            },
        )
        .unwrap();
    core.ensure_key().unwrap();
    h.id
}

#[tokio::test]
async fn needs_an_ssh_host() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let relay = FakeRelay::start();
    let h = core
        .add_host("local".into(), common::script_endpoint("true"))
        .unwrap();
    let r = core
        .enable_push(
            h.id,
            relay.url().into(),
            device("d"),
            Arc::new(Http),
            Arc::new(Steps::default()),
        )
        .await;
    assert!(matches!(r, Err(CoreError::BeaconNeedsSsh)), "{r:?}");
    assert!(relay.log().pairings.is_empty());
}

#[tokio::test]
async fn reports_an_unreachable_relay_at_the_register_step() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let id = dead_ssh_host(&core);
    let steps = Arc::new(Steps::default());

    let r = core
        .enable_push(
            id.clone(),
            "http://127.0.0.1:9".into(),
            device("d"),
            Arc::new(Http),
            steps.clone(),
        )
        .await;

    match r {
        Err(CoreError::PushFailed {
            step: PushStep::Register,
            detail,
        }) => assert!(!detail.is_empty()),
        other => panic!("expected a Register failure, got {other:?}"),
    }
    assert_eq!(*steps.0.lock().unwrap(), [PushStep::Register]);
    assert_eq!(core.push_pairing(id).unwrap(), None);
}

#[tokio::test]
async fn a_pairing_that_fails_on_the_host_leaves_nothing_on_the_relay() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let id = dead_ssh_host(&core);
    let relay = FakeRelay::start();
    let steps = Arc::new(Steps::default());

    let r = core
        .enable_push(
            id.clone(),
            relay.url().into(),
            device("dev-1"),
            Arc::new(Http),
            steps.clone(),
        )
        .await;

    assert!(
        matches!(
            r,
            Err(CoreError::PushFailed {
                step: PushStep::Pair,
                ..
            })
        ),
        "{r:?}"
    );
    assert_eq!(
        *steps.0.lock().unwrap(),
        [PushStep::Register, PushStep::Pair]
    );
    let log = relay.log();
    assert!(log.pairings.is_empty(), "rolled back: {log:?}");
    assert_eq!(log.deleted.len(), 1);
    assert_eq!(core.push_pairing(id).unwrap(), None);
}

#[test]
fn rejects_an_envelope_when_nothing_is_paired() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    assert!(matches!(
        core.open_push(vec![1; 2048]),
        Err(CoreError::PushRejected { .. })
    ));
}

// ---- docker: the real Beacon on a systemd Host -----------------------------

const URL: &str = "https://example.invalid/aries-beacon-linux-x86_64";
const PAIRING: &str = "/home/aries/.local/state/aries-beacon/pairing.json";

struct Fetcher(Vec<u8>);

impl BeaconFetcher for Fetcher {
    fn fetch(&self, _url: String) -> Result<Vec<u8>, FetchError> {
        Ok(self.0.clone())
    }
}

struct Setup {
    host: SystemdHost,
    core: Arc<AriesCore>,
    id: String,
    relay: FakeRelay,
    _tunnel: ReverseTunnel,
    _data: tempfile::TempDir,
}

/// A Host with the Beacon installed and a fake Relay the container reaches.
async fn setup() -> Setup {
    setup_with(false).await
}

/// `lingering`: the user's services keep running after SSH logins end, so
/// the Beacon's `serve` stays up.
async fn setup_with(lingering: bool) -> Setup {
    let host = SystemdHost::start();
    if lingering {
        host.enable_linger();
    }
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    host.authorize(&core.ensure_key().unwrap().openssh);
    let h = core
        .add_host(
            "box".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: host.port(),
                user: SystemdHost::USER.into(),
            },
        )
        .unwrap();
    core.trust_host_key(h.id.clone(), host.host_key_fingerprint())
        .unwrap();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    core.set_beacon_release(BeaconRelease {
        version: env!("CARGO_PKG_VERSION").into(),
        artifacts: vec![BeaconArtifact {
            os: "linux".into(),
            arch: "x86_64".into(),
            url: URL.into(),
            sha256: sha256_hex(&bytes),
        }],
    });
    core.install_beacon(h.id.clone(), Arc::new(Fetcher(bytes)))
        .await
        .unwrap();
    // The phone and the Beacon use the same URL: here it is the fake
    // Relay, in the container a tunnel to it on the same port.
    let relay = FakeRelay::start();
    let local = relay.url().trim_start_matches("http://").to_owned();
    let port = local.rsplit(':').next().unwrap().parse().unwrap();
    let tunnel = host.reverse_tunnel(port, &local);
    Setup {
        host,
        core,
        id: h.id,
        relay,
        _tunnel: tunnel,
        _data: data,
    }
}

fn sha256_hex(bytes: &[u8]) -> String {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("blob");
    std::fs::write(&path, bytes).unwrap();
    let out = std::process::Command::new("sha256sum")
        .arg(&path)
        .output()
        .unwrap();
    String::from_utf8(out.stdout).unwrap()[..64].to_owned()
}

impl Setup {
    async fn enable(&self, device_token: &str) -> Arc<Steps> {
        let steps = Arc::new(Steps::default());
        self.core
            .enable_push(
                self.id.clone(),
                self.relay.url().into(),
                device(device_token),
                Arc::new(Http),
                steps.clone(),
            )
            .await
            .unwrap();
        steps
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn turning_push_on_pairs_and_the_test_push_opens_on_the_phone() {
    let s = setup().await;

    let steps = s.enable("dev-1").await;

    assert_eq!(
        *steps.0.lock().unwrap(),
        [PushStep::Register, PushStep::Pair, PushStep::Test]
    );
    let log = s.relay.log();
    assert_eq!(log.pairings.values().collect::<Vec<_>>(), ["dev-1"]);
    assert!(s.host.check(&format!(
        "test \"$(stat -c %a {PAIRING})\" = 600 && test \"$(stat -c %U {PAIRING})\" = aries"
    )));
    let pushes = s.relay.wait_pushes(1, Duration::from_secs(10));
    let notice = s.core.open_push(pushes[0].1.clone()).unwrap();
    assert_eq!(notice.host_id, s.id);
    assert_eq!(notice.host_name, "box");
    assert!(matches!(notice.content, PushContent::Test { .. }));
    assert_eq!(
        s.core
            .push_pairing(s.id.clone())
            .unwrap()
            .map(|p| p.relay_url),
        Some(s.relay.url().to_owned())
    );

    // Tampered, wrong version, or from another Beacon: rejected.
    let mut tampered = pushes[0].1.clone();
    tampered[100] ^= 1;
    assert!(matches!(
        s.core.open_push(tampered),
        Err(CoreError::PushRejected { .. })
    ));
    let mut future = pushes[0].1.clone();
    future[0] = 9;
    match s.core.open_push(future) {
        Err(CoreError::PushRejected { reason }) => assert!(reason.contains("version"), "{reason}"),
        other => panic!("{other:?}"),
    }

    // A new device token reaches the Relay.
    s.core
        .update_push_token(device("dev-2"), Arc::new(Http))
        .await
        .unwrap();
    assert_eq!(
        s.relay.log().pairings.values().collect::<Vec<_>>(),
        ["dev-2"]
    );

    // Sending another test works on its own.
    s.core.send_test_push(s.id.clone()).await.unwrap();
    s.relay.wait_pushes(2, Duration::from_secs(10));
}

#[tokio::test]
#[ignore = "needs docker"]
async fn turning_push_off_deletes_the_pairing_on_the_relay_and_the_host() {
    let s = setup().await;
    s.enable("dev-1").await;
    let envelope = s.relay.wait_pushes(1, Duration::from_secs(10))[0].1.clone();
    let token = s.relay.log().pairings.keys().next().unwrap().clone();

    let off = s
        .core
        .disable_push(s.id.clone(), Arc::new(Http))
        .await
        .unwrap();

    assert!(off.host_cleared);
    let log = s.relay.log();
    assert!(log.pairings.is_empty());
    assert!(log.deleted.contains(&token));
    assert!(s.host.check(&format!("test ! -e {PAIRING}")));
    assert_eq!(s.core.push_pairing(s.id.clone()).unwrap(), None);
    assert!(matches!(
        s.core.open_push(envelope),
        Err(CoreError::PushRejected { .. })
    ));
}

#[tokio::test]
#[ignore = "needs docker"]
async fn turning_push_off_works_when_the_host_cannot_unpair() {
    let s = setup().await;
    s.enable("dev-1").await;
    s.host.root("rm /home/aries/.local/bin/aries-beacon");

    let off = s
        .core
        .disable_push(s.id.clone(), Arc::new(Http))
        .await
        .unwrap();

    assert!(!off.host_cleared);
    assert!(s.relay.log().pairings.is_empty());
    assert_eq!(s.core.push_pairing(s.id.clone()).unwrap(), None);
}

#[tokio::test]
#[ignore = "needs docker"]
async fn removing_the_beacon_unpairs() {
    let s = setup().await;
    s.enable("dev-1").await;

    s.core.uninstall_beacon(s.id.clone()).await.unwrap();

    assert!(s.relay.log().pairings.is_empty(), "the Beacon revoked it");
    assert!(s.host.check(&format!("test ! -e {PAIRING}")));
    assert_eq!(s.core.push_pairing(s.id.clone()).unwrap(), None);
    // Nothing is left to revoke from the phone.
    assert_eq!(s.core.flush_push_revocations(Arc::new(Http)).await, 0);
}

#[tokio::test]
#[ignore = "needs docker"]
async fn removing_the_host_revokes_the_pairing_on_the_relay() {
    let s = setup().await;
    s.enable("dev-1").await;
    let token = s.relay.log().pairings.keys().next().unwrap().clone();

    s.core.remove_host(s.id.clone()).unwrap();
    assert_eq!(s.core.flush_push_revocations(Arc::new(Http)).await, 0);

    let log = s.relay.log();
    assert!(log.pairings.is_empty());
    assert_eq!(log.deleted, [token]);
}

// ---- Agent status pushes (ticket 28) ----------------------------------------

/// Runs `script` as the Host user with the user's runtime dir, so it talks to
/// the same herdr-core session as the Beacon's `serve`.
fn as_user(host: &SystemdHost, script: &str) -> String {
    let u = SystemdHost::USER;
    host.root(&format!(
        "runuser -u {u} -- env HOME=/home/{u} XDG_RUNTIME_DIR=/run/user/$(id -u {u}) PATH=/usr/local/bin:/usr/bin:/bin sh -c '{script}'"
    ))
}

#[tokio::test]
#[ignore = "needs docker"]
async fn a_blocked_agent_reaches_the_phone_once() {
    use base64::Engine;
    let s = setup_with(true).await;
    s.enable("dev-1").await;
    s.relay.wait_pushes(1, Duration::from_secs(10)); // the test push

    // The resident service is attached to the user's herdr-core.
    let deadline = std::time::Instant::now() + Duration::from_secs(30);
    while !as_user(&s.host, "~/.local/bin/aries-beacon status").contains("\"core_version\":\"") {
        assert!(std::time::Instant::now() < deadline, "serve never attached");
        tokio::time::sleep(Duration::from_millis(200)).await;
    }

    let agent = std::fs::read(aries_test_support::fake_agent_path()).unwrap();
    let agent = base64::engine::general_purpose::STANDARD.encode(agent);
    let script = base64::engine::general_purpose::STANDARD.encode(
        "status working\nstatus blocked permission Allow Bash?\nsleep 0.3\n\
         status blocked permission Allow Bash?\nsleep 0.3\nstatus error - marker\nwait\n",
    );
    s.host.root(&format!(
        "echo {agent} | base64 -d > /tmp/fake-agent && echo {script} | base64 -d > /tmp/script && chmod 644 /tmp/fake-agent /tmp/script"
    ));
    let run = as_user(
        &s.host,
        "herdr-core run --bg --json --no-parent --name claude -- env FAKE_AGENT_INPUT=/tmp/in sh /tmp/fake-agent /tmp/script",
    );
    let address = serde_json::from_str::<serde_json::Value>(run.trim()).unwrap()["address"]
        .as_str()
        .unwrap()
        .to_owned();

    // Blocked once (its repeat is suppressed), then the Error marker.
    let pushes = s.relay.wait_pushes(3, Duration::from_secs(20));
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert_eq!(s.relay.log().pushes.len(), 3);
    let notices: Vec<_> = pushes[1..]
        .iter()
        .map(|(_, e)| s.core.open_push(e.clone()).unwrap())
        .collect();
    assert_eq!(notices[0].host_id, s.id);
    assert_eq!(
        notices[0].content,
        PushContent::Status {
            terminal: address.clone(),
            name: Some("claude".into()),
            title: None,
            top_level: true,
            status: AgentStatus::Blocked {
                reason: Some(BlockedReason::Permission),
                message: Some("Allow Bash?".into()),
            },
            at: match notices[0].content {
                PushContent::Status { at, .. } => at,
                _ => 0,
            },
        }
    );
    assert!(matches!(
        &notices[1].content,
        PushContent::Status { terminal, status: AgentStatus::Error { .. }, .. } if *terminal == address
    ));
}
