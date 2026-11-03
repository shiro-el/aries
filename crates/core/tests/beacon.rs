//! Installing, updating and removing the Beacon over SSH, against a docker
//! Host running systemd (so the user service is real). The Host downloads
//! from a [`ReleaseServer`] standing in for GitHub Releases; macOS is played
//! by the same Host with `uname`, `launchctl` and `shasum` faked.
//!
//! Needs docker. Run with `cargo test -p aries-core --test beacon -- --ignored`.

mod common;

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use aries_core::{
    AriesCore, BeaconArtifact, BeaconFetcher, BeaconRelease, BeaconSource, BeaconState,
    BeaconUpdate, CoreError, Endpoint, FetchError, Linger,
};
use aries_test_support::{ReleaseServer, SystemdHost, beacon_bin};
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
    release_at(version, "linux", "x86_64", URL, sha256)
}

fn release_at(version: &str, os: &str, arch: &str, url: &str, sha256: String) -> BeaconRelease {
    BeaconRelease {
        version: version.into(),
        artifacts: vec![BeaconArtifact {
            os: os.into(),
            arch: arch.into(),
            url: url.into(),
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
    // example.invalid does not resolve on the Host: the phone downloads.
    assert_eq!(fetcher.urls.lock().unwrap().as_slice(), [URL]);
    assert_eq!(installed.source, BeaconSource::PhoneUpload);
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
            os,
            ..
        } => {
            assert_eq!(v, version);
            assert_eq!(os, "linux");
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

const CLAUDE_SKILL: &str = "/home/aries/.claude/skills/call-human";
const AGENTS_SKILL: &str = "/home/aries/.agents/skills/call-human";

/// Runs `script` as the Host user (files it makes belong to them).
fn as_user(host: &SystemdHost, script: &str) {
    host.root(&format!("su aries -s /bin/sh -c '{script}'"));
}

#[tokio::test]
#[ignore = "needs docker"]
async fn installs_the_call_human_skill_and_removal_keeps_the_users_version() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    s.core
        .set_beacon_release(release(env!("CARGO_PKG_VERSION"), sha256sum(&bytes)));
    let fetcher = Fetcher::new(bytes);
    s.core
        .install_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    for dir in [CLAUDE_SKILL, AGENTS_SKILL] {
        assert!(
            s.host
                .check(&format!("grep -q 'aries-beacon call' {dir}/SKILL.md")),
            "{dir}"
        );
    }

    // The user edits Claude's copy and adds a file to the other one; the
    // next install (an update) keeps both.
    as_user(
        &s.host,
        &format!("echo mine > {CLAUDE_SKILL}/SKILL.md && echo notes > {AGENTS_SKILL}/NOTES.md"),
    );
    s.core
        .install_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    assert!(
        s.host
            .check(&format!("test \"$(cat {CLAUDE_SKILL}/SKILL.md)\" = mine"))
    );
    assert!(s.host.check(&format!(
        "grep -q 'aries-beacon call' {AGENTS_SKILL}/SKILL.md && test -f {AGENTS_SKILL}/NOTES.md"
    )));

    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert!(
        s.host
            .check(&format!("test \"$(cat {CLAUDE_SKILL}/SKILL.md)\" = mine"))
    );
    assert!(s.host.check(&format!(
        "test ! -e {AGENTS_SKILL}/SKILL.md && test -f {AGENTS_SKILL}/NOTES.md"
    )));
    assert!(!s.host.check(&format!("test -e {BIN}")));
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
    assert!(!s.host.check(&format!(
        "test -e {BIN} || test -e /home/aries/.local/bin/.aries-beacon.new"
    )));
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

const NAME: &str = "aries-beacon-linux-x86_64";

#[tokio::test]
#[ignore = "needs docker"]
async fn the_host_downloads_the_beacon_itself_when_it_can_reach_the_release() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    let server = ReleaseServer::start(vec![(NAME.into(), bytes.clone())]);
    let version = env!("CARGO_PKG_VERSION");
    s.core.set_beacon_release(release_at(
        version,
        "linux",
        "x86_64",
        &server.url(NAME),
        sha256sum(&bytes),
    ));
    let fetcher = Fetcher::new(Vec::new());

    let installed = s
        .core
        .install_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    assert_eq!(installed.source, BeaconSource::HostDownload);
    assert_eq!(server.requests(), [format!("/{NAME}")]);
    assert!(
        fetcher.urls.lock().unwrap().is_empty(),
        "the phone downloaded too"
    );
    assert!(s.host.check(&format!("test -x {BIN} && test -f {UNIT}")));
    match wait_attached(&s.core, &s.id).await {
        BeaconState::Installed {
            version: v,
            running,
            ..
        } => {
            assert_eq!(v, version);
            assert!(running);
        }
        other => panic!("{other:?}"),
    }
}

#[tokio::test]
#[ignore = "needs docker"]
async fn refuses_a_host_download_that_does_not_match_the_pinned_checksum() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    let mut tampered = bytes.clone();
    tampered.extend_from_slice(b"evil");
    let server = ReleaseServer::start(vec![(NAME.into(), tampered.clone())]);
    s.core.set_beacon_release(release_at(
        env!("CARGO_PKG_VERSION"),
        "linux",
        "x86_64",
        &server.url(NAME),
        sha256sum(&bytes),
    ));
    // The phone would hand out the genuine binary: it must not be asked.
    let fetcher = Fetcher::new(bytes);

    match s.core.install_beacon(s.id.clone(), fetcher.clone()).await {
        Err(CoreError::BeaconChecksum { expected, actual }) => {
            assert_eq!(expected, sha256sum(&fetcher.bytes));
            assert_eq!(actual, sha256sum(&tampered));
        }
        other => panic!("expected BeaconChecksum, got {other:?}"),
    }
    assert_eq!(server.requests().len(), 1);
    assert!(fetcher.urls.lock().unwrap().is_empty());
    assert!(s.host.check(
        "test ! -e /home/aries/.local/bin/aries-beacon && test ! -e /home/aries/.local/bin/.aries-beacon.new"
    ));
    assert!(!s.host.check(&format!("test -e {UNIT}")));
}

/// A stand-in Beacon of another release; `serve` just stays up.
fn fake_beacon(version: &str) -> Vec<u8> {
    format!(
        r#"#!/bin/sh
case "$1" in
  --version) echo "aries-beacon {version} (contract 1)" ;;
  status) echo '{{"version":"{version}","contract":1,"running":true,"core_version":"0.1.17","linger":false}}' ;;
  serve) exec sleep 1000000 ;;
esac
"#
    )
    .into_bytes()
}

/// A Host with an old Beacon installed, and the app pinned to the current
/// one, served by a release server.
async fn with_old_beacon() -> (Setup, ReleaseServer, Arc<Fetcher>) {
    let s = setup();
    let old = fake_beacon("0.0.9");
    s.core.set_beacon_release(release("0.0.9", sha256sum(&old)));
    s.core
        .install_beacon(s.id.clone(), Fetcher::new(old))
        .await
        .unwrap();

    let bytes = std::fs::read(beacon_bin()).unwrap();
    let server = ReleaseServer::start(vec![(NAME.into(), bytes.clone())]);
    s.core.set_beacon_release(release_at(
        env!("CARGO_PKG_VERSION"),
        "linux",
        "x86_64",
        &server.url(NAME),
        sha256sum(&bytes),
    ));
    (s, server, Fetcher::new(bytes))
}

#[tokio::test]
#[ignore = "needs docker"]
async fn updates_an_old_beacon_when_connecting() {
    let (s, server, fetcher) = with_old_beacon().await;
    assert!(s.core.beacon_auto_update());

    let check = s
        .core
        .check_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    match &check.update {
        BeaconUpdate::Updated { from, install } => {
            assert_eq!(from, "0.0.9");
            assert_eq!(install.version, env!("CARGO_PKG_VERSION"));
        }
        other => panic!("expected an update, got {other:?}"),
    }
    assert!(matches!(
        &check.state,
        BeaconState::Installed { version, supported: true, .. } if version == env!("CARGO_PKG_VERSION")
    ));
    assert_eq!(server.requests().len(), 1);
    // The new Beacon brought its OpenCode plugin.
    assert!(s.host.check(&format!("test -f {PLUGIN}")));
    // The old release had no skill; the update brings it.
    assert!(s.host.check(&format!(
        "test -f {CLAUDE_SKILL}/SKILL.md && test -f {AGENTS_SKILL}/SKILL.md"
    )));

    // Connecting again finds it current and leaves it alone.
    let again = s.core.check_beacon(s.id.clone(), fetcher).await.unwrap();
    assert_eq!(again.update, BeaconUpdate::NotNeeded);
    assert_eq!(server.requests().len(), 1);
}

#[tokio::test]
#[ignore = "needs docker"]
async fn with_auto_update_off_only_reports_that_an_update_is_needed() {
    let (s, server, fetcher) = with_old_beacon().await;
    s.core.set_beacon_auto_update(false).unwrap();

    let check = s
        .core
        .check_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    assert_eq!(check.update, BeaconUpdate::Needed);
    assert!(matches!(
        &check.state,
        BeaconState::Installed { version, supported: false, .. } if version == "0.0.9"
    ));
    assert!(server.requests().is_empty());
    assert!(fetcher.urls.lock().unwrap().is_empty());
    assert!(s.host.check(&format!("{BIN} --version | grep -q 0.0.9")));
}

/// Makes the Host look like a Mac to the installer: `uname` says Darwin
/// arm64, there is no `sha256sum` but a `shasum`, and `launchctl` records
/// its calls in /tmp/launchctl.log and runs the agent's program on
/// `bootstrap`. Like launchd over SSH without a desktop login, the `gui`
/// domain is refused and the `user` domain works.
fn pretend_macos(host: &SystemdHost) {
    host.root(
        r#"set -e
mv /usr/bin/sha256sum /usr/bin/gsha256sum
cat > /usr/local/bin/uname <<'EOF'
#!/bin/sh
case "$1" in -s) echo Darwin ;; -m) echo arm64 ;; *) exec /usr/bin/uname "$@" ;; esac
EOF
cat > /usr/local/bin/shasum <<'EOF'
#!/bin/sh
[ "$1" = -a ] && [ "$2" = 256 ] || { echo "fake shasum: $*" >&2; exit 2; }
exec gsha256sum "$3"
EOF
cat > /usr/local/bin/launchctl <<'EOF'
#!/bin/sh
echo "$*" >> /tmp/launchctl.log
pidfile=/tmp/launchctl-$(id -u).pid
case "$1 $2" in
  "bootstrap gui/"*) echo "Bootstrap failed: 125: Domain does not support specified action" >&2; exit 125 ;;
  "bootstrap user/"*)
    grep -q '<key>KeepAlive</key>' "$3" || exit 3
    prog=$(sed -n 's|.*<string>\(/.*aries-beacon\)</string>.*|\1|p' "$3")
    setsid nohup "$prog" serve >/dev/null 2>&1 < /dev/null &
    echo $! > "$pidfile" ;;
  "bootout user/"*)
    [ -f "$pidfile" ] || exit 3
    kill "$(cat "$pidfile")"; rm -f "$pidfile" ;;
  "bootout "*) exit 3 ;;
esac
EOF
chmod 755 /usr/local/bin/uname /usr/local/bin/shasum /usr/local/bin/launchctl
touch /tmp/launchctl.log && chmod 666 /tmp/launchctl.log"#,
    );
}

const PLIST: &str = "/home/aries/Library/LaunchAgents/io.github.shiroel.aries.beacon.plist";

#[tokio::test]
#[ignore = "needs docker"]
async fn registers_a_launch_agent_on_macos_and_explains_the_login_limit() {
    let s = setup();
    pretend_macos(&s.host);
    let uid = s.host.root("id -u aries").trim().to_owned();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    let name = "aries-beacon-macos-aarch64";
    let server = ReleaseServer::start(vec![(name.into(), bytes.clone())]);
    s.core.set_beacon_release(release_at(
        env!("CARGO_PKG_VERSION"),
        "macos",
        "aarch64",
        &server.url(name),
        sha256sum(&bytes),
    ));

    let installed = s
        .core
        .install_beacon(s.id.clone(), Fetcher::new(Vec::new()))
        .await
        .unwrap();
    assert_eq!(installed.source, BeaconSource::HostDownload);
    assert_eq!(installed.linger, Linger::LoginSession);
    assert_eq!(server.requests(), [format!("/{name}")]);

    let plist = s.host.root(&format!("cat {PLIST}"));
    assert!(
        plist.contains("<string>io.github.shiroel.aries.beacon</string>"),
        "{plist}"
    );
    assert!(
        plist.contains(&format!("<string>{BIN}</string>")),
        "{plist}"
    );
    assert!(plist.contains("<key>RunAtLoad</key>"), "{plist}");
    assert!(
        !s.host.check(&format!("test -e {UNIT}")),
        "systemd unit on a Mac"
    );
    let log = s.host.root("cat /tmp/launchctl.log");
    assert!(
        log.contains(&format!("bootstrap user/{uid} {PLIST}")),
        "{log}"
    );

    match wait_attached(&s.core, &s.id).await {
        BeaconState::Installed { os, running, .. } => {
            assert_eq!(os, "macos");
            assert!(running);
        }
        other => panic!("{other:?}"),
    }

    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert!(
        s.host
            .check(&format!("test ! -e {PLIST} && test ! -e {BIN}"))
    );
    let log = s.host.root("cat /tmp/launchctl.log");
    assert!(
        log.contains(&format!(
            "bootout user/{uid}/io.github.shiroel.aries.beacon"
        )),
        "{log}"
    );
    assert!(!s.host.check("grep -qs aries-beaco[n] /proc/[0-9]*/cmdline"));
}

#[test]
fn the_auto_update_setting_is_remembered() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    assert!(core.beacon_auto_update(), "on by default");
    core.set_beacon_auto_update(false).unwrap();
    drop(core);
    assert!(!core_in(data.path()).beacon_auto_update());
}

/// The release the app was built with (`ARIES_BEACON_MANIFEST`), from the
/// binaries `scripts/build-beacon.sh` made (`ARIES_BEACON_DIST`): only the
/// URL is swapped for the fake release server. Skipped in builds without a
/// pinned release.
#[tokio::test]
#[ignore = "needs docker"]
async fn installs_the_pinned_release_built_by_the_pipeline() {
    let pinned = aries_core::pinned_release();
    let (Some(artifact), Some(dist)) = (
        pinned
            .artifacts
            .iter()
            .find(|a| a.os == "linux" && a.arch == "x86_64"),
        std::env::var_os("ARIES_BEACON_DIST"),
    ) else {
        eprintln!("skipped: no pinned linux-x86_64 Beacon or ARIES_BEACON_DIST");
        return;
    };
    let bytes = std::fs::read(std::path::Path::new(&dist).join(NAME)).unwrap();
    let server = ReleaseServer::start(vec![(NAME.into(), bytes)]);
    let s = setup();
    s.core.set_beacon_release(release_at(
        &pinned.version,
        "linux",
        "x86_64",
        &server.url(NAME),
        artifact.sha256.clone(),
    ));

    let installed = s
        .core
        .install_beacon(s.id.clone(), Fetcher::new(Vec::new()))
        .await
        .unwrap();
    assert_eq!(installed.source, BeaconSource::HostDownload);
    match wait_attached(&s.core, &s.id).await {
        BeaconState::Installed {
            version, supported, ..
        } => {
            assert_eq!(version, pinned.version);
            assert!(supported);
        }
        other => panic!("{other:?}"),
    }
}

const PLUGINS: &str = "/home/aries/.config/opencode/plugins";
const PLUGIN: &str = "/home/aries/.config/opencode/plugins/aries-agent-state.js";

/// Ticket 42: the Beacon keeps the OpenCode status plugin in the user's
/// OpenCode configuration, never overwrites or removes a copy the user
/// edited, and leaves the user's other plugins alone.
#[tokio::test]
#[ignore = "needs docker"]
async fn manages_the_opencode_plugin_and_keeps_the_users_edits() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    s.core
        .set_beacon_release(release(env!("CARGO_PKG_VERSION"), sha256sum(&bytes)));
    let fetcher = Fetcher::new(bytes);
    let install = || async {
        s.core
            .install_beacon(s.id.clone(), fetcher.clone())
            .await
            .unwrap();
    };
    // The user's own plugin, there before Aries.
    s.host.root(&format!(
        "mkdir -p {PLUGINS} && echo 'export const Mine = async () => ({{}});' > {PLUGINS}/mine.js \
         && chown -R aries:aries /home/aries/.config"
    ));

    install().await;
    assert!(s.host.check(&format!(
        "head -n 1 {PLUGIN} | grep -q '^// aries-managed: aries-agent-state ' \
         && grep -q 'aries.opencode.session' {PLUGIN} && grep -q '7501' {PLUGIN}"
    )));
    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert!(s.host.check(&format!("test ! -e {PLUGIN}")));
    assert!(s.host.check(&format!("test -f {PLUGINS}/mine.js")));

    // Installed again, then edited by the user: an update keeps the edit,
    // and so does removal.
    install().await;
    s.host.root(&format!("echo '// mine now' >> {PLUGIN}"));
    let edited = s.host.root(&format!("sha256sum {PLUGIN}"));
    install().await;
    assert_eq!(s.host.root(&format!("sha256sum {PLUGIN}")), edited);
    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert_eq!(s.host.root(&format!("sha256sum {PLUGIN}")), edited);
    assert!(s.host.check(&format!("test -f {PLUGINS}/mine.js")));
}

/// With nothing else of the user's there, removal leaves no empty OpenCode
/// directories behind.
#[tokio::test]
#[ignore = "needs docker"]
async fn removing_the_beacon_leaves_no_empty_opencode_directories() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    s.core
        .set_beacon_release(release(env!("CARGO_PKG_VERSION"), sha256sum(&bytes)));
    s.core
        .install_beacon(s.id.clone(), Fetcher::new(bytes))
        .await
        .unwrap();
    assert!(s.host.check(&format!("test -f {PLUGIN}")));
    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert!(s.host.check("test ! -e /home/aries/.config/opencode"));
}

const PI_EXT: &str = "/home/aries/.pi/agent/extensions/aries-status.ts";

/// Runs `script` as the Host user (the user's own files, `HOME` set).
fn user_sh(host: &SystemdHost, script: &str) -> String {
    host.root(&format!(
        "runuser -u aries -- env HOME=/home/aries sh -c {}",
        shell_quote(script)
    ))
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

#[tokio::test]
#[ignore = "needs docker"]
async fn manages_the_pi_extension_with_the_beacon() {
    let s = setup();
    let bytes = std::fs::read(beacon_bin()).unwrap();
    let version = env!("CARGO_PKG_VERSION");
    s.core
        .set_beacon_release(release(version, sha256sum(&bytes)));
    let fetcher = Fetcher::new(bytes);

    // Installing the Beacon puts the extension in Pi's global dir.
    s.core
        .install_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    let first = user_sh(&s.host, &format!("head -n 1 {PI_EXT}"));
    assert!(
        first.starts_with("// @aries-managed-pi-extension sha256="),
        "{first}"
    );
    assert!(s.host.check(&format!(
        "grep -q aries.pi.file= {PI_EXT} && [ \"$(stat -c %U {PI_EXT})\" = aries ]"
    )));

    // An older managed copy is replaced when the Beacon is updated.
    let old_body = "// old Aries extension\nexport default () => {};\n";
    let old = format!(
        "// @aries-managed-pi-extension sha256={}\n{old_body}",
        sha256sum(old_body.as_bytes())
    );
    user_sh(
        &s.host,
        &format!("printf '%s' {} > {PI_EXT}", shell_quote(&old)),
    );
    s.core
        .install_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    assert!(
        !s.host
            .check(&format!("grep -q 'old Aries extension' {PI_EXT}"))
    );
    assert!(s.host.check(&format!("grep -q aries.pi.file= {PI_EXT}")));

    // The user edits it: updating keeps the edit, and so does removal.
    user_sh(&s.host, &format!("echo '// my tweak' >> {PI_EXT}"));
    s.core
        .install_beacon(s.id.clone(), fetcher.clone())
        .await
        .unwrap();
    assert!(s.host.check(&format!("grep -q 'my tweak' {PI_EXT}")));
    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert!(s.host.check(&format!("grep -q 'my tweak' {PI_EXT}")));

    // Untouched, it goes with the Beacon, along with the dirs it made.
    user_sh(&s.host, "rm -rf /home/aries/.pi");
    s.core.install_beacon(s.id.clone(), fetcher).await.unwrap();
    assert!(s.host.check(&format!("test -f {PI_EXT}")));
    s.core.uninstall_beacon(s.id.clone()).await.unwrap();
    assert!(s.host.check("test ! -e /home/aries/.pi"));
}
