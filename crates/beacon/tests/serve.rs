//! The Beacon binary as a black box: `serve` attaches to the user's local
//! herdr-core (here an isolated session) and `status` reports it.

use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::beacon::BeaconStatus;
use aries_test_support::HcSession;

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

struct Env {
    hc: HcSession,
    home: tempfile::TempDir,
}

impl Env {
    fn new() -> Self {
        Self {
            hc: HcSession::start(),
            home: tempfile::tempdir().unwrap(),
        }
    }

    fn command(&self, arg: &str) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.arg(arg).env_clear();
        cmd.envs(self.hc.env());
        let bin_dir = self.hc.bin().parent().unwrap();
        cmd.env("PATH", format!("{}:/usr/bin:/bin", bin_dir.display()));
        cmd.env("HOME", self.home.path());
        cmd.stdin(Stdio::null());
        cmd
    }

    fn status(&self) -> BeaconStatus {
        let out = self.command("status").output().unwrap();
        assert!(out.status.success(), "status failed: {out:?}");
        let text = String::from_utf8(out.stdout).unwrap();
        assert_eq!(text.lines().count(), 1, "one JSON line: {text:?}");
        serde_json::from_str(text.trim()).unwrap()
    }

    fn wait_for(&self, what: &str, ok: impl Fn(&BeaconStatus) -> bool) -> BeaconStatus {
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            let s = self.status();
            if ok(&s) {
                return s;
            }
            assert!(
                Instant::now() < deadline,
                "timed out waiting for {what}: {s:?}"
            );
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

struct Killed(Child);
impl Drop for Killed {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
fn status_reports_the_version_before_the_service_runs() {
    let env = Env::new();
    let s = env.status();
    assert_eq!(s.version, env!("CARGO_PKG_VERSION"));
    assert_eq!(s.contract, aries_protocol::CONTRACT_VERSION);
    assert!(!s.running);
    assert_eq!(s.core_version, None);
}

#[test]
fn serve_attaches_to_the_local_herdr_core() {
    let env = Env::new();
    let mut serve = Killed(
        env.command("serve")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap(),
    );
    let s = env.wait_for("attach", |s| s.core_version.is_some());
    assert!(s.running);
    assert_eq!(s.core_version.as_deref(), Some("0.1.17"));

    serve.0.kill().unwrap();
    serve.0.wait().unwrap();
    let s = env.status();
    assert!(!s.running);
    assert_eq!(s.core_version, None);
}
