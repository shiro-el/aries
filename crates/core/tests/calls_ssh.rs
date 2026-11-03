//! Call history over SSH, from a docker Host with the Beacon installed by
//! the app. Needs docker: `cargo test -p aries-core --test calls_ssh -- --ignored`.

mod common;

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use aries_core::{
    BeaconArtifact, BeaconFetcher, BeaconRelease, CallFeedState, CallInfo, CallListener, CoreError,
    Endpoint, FetchError,
};
use aries_test_support::{SystemdHost, beacon_bin};
use common::core_in;

struct Fetcher(Vec<u8>);

impl BeaconFetcher for Fetcher {
    fn fetch(&self, _url: String) -> Result<Vec<u8>, FetchError> {
        Ok(self.0.clone())
    }
}

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

#[derive(Default)]
struct Recorder {
    titles: Mutex<Vec<String>>,
    new: Mutex<Vec<String>>,
    live: Mutex<bool>,
}

impl CallListener for Recorder {
    fn on_calls(&self, calls: Vec<CallInfo>) {
        *self.titles.lock().unwrap() = calls.into_iter().map(|c| c.title).collect();
    }
    fn on_new_call(&self, call: CallInfo) {
        self.new.lock().unwrap().push(call.title);
    }
    fn on_state(&self, state: CallFeedState) {
        *self.live.lock().unwrap() = state == CallFeedState::Live;
    }
}

async fn wait_until(what: &str, ok: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(30);
    while !ok() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn the_app_reads_and_follows_call_history_over_ssh() {
    let host = SystemdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    host.authorize(&core.ensure_key().unwrap().openssh);
    let id = core
        .add_host(
            "box".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: host.port(),
                user: SystemdHost::USER.into(),
            },
        )
        .unwrap()
        .id;
    core.trust_host_key(id.clone(), host.host_key_fingerprint())
        .unwrap();

    assert!(matches!(
        core.calls(id.clone()).await,
        Err(CoreError::BeaconNotInstalled)
    ));

    let bytes = std::fs::read(beacon_bin()).unwrap();
    core.set_beacon_release(BeaconRelease {
        version: env!("CARGO_PKG_VERSION").into(),
        artifacts: vec![BeaconArtifact {
            os: "linux".into(),
            arch: "x86_64".into(),
            url: "https://example.invalid/beacon".into(),
            sha256: sha256sum(&bytes),
        }],
    });
    core.install_beacon(id.clone(), Arc::new(Fetcher(bytes)))
        .await
        .unwrap();

    let call = |title: &str| {
        host.root(&format!(
            "su - aries -c \"/home/aries/.local/bin/aries-beacon call --title '{title}' --choice Yes\""
        ));
    };
    call("first");
    let calls = core.calls(id.clone()).await.unwrap();
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].title, "first");
    assert_eq!(calls[0].choices, ["Yes"]);
    // `su` is no herdr-core Terminal: recorded, caller unconfirmed.
    assert_eq!(calls[0].terminal, None);

    let rec = Arc::new(Recorder::default());
    let watch = core.watch_calls(id.clone(), rec.clone()).unwrap();
    wait_until("the first sync", || *rec.live.lock().unwrap()).await;
    call("second");
    wait_until("the second Call", || {
        rec.titles.lock().unwrap().as_slice() == ["second", "first"]
    })
    .await;
    assert_eq!(rec.new.lock().unwrap().as_slice(), ["second"]);
    watch.stop();
}
