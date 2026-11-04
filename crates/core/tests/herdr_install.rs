//! Public core API against a docker sshd Host; no user's session is touched.
mod common;

use aries_core::{
    AriesCore, BeaconFetcher, Endpoint, FetchError, HerdrCoreState, HerdrInstallListener,
    HerdrInstallStep,
};
use aries_test_support::{SshdHost, herdr_core_bin};
use std::sync::{Arc, Mutex};

struct Download(Vec<u8>);
impl BeaconFetcher for Download {
    fn fetch(&self, _: String) -> Result<Vec<u8>, FetchError> {
        Ok(self.0.clone())
    }
}
#[derive(Default)]
struct Progress(Mutex<Vec<HerdrInstallStep>>);
impl HerdrInstallListener for Progress {
    fn progress(&self, step: HerdrInstallStep, _: String) {
        self.0.lock().unwrap().push(step);
    }
}

fn setup() -> (SshdHost, Arc<AriesCore>, String, tempfile::TempDir) {
    let host = SshdHost::start_without_core();
    let data = tempfile::tempdir().unwrap();
    let core = common::core_in(data.path());
    host.authorize(&core.ensure_key().unwrap().openssh);
    let saved = core
        .add_host(
            "box".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: host.port(),
                user: SshdHost::USER.into(),
            },
        )
        .unwrap();
    core.trust_host_key(saved.id.clone(), host.host_key_fingerprint())
        .unwrap();
    (host, core, saved.id, data)
}

fn archive() -> Vec<u8> {
    let dir = tempfile::tempdir().unwrap();
    std::fs::copy(herdr_core_bin(), dir.path().join("herdr-core")).unwrap();
    let out = std::process::Command::new("tar")
        .args(["-czf", "-", "-C"])
        .arg(dir.path())
        .arg("herdr-core")
        .output()
        .unwrap();
    assert!(out.status.success());
    out.stdout
}

fn pin(core: &AriesCore, bytes: &[u8]) {
    use sha2::{Digest, Sha256};
    let hash = Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    core.set_herdr_core_release(aries_core::HerdrCoreRelease {
        version: "0.1.17".into(),
        artifacts: vec![aries_core::HerdrCoreArtifact {
            os: "linux".into(),
            arch: "x86_64".into(),
            url: "http://127.0.0.1:9/core.tar.gz".into(),
            sha256: hash,
        }],
    });
}

#[tokio::test]
#[ignore = "needs docker"]
async fn installs_missing_core_with_progress_and_can_connect_afterwards() {
    let (_host, core, id, _data) = setup();
    // D02: the actual SSH channel's exit 127 must request installation, never a retry.
    assert!(matches!(
        core.connect(id.clone()).await,
        Err(aries_core::CoreError::HerdrCoreMissing)
    ));
    assert_eq!(
        core.herdr_core_status(id.clone()).await.unwrap(),
        HerdrCoreState::Missing
    );
    let bytes = archive();
    pin(&core, &bytes);
    let progress = Arc::new(Progress::default());
    core.install_herdr_core(id.clone(), Arc::new(Download(bytes)), progress.clone())
        .await
        .unwrap();
    assert_eq!(
        core.herdr_core_status(id.clone()).await.unwrap(),
        HerdrCoreState::Installed {
            version: "0.1.17".into(),
            needs_update: false
        }
    );
    assert!(
        progress
            .0
            .lock()
            .unwrap()
            .contains(&HerdrInstallStep::Verify)
    );
    assert_eq!(
        progress.0.lock().unwrap().last(),
        Some(&HerdrInstallStep::Done)
    );
    let conn = core.connect(id).await.unwrap();
    assert!(conn.terminals().await.unwrap().is_empty());
    conn.disconnect().await;
}

#[tokio::test]
#[ignore = "needs docker"]
async fn corrupt_host_download_is_refused_and_a_retry_can_install() {
    use aries_core::CoreError;
    use aries_test_support::ReleaseServer;
    let (host, core, id, _data) = setup();
    let bytes = archive();
    let server = ReleaseServer::start(vec![("core.tar.gz".into(), bytes.clone())]);
    let mut release = aries_core::pinned_herdr_core_release();
    release
        .artifacts
        .retain(|a| a.os == "linux" && a.arch == "x86_64");
    release.artifacts[0].url = server.url("core.tar.gz");
    release.artifacts[0].sha256 = "0".repeat(64);
    core.set_herdr_core_release(release.clone());
    let result = core
        .install_herdr_core(
            id.clone(),
            Arc::new(Download(vec![])),
            Arc::new(Progress::default()),
        )
        .await;
    assert!(
        matches!(result, Err(CoreError::HerdrChecksum { .. })),
        "{result:?}"
    );
    assert_eq!(
        core.herdr_core_status(id.clone()).await.unwrap(),
        HerdrCoreState::Missing
    );
    let failures = core
        .diagnostics(
            Some(aries_core::DiagnosticSource::Ssh),
            Some(id.clone()),
            true,
        )
        .unwrap();
    assert_eq!(failures[0].event, aries_core::DiagnosticEvent::HerdrInstall);
    assert_eq!(
        failures[0].error,
        Some(aries_core::DiagnosticError::HerdrChecksum)
    );
    let out = std::process::Command::new("docker")
        .args([
            "exec",
            host.network_name(),
            "sh",
            "-c",
            "find /home/aries/.local/bin -name '.aries-herdr.*'",
        ])
        .output()
        .unwrap();
    assert!(out.status.success());
    assert!(out.stdout.is_empty(), "staging was not cleaned");
    pin(&core, &bytes);
    // The retry exercises phone fallback as well as cleaning the failed download.
    core.install_herdr_core(
        id.clone(),
        Arc::new(Download(bytes)),
        Arc::new(Progress::default()),
    )
    .await
    .unwrap();
    assert_eq!(
        core.herdr_core_status(id).await.unwrap(),
        HerdrCoreState::Installed {
            version: "0.1.17".into(),
            needs_update: false
        }
    );
}

/// Produces an older binary from the pinned build without an external network
/// dependency: only the equal-length package version string changes. All SSH,
/// updater, process recovery and protocol behavior is the real herdr-core.
fn seed_old_core(host: &SshdHost) {
    let mut bytes = std::fs::read(herdr_core_bin()).unwrap();
    let mut count = 0;
    for i in 0..bytes.len() - 6 {
        if &bytes[i..i + 6] == b"0.1.17" {
            bytes[i..i + 6].copy_from_slice(b"0.1.16");
            count += 1;
        }
    }
    assert!(count > 0);
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("herdr-core");
    std::fs::write(&path, bytes).unwrap();
    docker(
        host,
        &[
            "sh",
            "-c",
            "mkdir -p /home/aries/.local/bin && chown -R aries:aries /home/aries/.local",
        ],
    );
    let out = std::process::Command::new("docker")
        .args(["cp"])
        .arg(&path)
        .arg(format!(
            "{}:/home/aries/.local/bin/herdr-core",
            host.network_name()
        ))
        .output()
        .unwrap();
    assert!(out.status.success());
    docker(
        host,
        &[
            "sh",
            "-c",
            "chmod 755 /home/aries/.local/bin/herdr-core; chown -R aries:aries /home/aries/.local",
        ],
    );
}

fn docker(host: &SshdHost, args: &[&str]) -> String {
    let out = std::process::Command::new("docker")
        .args(["exec", host.network_name()])
        .args(args)
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8(out.stdout).unwrap()
}

#[tokio::test]
#[ignore = "needs docker"]
async fn updates_an_old_user_core_while_preserving_its_terminal() {
    let (host, core, id, _data) = setup();
    seed_old_core(&host);
    assert_eq!(
        core.herdr_core_status(id.clone()).await.unwrap(),
        HerdrCoreState::Installed {
            version: "0.1.16".into(),
            needs_update: true
        }
    );
    let terminal = docker(
        &host,
        &[
            "su",
            "aries",
            "-s",
            "/bin/sh",
            "-c",
            "$HOME/.local/bin/herdr-core run --bg --json --no-parent -- sh -c 'echo before; sleep 300'",
        ],
    );
    let terminal: serde_json::Value = serde_json::from_str(&terminal).unwrap();
    let bytes = archive();
    pin(&core, &bytes);
    let rejected = core
        .install_herdr_core(
            id.clone(),
            Arc::new(Download(b"damaged".to_vec())),
            Arc::new(Progress::default()),
        )
        .await;
    assert!(matches!(
        rejected,
        Err(aries_core::CoreError::HerdrChecksum { .. })
    ));
    assert_eq!(
        core.herdr_core_status(id.clone()).await.unwrap(),
        HerdrCoreState::Installed {
            version: "0.1.16".into(),
            needs_update: true
        }
    );
    core.install_herdr_core(
        id.clone(),
        Arc::new(Download(bytes)),
        Arc::new(Progress::default()),
    )
    .await
    .unwrap();
    let conn = core.connect(id.clone()).await.unwrap();
    let terminals = conn.terminals().await.unwrap();
    assert_eq!(terminals.len(), 1);
    assert_eq!(terminals[0].address, terminal["address"].as_str().unwrap());
    assert_eq!(
        core.herdr_core_status(id).await.unwrap(),
        HerdrCoreState::Installed {
            version: "0.1.17".into(),
            needs_update: false
        }
    );
    conn.disconnect().await;
}

#[tokio::test]
#[ignore = "needs docker"]
async fn a_host_can_download_the_verified_official_archive_without_phone_upload() {
    use aries_test_support::ReleaseServer;
    let (_host, core, id, _data) = setup();
    let bytes = archive();
    let server = ReleaseServer::start(vec![("core.tar.gz".into(), bytes.clone())]);
    pin(&core, &bytes);
    use sha2::{Digest, Sha256};
    core.set_herdr_core_release(aries_core::HerdrCoreRelease {
        version: "0.1.17".into(),
        artifacts: vec![aries_core::HerdrCoreArtifact {
            os: "linux".into(),
            arch: "x86_64".into(),
            url: server.url("core.tar.gz"),
            sha256: Sha256::digest(&bytes)
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect(),
        }],
    });
    // Empty phone bytes cannot satisfy the checksum, so success proves the
    // Host download was used and independently verified on the Host.
    core.install_herdr_core(
        id.clone(),
        Arc::new(Download(vec![])),
        Arc::new(Progress::default()),
    )
    .await
    .unwrap();
    assert_eq!(
        core.herdr_core_status(id).await.unwrap(),
        HerdrCoreState::Installed {
            version: "0.1.17".into(),
            needs_update: false
        }
    );
    assert!(server.requests().iter().any(|p| p == "/core.tar.gz"));
}

#[tokio::test]
#[ignore = "needs docker"]
async fn a_foreign_install_is_left_for_its_package_manager() {
    let (host, core, id, _data) = setup();
    seed_old_core(&host);
    docker(
        &host,
        &[
            "sh",
            "-c",
            "mv /home/aries/.local/bin/herdr-core /usr/local/bin/herdr-core; chown root:root /usr/local/bin/herdr-core",
        ],
    );
    let bytes = archive();
    pin(&core, &bytes);
    let result = core
        .install_herdr_core(
            id.clone(),
            Arc::new(Download(bytes)),
            Arc::new(Progress::default()),
        )
        .await;
    assert!(
        matches!(result, Err(aries_core::CoreError::HerdrInstall { step, detail }) if step == "install" && detail.contains("package manager"))
    );
    assert_eq!(
        core.herdr_core_status(id).await.unwrap(),
        HerdrCoreState::Installed {
            version: "0.1.16".into(),
            needs_update: true
        }
    );
    docker(
        &host,
        &["test", "!", "-e", "/home/aries/.local/bin/herdr-core"],
    );
}
