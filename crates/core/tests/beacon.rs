//! Installing and removing the Beacon on a Linux Host over SSH, against a
//! docker Host running systemd (so the user service is real).
//!
//! Needs docker. Run with `cargo test -p aries-core --test beacon -- --ignored`.

mod common;

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use aries_core::{
    AriesCore, BeaconArtifact, BeaconFetcher, BeaconRelease, BeaconState, CoreError, Endpoint,
    FetchError, Linger,
};
use aries_test_support::{SystemdHost, beacon_bin};
use common::core_in;

const URL: &str = "https://example.invalid/aries-beacon-linux-x86_64";

/// Plays the phone's HTTP download: hands out fixed bytes and records URLs.
struct Fetcher {
    bytes: Vec<u8>,
    urls: Mutex<Vec<String>>,
}

impl Fetcher {
    fn new(bytes: Vec<u8>) -> Arc<Self> {
        Arc::new(Self {
            bytes,
            urls: Mutex::new(Vec::new()),
        })
    }
}

impl BeaconFetcher for Fetcher {
    fn fetch(&self, url: String) -> Result<Vec<u8>, FetchError> {
        self.urls.lock().unwrap().push(url);
        Ok(self.bytes.clone())
    }
}

/// `sha256sum`'s hex digest of `bytes`: an independent source of truth.
fn sha256sum(bytes: &[u8]) -> String {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("blob");
    std::fs::write(&path, bytes).unwrap();
    let out = std::process::Command::new("sha256sum")
        .arg(&path)
        .output()
        .unwrap();
    String::from_utf8(out.stdout).unwrap()[..64].to_owned()
}

fn release(version: &str, sha256: String) -> BeaconRelease {
    BeaconRelease {
        version: version.into(),
        artifacts: vec![BeaconArtifact {
            os: "linux".into(),
            arch: "x86_64".into(),
            url: URL.into(),
            sha256,
        }],
    }
}

struct Setup {
    host: SystemdHost,
    core: Arc<AriesCore>,
    id: String,
    _data: tempfile::TempDir,
}

fn setup() -> Setup {
    let host = SystemdHost::start();
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
    Setup {
        host,
        core,
        id: h.id,
        _data: data,
    }
}

async fn wait_attached(core: &AriesCore, id: &str) -> BeaconState {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let state = core.beacon_status(id.into()).await.unwrap();
        if let BeaconState::Installed {
            core_version: Some(_),
            ..
        } = &state
        {
            return state;
        }
        assert!(
            Instant::now() < deadline,
            "Beacon never attached: {state:?}"
        );
        tokio::time::sleep(Duration::from_millis(300)).await;
    }
}

const BIN: &str = "/home/aries/.local/bin/aries-beacon";
const UNIT: &str = "/home/aries/.config/systemd/user/aries-beacon.service";
const CALLS: &str = "/home/aries/.local/state/aries-beacon/calls.jsonl";

#[tokio::test]
#[ignore = "needs docker"]
async fn installs_the_beacon_as_a_user_service_and_removes_it_keeping_calls() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    let version = env!("CARGO_PKG_VERSION");
    s.core
        .set_beacon_release(release(version, sha256sum(&bytes)));
    let fetcher = Fetcher::new(bytes);

    assert_eq!(
        s.core.beacon_status(s.id.clone()).await.unwrap(),
        BeaconState::NotInstalled
    );

    let installed = s
        .core
        .install_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    assert_eq!(fetcher.urls.lock().unwrap().as_slice(), [URL]);
    assert_eq!(installed.version, version);
    // This Host denies the user `loginctl enable-linger`: the app explains.
    match &installed.linger {
        Linger::Failed { reason } => assert!(!reason.is_empty()),
        other => panic!("expected a linger failure, got {other:?}"),
    }
    assert!(s.host.check(&format!("test -x {BIN} && test -f {UNIT}")));
    assert!(s.host.check(
        "test -L /home/aries/.config/systemd/user/default.target.wants/aries-beacon.service"
    ));

    match wait_attached(&s.core, &s.id).await {
        BeaconState::Installed {
            version: v,
            running,
            core_version,
            supported,
            linger,
            ..
        } => {
            assert_eq!(v, version);
            assert!(running);
            assert_eq!(core_version.as_deref(), Some("0.1.17"));
            assert!(supported);
            assert!(!linger);
        }
        other => panic!("{other:?}"),
    }

    // A Call record the Beacon kept; removal must not take it.
    s.host.root(&format!(
        "echo '{{}}' > {CALLS} && chown aries:aries {CALLS}"
    ));

    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert_eq!(
        s.core.beacon_status(s.id.clone()).await.unwrap(),
        BeaconState::NotInstalled
    );
    assert!(
        s.host
            .check(&format!("test ! -e {BIN} && test ! -e {UNIT}"))
    );
    // No Beacon process is left (the bracket keeps grep from matching itself).
    assert!(!s.host.check("grep -qs aries-beaco[n] /proc/[0-9]*/cmdline"));
    assert!(s.host.check(&format!("test -f {CALLS}")));
}

#[tokio::test]
#[ignore = "needs docker"]
async fn refuses_a_binary_that_does_not_match_the_pinned_checksum() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    s.core
        .set_beacon_release(release(env!("CARGO_PKG_VERSION"), sha256sum(&bytes)));
    let mut tampered = bytes;
    tampered.extend_from_slice(b"evil");

    match s
        .core
        .install_beacon(s.id.clone(), Fetcher::new(tampered.clone()))
        .await
    {
        Err(CoreError::BeaconChecksum { expected, actual }) => {
            assert_ne!(expected, actual);
            assert_eq!(actual, sha256sum(&tampered));
        }
        other => panic!("expected BeaconChecksum, got {other:?}"),
    }
    assert!(s.host.check("test ! -e /home/aries/.local/bin"));
    assert_eq!(
        s.core.beacon_status(s.id.clone()).await.unwrap(),
        BeaconState::NotInstalled
    );
}

/// Stands in for a Beacon release outside the range this app accepts.
const FUTURE_BEACON: &str = r#"#!/bin/sh
case "$1" in
  --version) echo "aries-beacon 9.0.0 (contract 1)" ;;
  status) echo '{"version":"9.0.0","contract":1,"running":true,"core_version":"0.1.17","linger":true}' ;;
  serve) exec sleep 1000000 ;;
esac
"#;

#[tokio::test]
#[ignore = "needs docker"]
async fn shows_a_beacon_outside_the_allowed_range_and_reports_linger() {
    let s = setup();
    s.host.enable_linger();
    let bytes = FUTURE_BEACON.as_bytes().to_vec();
    s.core
        .set_beacon_release(release("9.0.0", sha256sum(&bytes)));

    let installed = s
        .core
        .install_beacon(s.id.clone(), Fetcher::new(bytes))
        .await
        .unwrap();
    assert_eq!(installed.linger, Linger::Enabled);

    match s.core.beacon_status(s.id.clone()).await.unwrap() {
        BeaconState::Installed {
            version,
            supported,
            allowed,
            ..
        } => {
            assert_eq!(version, "9.0.0");
            assert!(!supported);
            assert_eq!(allowed, ">=0.1.0, <0.2.0");
        }
        other => panic!("{other:?}"),
    }
}

#[tokio::test]
async fn needs_an_ssh_host() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let h = core
        .add_host("local".into(), common::script_endpoint("true"))
        .unwrap();
    assert!(matches!(
        core.install_beacon(h.id, Fetcher::new(vec![])).await,
        Err(CoreError::BeaconNeedsSsh)
    ));
}
