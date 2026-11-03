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
    AgentStatus, AriesCore, BeaconArtifact, BeaconFetcher, BeaconRelease, BlockedReason,
    CallReplyOutcome, CallUrgency, CoreError, Endpoint, FetchError, HttpClient, HttpRequest,
    HttpResponse, PushContent, PushDevice, PushPolicy, PushProgress, PushStep,
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
async fn the_push_policy_needs_an_ssh_host() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let h = core
        .add_host("local".into(), common::script_endpoint("true"))
        .unwrap();
    assert!(matches!(
        core.push_policy(h.id.clone()).await,
        Err(CoreError::BeaconNeedsSsh)
    ));
    assert!(matches!(
        core.set_push_policy(h.id, PushPolicy::default()).await,
        Err(CoreError::BeaconNeedsSsh)
    ));
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

/// Answers every request with one canned reply and counts the calls.
struct Canned {
    reply: Result<(u16, &'static str), &'static str>,
    calls: Mutex<u32>,
}

impl Canned {
    fn new(reply: Result<(u16, &'static str), &'static str>) -> Arc<Self> {
        Arc::new(Self {
            reply,
            calls: Mutex::new(0),
        })
    }
}

impl HttpClient for Canned {
    fn send(&self, _request: HttpRequest) -> Result<HttpResponse, FetchError> {
        *self.calls.lock().unwrap() += 1;
        match self.reply {
            Ok((status, body)) => Ok(HttpResponse {
                status,
                body: body.as_bytes().to_vec(),
            }),
            Err(detail) => Err(FetchError::Failed {
                detail: detail.into(),
            }),
        }
    }
}

/// The Register failure's detail for turning push on at `relay_url`.
async fn register_failure(relay_url: &str, http: Arc<Canned>) -> String {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let id = dead_ssh_host(&core);
    let r = core
        .enable_push(
            id.clone(),
            relay_url.into(),
            device("d"),
            http,
            Arc::new(Steps::default()),
        )
        .await;
    assert_eq!(core.push_pairing(id).unwrap(), None);
    match r {
        Err(CoreError::PushFailed {
            step: PushStep::Register,
            detail,
        }) => detail,
        other => panic!("expected a Register failure, got {other:?}"),
    }
}

#[tokio::test]
async fn rejects_a_relay_url_that_is_not_https_before_calling_it() {
    for (url, why) in [
        ("relay.example", "https://"),
        ("ftp://relay.example", "https://"),
        ("http://relay.example", "https://"),
        ("https://", "no host"),
        ("https://me@relay.example", "user"),
        ("https://relay.example/?a=1", "query"),
        ("https://relay.example/#x", "query"),
        ("https://relay example", "space"),
    ] {
        let http = Canned::new(Ok((201, r#"{"token":"t"}"#)));
        let detail = register_failure(url, http.clone()).await;
        assert!(detail.contains(why), "{url}: {detail}");
        assert_eq!(*http.calls.lock().unwrap(), 0, "{url}");
    }
}

#[tokio::test]
async fn accepts_https_and_loopback_http_relay_urls() {
    // The URL passes the check and the (dead) Host fails at Pair.
    for url in [
        "https://relay.example",
        "https://relay.example:8443/aries/",
        " HTTPS://Relay.Example ",
        "http://127.0.0.1:8787",
        "http://localhost:8787",
        "http://[::1]:8787",
    ] {
        let data = tempfile::tempdir().unwrap();
        let core = core_in(data.path());
        let id = dead_ssh_host(&core);
        let r = core
            .enable_push(
                id,
                url.into(),
                device("d"),
                Canned::new(Ok((201, r#"{"token":"t"}"#))),
                Arc::new(Steps::default()),
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
            "{url}: {r:?}"
        );
    }
}

#[tokio::test]
async fn says_why_the_relay_did_not_register_the_phone() {
    for (reply, why) in [
        (Err("connection refused"), "could not reach the Relay"),
        (Ok((404, "<html>Not Found</html>")), "no Aries Relay"),
        (Ok((200, "<html>hello</html>")), "not an Aries Relay"),
        (Ok((201, r#"{"ok":true}"#)), "not an Aries Relay"),
        (Ok((429, r#"{"error":"rate_limited"}"#)), "too many"),
        (
            Ok((503, r#"{"error":"fcm_not_configured"}"#)),
            "fcm_not_configured",
        ),
    ] {
        let detail = register_failure("https://relay.example", Canned::new(reply)).await;
        assert!(detail.contains(why), "{reply:?}: {detail}");
    }
}

#[tokio::test]
async fn shortens_a_long_relay_error_page() {
    let page: &'static str = Box::leak("x".repeat(5000).into_boxed_str());
    let detail = register_failure("https://relay.example", Canned::new(Ok((500, page)))).await;
    assert!(detail.contains("HTTP 500"), "{detail}");
    assert!(detail.len() < 400, "{} bytes", detail.len());
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
async fn changing_the_relay_unpairs_the_old_one_and_pairs_the_new_one() {
    let s = setup().await;
    s.enable("dev-1").await;
    let old_token = s.relay.log().pairings.keys().next().unwrap().clone();
    s.relay.wait_pushes(1, Duration::from_secs(10));

    // A Relay that does not answer: nothing changes, the old one still works.
    let r = s
        .core
        .enable_push(
            s.id.clone(),
            "http://127.0.0.1:9".into(),
            device("dev-1"),
            Arc::new(Http),
            Arc::new(Steps::default()),
        )
        .await;
    assert!(
        matches!(
            r,
            Err(CoreError::PushFailed {
                step: PushStep::Register,
                ..
            })
        ),
        "{r:?}"
    );
    assert_eq!(
        s.core
            .push_pairing(s.id.clone())
            .unwrap()
            .unwrap()
            .relay_url,
        s.relay.url()
    );
    s.core.send_test_push(s.id.clone()).await.unwrap();
    s.relay.wait_pushes(2, Duration::from_secs(10));

    // The self-hosted Relay, reached by the Beacon through its own tunnel.
    let mine = FakeRelay::start();
    let local = mine.url().trim_start_matches("http://").to_owned();
    let port = local.rsplit(':').next().unwrap().parse().unwrap();
    let _tunnel = s.host.reverse_tunnel(port, &local);
    let steps = Arc::new(Steps::default());
    let on = s
        .core
        .enable_push(
            s.id.clone(),
            format!("{}/", mine.url()),
            device("dev-1"),
            Arc::new(Http),
            steps.clone(),
        )
        .await
        .unwrap();

    assert_eq!(on.relay_url, mine.url());
    assert_eq!(
        *steps.0.lock().unwrap(),
        [
            PushStep::Register,
            PushStep::Pair,
            PushStep::Unpair,
            PushStep::Test
        ]
    );
    let old = s.relay.log();
    assert!(old.pairings.is_empty(), "{old:?}");
    assert!(old.deleted.contains(&old_token));
    assert_eq!(old.pushes.len(), 2, "nothing more went to the old Relay");
    assert_eq!(mine.log().pairings.values().collect::<Vec<_>>(), ["dev-1"]);
    let pushes = mine.wait_pushes(1, Duration::from_secs(10));
    let notice = s.core.open_push(pushes[0].1.clone()).unwrap();
    assert!(matches!(notice.content, PushContent::Test { .. }));
    assert_eq!(
        s.core
            .push_pairing(s.id.clone())
            .unwrap()
            .unwrap()
            .relay_url,
        mine.url()
    );
    assert_eq!(s.core.flush_push_revocations(Arc::new(Http)).await, 0);
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

#[tokio::test]
#[ignore = "needs docker"]
async fn a_flush_preserves_a_revocation_queued_while_http_is_in_flight() {
    struct RemoveDuringDelete {
        core: Arc<AriesCore>,
        host: Mutex<Option<String>>,
    }
    impl HttpClient for RemoveDuringDelete {
        fn send(&self, request: HttpRequest) -> Result<HttpResponse, FetchError> {
            if let Some(host) = self.host.lock().unwrap().take() {
                self.core.remove_host(host).unwrap();
            }
            Http.send(request)
        }
    }
    let s = setup().await;
    s.enable("dev-1").await;
    // Another phone-side Host record on the same server has its own pairing.
    let other = s
        .core
        .add_host(
            "other".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: s.host.port(),
                user: SystemdHost::USER.into(),
            },
        )
        .unwrap()
        .id;
    s.core
        .trust_host_key(other.clone(), s.host.host_key_fingerprint())
        .unwrap();
    s.core.remove_host(s.id.clone()).unwrap();
    s.core
        .enable_push(
            other.clone(),
            s.relay.url().into(),
            device("dev-2"),
            Arc::new(Http),
            Arc::new(Steps::default()),
        )
        .await
        .unwrap();
    let client = Arc::new(RemoveDuringDelete {
        core: s.core.clone(),
        host: Mutex::new(Some(other)),
    });
    assert_eq!(s.core.flush_push_revocations(client).await, 1);
    assert_eq!(s.core.flush_push_revocations(Arc::new(Http)).await, 0);
    assert!(s.relay.log().pairings.is_empty());
}

#[tokio::test]
#[ignore = "needs docker"]
async fn a_failed_phone_pairing_save_keeps_the_previous_push_working() {
    let s = setup().await;
    s.enable("dev-1").await;
    let data = s._data.path().join("hosts").join(&s.id);
    // Block both the current writer and the staged writer without harming
    // the existing committed pairing. No filesystem permissions/root assumptions.
    std::fs::create_dir(data.join(".push.json.tmp")).unwrap();
    std::fs::create_dir(data.join(".push-pending.json.tmp")).unwrap();
    let result = s
        .core
        .enable_push(
            s.id.clone(),
            s.relay.url().into(),
            device("dev-2"),
            Arc::new(Http),
            Arc::new(Steps::default()),
        )
        .await;
    assert!(result.is_err());
    s.core.send_test_push(s.id.clone()).await.unwrap();
    let pushes = s.relay.wait_pushes(2, Duration::from_secs(10));
    s.core.open_push(pushes.last().unwrap().1.clone()).unwrap();
}

#[tokio::test]
#[ignore = "needs docker"]
async fn a_lost_host_commit_reply_recovers_after_restarting_the_phone() {
    let s = setup().await;
    s.enable("dev-1").await;
    s.host.root(
        r#"mv /home/aries/.local/bin/aries-beacon /home/aries/.local/bin/aries-beacon.real
cat > /home/aries/.local/bin/aries-beacon <<'SH'
#!/bin/sh
if [ "$1" = pair-commit ] && [ ! -e "$HOME/commit-lost" ]; then
  "$0.real" "$@" || exit 1
  touch "$HOME/commit-lost"
  exit 1
fi
exec "$0.real" "$@"
SH
chmod 755 /home/aries/.local/bin/aries-beacon"#,
    );
    let result = s
        .core
        .enable_push(
            s.id.clone(),
            s.relay.url().into(),
            device("dev-2"),
            Arc::new(Http),
            Arc::new(Steps::default()),
        )
        .await;
    assert!(result.is_err(), "the Host commit reply was lost");
    let core = core_in(s._data.path());
    // A push already sent with the new Host key is readable even before
    // the pending phone transaction has been recovered after restart.
    assert!(
        s.host
            .check("su aries -c '/home/aries/.local/bin/aries-beacon push-test'")
    );
    let pushes = s.relay.wait_pushes(2, Duration::from_secs(10));
    core.open_push(pushes.last().unwrap().1.clone()).unwrap();
    core.send_test_push(s.id.clone()).await.unwrap();
    assert_eq!(core.flush_push_revocations(Arc::new(Http)).await, 0);
    let pushes = s.relay.wait_pushes(3, Duration::from_secs(10));
    core.open_push(pushes.last().unwrap().1.clone()).unwrap();
    assert_eq!(s.relay.log().pairings.len(), 1);
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
            turn: match notices[0].content {
                PushContent::Status { turn, .. } => turn,
                _ => None,
            },
            question: None,
            excerpt: None,
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

// ---- Call pushes (ticket 33) --------------------------------------------------

#[tokio::test]
#[ignore = "needs docker"]
async fn a_call_reaches_the_phone_with_its_urgency_and_choices() {
    let s = setup_with(true).await;
    s.enable("dev-1").await;
    s.relay.wait_pushes(1, Duration::from_secs(10)); // the test push

    let run = as_user(
        &s.host,
        "herdr-core run --bg --json --no-parent --name claude -- sh -c \"$HOME/.local/bin/aries-beacon call --title Deploy? --body Ready --urgency emergency --choice Go --choice Stop; sleep 60\"",
    );
    let address = serde_json::from_str::<serde_json::Value>(run.trim()).unwrap()["address"]
        .as_str()
        .unwrap()
        .to_owned();

    let pushes = s.relay.wait_pushes(2, Duration::from_secs(20));
    let notice = s.core.open_push(pushes[1].1.clone()).unwrap();
    assert_eq!(notice.host_id, s.id);
    match notice.content {
        PushContent::Call {
            id,
            terminal,
            top_level,
            title,
            body,
            urgency,
            choices,
            ..
        } => {
            assert!(id.starts_with('c'), "{id}");
            assert_eq!(terminal, address);
            assert!(top_level);
            assert_eq!((title.as_str(), body.as_str()), ("Deploy?", "Ready"));
            assert_eq!(urgency, CallUrgency::Emergency);
            assert_eq!(choices, ["Go", "Stop"]);
        }
        other => panic!("not a Call push: {other:?}"),
    }
}

// ---- Push policy (ticket 30) ---------------------------------------------------

#[tokio::test]
#[ignore = "needs docker"]
async fn the_push_policy_is_kept_on_the_host_and_turns_on_done() {
    use base64::Engine;
    let s = setup_with(true).await;
    s.enable("dev-1").await;
    s.relay.wait_pushes(1, Duration::from_secs(10)); // the test push

    let defaults = s.core.push_policy(s.id.clone()).await.unwrap();
    assert_eq!(
        defaults,
        PushPolicy {
            blocked: true,
            error: true,
            done: false,
            child_agents: false,
        }
    );
    let chosen = PushPolicy {
        blocked: false,
        done: true,
        ..defaults
    };
    assert_eq!(
        s.core.set_push_policy(s.id.clone(), chosen).await.unwrap(),
        chosen
    );
    assert_eq!(s.core.push_policy(s.id.clone()).await.unwrap(), chosen);
    let file = "/home/aries/.local/state/aries-beacon/push-policy.json";
    assert!(s.host.check(&format!(
        "test \"$(stat -c %a {file})\" = 600 && test \"$(stat -c %U {file})\" = aries"
    )));

    let deadline = std::time::Instant::now() + Duration::from_secs(30);
    while !as_user(&s.host, "~/.local/bin/aries-beacon status").contains("\"core_version\":\"") {
        assert!(std::time::Instant::now() < deadline, "serve never attached");
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let agent = std::fs::read(aries_test_support::fake_agent_path()).unwrap();
    let agent = base64::engine::general_purpose::STANDARD.encode(agent);
    // Blocked is off now; the finished (unseen) turn is the only push.
    let script = base64::engine::general_purpose::STANDARD.encode(
        "status working\nstatus blocked permission Allow Bash?\nsleep 0.3\n\
         status working\nsleep 0.2\nstatus done\nwait\n",
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

    s.relay.wait_pushes(2, Duration::from_secs(20));
    tokio::time::sleep(Duration::from_millis(500)).await;
    let all: Vec<_> = s
        .relay
        .log()
        .pushes
        .iter()
        .map(|(_, e)| s.core.open_push(e.clone()).unwrap().content)
        .collect();
    assert_eq!(all.len(), 2, "{all:?}");
    assert!(
        matches!(
            &all[1],
            PushContent::Status { terminal, status: AgentStatus::Done, .. } if *terminal == address
        ),
        "{:?}",
        all[1]
    );
}

// ---- Answering a Call from its notification (ticket 36) ---------------------

/// The path behind a notification's Choice button: a fake Agent sends a
/// Call, the fake Relay gets the envelope, the core opens it, and the
/// Choice (picked by index from the push) is typed into the Agent's
/// Terminal. Tapping it again (or a retry after an unknown result) types
/// nothing more.
#[tokio::test]
#[ignore = "needs docker"]
async fn a_choice_from_a_call_push_reaches_the_agent_once() {
    use base64::Engine;
    let s = setup_with(true).await;
    s.enable("dev-1").await;
    s.relay.wait_pushes(1, Duration::from_secs(10)); // the test push

    let agent = std::fs::read(aries_test_support::fake_agent_path()).unwrap();
    let agent = base64::engine::general_purpose::STANDARD.encode(agent);
    let script = base64::engine::general_purpose::STANDARD.encode(
        "status working\n\
         run $HOME/.local/bin/aries-beacon call --title Deploy? --urgency urgent --choice Go --choice Stop >/dev/null 2>&1\n\
         status idle\nwait\nstatus working\n",
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

    let pushes = s.relay.wait_pushes(2, Duration::from_secs(20));
    let notice = s.core.open_push(pushes[1].1.clone()).unwrap();
    let PushContent::Call {
        id,
        terminal,
        choices,
        ..
    } = notice.content
    else {
        panic!("not a Call push: {:?}", notice.content);
    };
    assert_eq!(terminal, address);
    assert_eq!(choices, ["Go", "Stop"]);
    assert!(
        s.core
            .can_answer_in_background(notice.host_id.clone())
            .unwrap()
    );

    let reply = s
        .core
        .reply_call(notice.host_id.clone(), id.clone(), 1)
        .await
        .unwrap();
    assert_eq!(reply.outcome, CallReplyOutcome::Delivered, "{reply:?}");
    assert_eq!(reply.answer.unwrap().text, "Stop");
    let inputs = || s.host.root("cat /tmp/in");
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while inputs().trim().is_empty() {
        assert!(std::time::Instant::now() < deadline, "nothing typed");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    assert_eq!(inputs().trim(), "Stop");

    // The same button again: the Host already has the answer.
    let again = s.core.reply_call(notice.host_id, id, 1).await.unwrap();
    assert_eq!(again.outcome, CallReplyOutcome::Answered);
    tokio::time::sleep(Duration::from_millis(800)).await;
    assert_eq!(inputs().trim(), "Stop");
}

/// Full notification payload path: a transcript-backed question, then Done,
/// through the resident Beacon, encrypted Relay envelope and public core API.
#[tokio::test]
#[ignore = "needs docker"]
async fn question_and_done_details_reach_the_phone() {
    use base64::Engine;
    let s = setup_with(true).await;
    s.enable("dev-1").await;
    s.core
        .set_push_policy(
            s.id.clone(),
            PushPolicy {
                done: true,
                ..PushPolicy::default()
            },
        )
        .await
        .unwrap();
    s.relay.wait_pushes(1, Duration::from_secs(10));
    let deadline = std::time::Instant::now() + Duration::from_secs(30);
    while !as_user(&s.host, "~/.local/bin/aries-beacon status").contains("\"core_version\":\"") {
        assert!(std::time::Instant::now() < deadline, "serve never attached");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    let question = serde_json::json!({"type":"assistant","uuid":"m1","message":{"content":[{"type":"tool_use","id":"ask-1","name":"AskUserQuestion","input":{"questions":[{"question":"Ship?","multiSelect":false,"options":[{"label":"Yes"},{"label":"No"}]}]}}]}});
    let done = serde_json::json!({"type":"assistant","uuid":"m2","message":{"content":[{"type":"text","text":"완료".repeat(150)}]}});
    let transcript =
        base64::engine::general_purpose::STANDARD.encode(format!("{question}\n{done}\n"));
    let agent = base64::engine::general_purpose::STANDARD
        .encode(std::fs::read(aries_test_support::fake_agent_path()).unwrap());
    let script = base64::engine::general_purpose::STANDARD.encode(
        "claude-session /home/aries/.claude S1 /work/a\nstatus working\nstatus blocked question Ship?\nkeys 1\nstatus working\nstatus done\nwait\n"
    );
    s.host.root(&format!("mkdir -p /home/aries/.claude/projects/-work-a && echo {transcript} | base64 -d > /home/aries/.claude/projects/-work-a/S1.jsonl && chown -R aries:aries /home/aries/.claude && echo {agent} | base64 -d > /tmp/fake-agent && echo {script} | base64 -d > /tmp/script && chmod 644 /tmp/fake-agent /tmp/script"));
    let run = as_user(
        &s.host,
        "herdr-core run --bg --json --no-parent --name claude -- env FAKE_AGENT_INPUT=/tmp/in sh /tmp/fake-agent /tmp/script",
    );
    let address = serde_json::from_str::<serde_json::Value>(run.trim()).unwrap()["address"]
        .as_str()
        .unwrap()
        .to_owned();
    let pushes = s.relay.wait_pushes(2, Duration::from_secs(20));
    let notice = s.core.open_push(pushes[1].1.clone()).unwrap();
    match notice.content {
        PushContent::Status {
            terminal,
            turn,
            question,
            excerpt,
            ..
        } => {
            assert_eq!(terminal, address);
            assert_eq!(turn, Some(1));
            assert_eq!(
                question,
                Some(aries_core::PushQuestion {
                    id: "ask-1".into(),
                    text: "Ship?".into(),
                    options: vec!["Yes".into(), "No".into()]
                })
            );
            assert_eq!(excerpt, None);
        }
        other => panic!("{other:?}"),
    }
    s.core
        .answer_question(s.id.clone(), address, "ask-1".into(), vec![vec![0]])
        .await
        .unwrap();
    let pushes = s.relay.wait_pushes(3, Duration::from_secs(20));
    let notice = s.core.open_push(pushes[2].1.clone()).unwrap();
    match notice.content {
        PushContent::Status {
            status: AgentStatus::Done,
            turn,
            question,
            excerpt,
            ..
        } => {
            assert_eq!(turn, Some(2));
            assert_eq!(question, None);
            assert_eq!(excerpt, Some(format!("{}…", "완료".repeat(100))));
        }
        other => panic!("{other:?}"),
    }
}

/// Real resident Beacon + real herdr-core over a docker SSH Host: no events
/// are needed for the second update, and Done bypasses the ordinary policy.
#[tokio::test]
#[ignore = "needs docker; observes the real 60 second progress interval"]
async fn watched_progress_is_normal_priority_throttled_and_replaced_on_done() {
    use base64::Engine;
    let s = setup_with(true).await;
    s.enable("watched-device").await;
    s.relay.wait_pushes(1, Duration::from_secs(10));
    let deadline = std::time::Instant::now() + Duration::from_secs(30);
    while !as_user(&s.host, "~/.local/bin/aries-beacon status").contains("\"core_version\":\"") {
        assert!(std::time::Instant::now() < deadline, "serve never attached");
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let agent = base64::engine::general_purpose::STANDARD
        .encode(std::fs::read(aries_test_support::fake_agent_path()).unwrap());
    let script = base64::engine::general_purpose::STANDARD
        .encode("status working\nwait\nstatus done\nwait\n");
    s.host.root(&format!("echo {agent} | base64 -d > /tmp/watched-agent && echo {script} | base64 -d > /tmp/watched-script && chmod 644 /tmp/watched-agent /tmp/watched-script"));
    let run = as_user(
        &s.host,
        "herdr-core run --bg --json --no-parent --name watched -- env FAKE_AGENT_INPUT=/tmp/watched-input sh /tmp/watched-agent /tmp/watched-script",
    );
    let address = serde_json::from_str::<serde_json::Value>(run.trim()).unwrap()["address"]
        .as_str()
        .unwrap()
        .to_owned();
    let conn = s.core.connect(s.id.clone()).await.unwrap();
    assert!(
        conn.set_watched(address.clone(), true)
            .await
            .unwrap()
            .watched
    );
    let first = s.relay.wait_pushes(2, Duration::from_secs(15));
    let first_at = match s.core.open_push(first[1].1.clone()).unwrap().content {
        PushContent::Progress {
            terminal,
            at,
            child_agents,
            cancelled,
            ..
        } => {
            assert_eq!(terminal, address);
            assert_eq!(child_agents, 0);
            assert!(!cancelled);
            at
        }
        other => panic!("{other:?}"),
    };
    // Repeated label events cannot bypass the per-Agent timer.
    for _ in 0..3 {
        conn.set_watched(address.clone(), true).await.unwrap();
    }
    tokio::time::sleep(Duration::from_secs(3)).await;
    assert_eq!(s.relay.log().pushes.len(), 2);
    let second = s.relay.wait_pushes(3, Duration::from_secs(70));
    match s.core.open_push(second[2].1.clone()).unwrap().content {
        PushContent::Progress {
            at, elapsed_secs, ..
        } => {
            assert!(at >= first_at + 60);
            assert!(elapsed_secs >= 60);
        }
        other => panic!("{other:?}"),
    }
    assert_eq!(&s.relay.log().push_priorities[1..], ["normal", "normal"]);
    as_user(&s.host, &format!("herdr-core send {address} finish"));
    let all = s.relay.wait_pushes(4, Duration::from_secs(15));
    assert!(matches!(
        s.core.open_push(all[3].1.clone()).unwrap().content,
        PushContent::Status {
            status: AgentStatus::Done,
            ..
        }
    ));
    assert_eq!(s.relay.log().push_priorities[3], "high");
    tokio::time::sleep(Duration::from_secs(2)).await;
    assert_eq!(s.relay.log().pushes.len(), 4);
    conn.disconnect().await;
}

/// FCM can arrive before the registration HTTP response. The app must
/// answer only a challenge matching its own locally initiated registration.
#[tokio::test]
async fn registration_waits_for_device_proof_before_pairing_the_host() {
    struct OwnershipRelay {
        core: Arc<AriesCore>,
        confirmed: Arc<Mutex<Option<String>>>,
        host: String,
    }
    struct Confirmation(Arc<Mutex<Option<String>>>);
    impl HttpClient for Confirmation {
        fn send(&self, r: HttpRequest) -> Result<HttpResponse, FetchError> {
            assert_eq!(r.url, "https://relay.test/v1/pairing/confirm");
            let body: serde_json::Value = serde_json::from_slice(&r.body).unwrap();
            assert_eq!(body["challenge"], "p".repeat(43));
            *self.0.lock().unwrap() = Some(body["verification_id"].as_str().unwrap().to_owned());
            Ok(HttpResponse {
                status: 204,
                body: vec![],
            })
        }
    }
    impl HttpClient for OwnershipRelay {
        fn send(&self, r: HttpRequest) -> Result<HttpResponse, FetchError> {
            let body: serde_json::Value = serde_json::from_slice(&r.body).unwrap_or_default();
            let (status, reply) = if r.method == "POST" && r.url.ends_with("/v1/pairings") {
                let id = body["verification_id"]
                    .as_str()
                    .expect("local registration ID")
                    .to_owned();
                assert_eq!(
                    self.core
                        .push_verification_state(self.host.clone())
                        .unwrap(),
                    Some(aries_core::PushVerificationState::Pending)
                );
                let receiver: Arc<dyn HttpClient> = Arc::new(Confirmation(self.confirmed.clone()));
                let rt = tokio::runtime::Handle::current();
                assert!(
                    !rt.block_on(self.core.confirm_push_token(
                        "x".repeat(43),
                        "p".repeat(43),
                        receiver.clone()
                    ))
                    .unwrap()
                );
                assert!(self.confirmed.lock().unwrap().is_none());
                assert!(
                    rt.block_on(self.core.confirm_push_token(id, "p".repeat(43), receiver))
                        .unwrap()
                );
                (
                    202,
                    serde_json::json!({"token": "pending-token", "expires_in": 120}),
                )
            } else if r.method == "GET" {
                assert_eq!(r.headers["Authorization"], "Bearer pending-token");
                (
                    200,
                    serde_json::json!({"status": "active", "verification_id": self.confirmed.lock().unwrap().clone()}),
                )
            } else {
                assert_eq!(r.method, "DELETE");
                (204, serde_json::Value::Null)
            };
            Ok(HttpResponse {
                status,
                body: reply.to_string().into_bytes(),
            })
        }
    }
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = dead_ssh_host(&core);
    let steps = Arc::new(Steps::default());
    let http = Arc::new(OwnershipRelay {
        core: core.clone(),
        confirmed: Default::default(),
        host: host.clone(),
    });
    let r = core
        .enable_push(
            host.clone(),
            "https://relay.test".into(),
            device("phone"),
            http,
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
        [PushStep::Register, PushStep::Verify, PushStep::Pair]
    );
    assert_eq!(
        core.push_verification_state(host).unwrap(),
        Some(aries_core::PushVerificationState::Verified)
    );
}

#[tokio::test]
async fn failed_verification_never_pairs_the_host_and_is_visible_after_restart() {
    #[derive(Default)]
    struct PendingRelay(Mutex<Vec<String>>);
    impl HttpClient for PendingRelay {
        fn send(&self, r: HttpRequest) -> Result<HttpResponse, FetchError> {
            self.0.lock().unwrap().push(r.method.clone());
            Ok(match r.method.as_str() {
                "POST" => HttpResponse {
                    status: 202,
                    body: br#"{"token":"pending","expires_in":120}"#.to_vec(),
                },
                "GET" => HttpResponse {
                    status: 401,
                    body: br#"{"error":"unknown_pairing"}"#.to_vec(),
                },
                "DELETE" => HttpResponse {
                    status: 204,
                    body: vec![],
                },
                _ => panic!("unexpected request"),
            })
        }
    }
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = dead_ssh_host(&core);
    let steps = Arc::new(Steps::default());
    let relay = Arc::new(PendingRelay::default());
    let r = core
        .enable_push(
            host.clone(),
            "https://relay.test".into(),
            device("phone"),
            relay.clone(),
            steps.clone(),
        )
        .await;
    assert!(
        matches!(
            r,
            Err(CoreError::PushFailed {
                step: PushStep::Verify,
                ..
            })
        ),
        "{r:?}"
    );
    assert_eq!(
        *steps.0.lock().unwrap(),
        [PushStep::Register, PushStep::Verify]
    );
    assert_eq!(*relay.0.lock().unwrap(), ["POST", "GET", "DELETE"]);
    assert!(core.push_pairing(host.clone()).unwrap().is_none());
    let restarted = core_in(data.path());
    assert_eq!(
        restarted.push_verification_state(host.clone()).unwrap(),
        Some(aries_core::PushVerificationState::Failed)
    );
    restarted.remove_host(host.clone()).unwrap();
    assert!(!data.path().join("hosts").join(host).exists());
}
