//! The Chat over a real SSH exec channel: the installed Beacon on a docker
//! Host streams its lines to `watch_chat`.
//!
//! Needs docker. Run with `cargo test -p aries-core --test chat_ssh -- --ignored`.

mod common;

use std::sync::Arc;
use std::time::Duration;

use aries_core::{
    BeaconArtifact, BeaconFetcher, BeaconRelease, ChatSnapshot, ChatStatus, ChatWatch, CoreError,
    Endpoint, FetchError,
};
use aries_test_support::{SystemdHost, beacon_bin};

struct Bytes(Vec<u8>);

impl BeaconFetcher for Bytes {
    fn fetch(&self, _: String) -> Result<Vec<u8>, FetchError> {
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

async fn wait_for(watch: &ChatWatch, ok: impl Fn(&ChatSnapshot) -> bool) -> ChatSnapshot {
    tokio::time::timeout(Duration::from_secs(30), async {
        loop {
            let s = watch.next().await.unwrap();
            if ok(&s) {
                return s;
            }
        }
    })
    .await
    .expect("timed out")
}

#[tokio::test]
#[ignore = "needs docker"]
async fn the_hosts_beacon_streams_the_chat_over_ssh() {
    let host = SystemdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = common::core_in(data.path());
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

    // No Beacon yet.
    let watch = core.watch_chat(id.clone(), "t1".into(), None).unwrap();
    wait_for(&watch, |s| s.status == ChatStatus::NoBeacon).await;
    assert!(matches!(watch.next().await, Err(CoreError::Closed)));

    let bytes = std::fs::read(beacon_bin()).unwrap();
    core.set_beacon_release(BeaconRelease {
        version: env!("CARGO_PKG_VERSION").into(),
        artifacts: vec![BeaconArtifact {
            os: "linux".into(),
            arch: "x86_64".into(),
            url: "https://example.invalid/b".into(),
            sha256: sha256sum(&bytes),
        }],
    });
    core.install_beacon(id.clone(), Arc::new(Bytes(bytes)))
        .await
        .unwrap();

    // A plain Terminal: the Beacon finds no Agent and keeps following.
    assert!(host.check(
        "su aries -c 'herdr-core run --bg --no-parent --name plain -- sleep 300' >/dev/null"
    ));
    let watch = core.watch_chat(id.clone(), "plain".into(), None).unwrap();
    wait_for(&watch, |s| s.status == ChatStatus::NoAgent).await;
    watch.stop();

    let watch = core.watch_chat(id, "nope".into(), None).unwrap();
    wait_for(&watch, |s| s.status == ChatStatus::NoTerminal).await;
}
