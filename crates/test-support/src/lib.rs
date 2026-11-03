//! Test harness shared by every crate's integration tests.
//!
//! - [`HcSession`]: a real, pinned herdr-core in a throwaway session whose
//!   socket, state and config all live under a temp dir. It never touches the
//!   developer's own herdr-core session.
//! - [`SshdHost`]: a docker container running a real OpenSSH sshd with the
//!   pinned herdr-core installed, for the few tests that exercise SSH itself.

use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

/// The herdr-core version every test runs against.
pub const HERDR_CORE_VERSION: &str = "0.1.17";

/// Environment variables a test process may inherit from a surrounding
/// herdr-core terminal; passing them on would point the child at the
/// developer's real session.
const LEAKY_ENV: &[&str] = &[
    "HC_SESSION",
    "HC_SOCKET",
    "HC_SCOPE",
    "HC_TERMINAL_ID",
    "HC_TERMINAL_SOCKET",
];

static COUNTER: AtomicUsize = AtomicUsize::new(0);

fn unique(prefix: &str) -> String {
    format!(
        "{prefix}-{}-{}",
        std::process::id(),
        COUNTER.fetch_add(1, Ordering::Relaxed)
    )
}

/// The repository root (where `Cargo.toml` of the workspace lives).
pub fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .expect("repo root")
}

/// Path of the pinned herdr-core binary.
///
/// `ARIES_HERDR_CORE` wins; otherwise the binary fetched by
/// `scripts/fetch-herdr-core.sh` into `target/herdr-core-<version>/`.
/// Panics unless the binary reports exactly [`HERDR_CORE_VERSION`].
pub fn herdr_core_bin() -> PathBuf {
    let path = std::env::var_os("ARIES_HERDR_CORE")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            repo_root()
                .join("target")
                .join(format!("herdr-core-{HERDR_CORE_VERSION}"))
                .join("herdr-core")
        });
    assert!(
        path.is_file(),
        "pinned herdr-core not found at {}; run scripts/fetch-herdr-core.sh or set ARIES_HERDR_CORE",
        path.display()
    );
    let out = Command::new(&path)
        .arg("--version")
        .output()
        .expect("run herdr-core --version");
    let version = String::from_utf8_lossy(&out.stdout);
    assert_eq!(
        version.trim(),
        format!("hc {HERDR_CORE_VERSION}"),
        "{} is not the pinned herdr-core",
        path.display()
    );
    path
}

/// A real herdr-core in an isolated session. Stopped and deleted on drop.
pub struct HcSession {
    dir: tempfile::TempDir,
    name: String,
    bin: PathBuf,
}

impl HcSession {
    /// Starts a fresh core in its own session and temp dirs.
    pub fn start() -> Self {
        // Short base path: the socket path must stay under the 108-byte limit.
        let dir = tempfile::Builder::new()
            .prefix("aries-hc-")
            .tempdir_in("/tmp")
            .expect("temp dir");
        let run = dir.path().join("run");
        std::fs::create_dir_all(&run).expect("run dir");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&run, std::fs::Permissions::from_mode(0o700))
                .expect("chmod run dir");
        }
        let session = Self {
            dir,
            name: unique("aries-test"),
            bin: herdr_core_bin(),
        };
        session.hc(&["server", "start"]);
        session
    }

    /// Session name (`HC_SESSION`).
    pub fn name(&self) -> &str {
        &self.name
    }

    /// Path of the pinned herdr-core binary this session runs.
    pub fn bin(&self) -> &Path {
        &self.bin
    }

    /// Environment that points a herdr-core process at this session.
    pub fn env(&self) -> Vec<(String, String)> {
        let d = self.dir.path();
        let p = |s: &str| d.join(s).to_string_lossy().into_owned();
        vec![
            ("HC_SESSION".into(), self.name.clone()),
            ("XDG_RUNTIME_DIR".into(), p("run")),
            ("XDG_STATE_HOME".into(), p("state")),
            ("XDG_CONFIG_HOME".into(), p("config")),
            ("XDG_DATA_HOME".into(), p("data")),
            ("XDG_CACHE_HOME".into(), p("cache")),
        ]
    }

    /// The argv that speaks the client protocol on stdio for this session
    /// (what a Local endpoint runs).
    pub fn connect_argv(&self) -> Vec<String> {
        vec![self.bin.to_string_lossy().into_owned(), "connect".into()]
    }

    /// A `herdr-core` command bound to this session.
    pub fn command(&self) -> Command {
        let mut cmd = Command::new(&self.bin);
        for k in LEAKY_ENV {
            cmd.env_remove(k);
        }
        cmd.envs(self.env());
        cmd.current_dir(self.dir.path());
        cmd
    }

    /// Runs `hc <args>` in this session; panics on failure, returns stdout.
    pub fn hc(&self, args: &[&str]) -> String {
        let out = self
            .command()
            .args(args)
            .stdin(Stdio::null())
            .output()
            .expect("run herdr-core");
        assert_ok(&out, &format!("hc {}", args.join(" ")));
        String::from_utf8_lossy(&out.stdout).into_owned()
    }

    /// Starts `argv` in a new background Terminal; returns its address (`t1`).
    pub fn run_bg(&self, name: &str, argv: &[&str]) -> String {
        let mut args = vec!["run", "--bg", "--json", "--no-parent", "--name", name, "--"];
        args.extend_from_slice(argv);
        let out = self.hc(&args);
        let v: serde_json::Value = serde_json::from_str(out.trim()).expect("run json");
        v["address"].as_str().expect("address").to_owned()
    }
}

impl Drop for HcSession {
    fn drop(&mut self) {
        let _ = self
            .command()
            .args(["server", "stop"])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

fn assert_ok(out: &Output, what: &str) {
    assert!(
        out.status.success(),
        "{what} failed: {}\nstdout: {}\nstderr: {}",
        out.status,
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
}

/// Image tag built from `fixtures/sshd/Dockerfile`.
pub const SSHD_IMAGE: &str = "aries-test-sshd:1";

/// A docker container running a real sshd with the pinned herdr-core.
///
/// Users: [`SshdHost::USER`] (plain `/bin/sh`) and [`SshdHost::NOISY_USER`]
/// (a login shell that prints a banner on stdout before running the command).
/// Removed on drop. Container names start with `ARIES_TEST_CONTAINER_PREFIX`
/// (default `aries-test`).
pub struct SshdHost {
    container: String,
    port: u16,
}

impl SshdHost {
    pub const USER: &'static str = "aries";
    pub const NOISY_USER: &'static str = "noisy";

    /// Builds the image if needed and starts a container.
    pub fn start() -> Self {
        let bin = herdr_core_bin();
        let fixture = repo_root().join("fixtures/sshd");
        docker(&["build", "-q", "-t", SSHD_IMAGE, &fixture.to_string_lossy()]);
        let prefix =
            std::env::var("ARIES_TEST_CONTAINER_PREFIX").unwrap_or_else(|_| "aries-test".into());
        let container = unique(&format!("{prefix}-sshd"));
        let mount = format!("{}:/usr/local/bin/herdr-core:ro", bin.display());
        docker(&[
            "run", "-d", "--rm", "--name", &container, "-p", "127.0.0.1::22", "-v", &mount,
            SSHD_IMAGE,
        ]);
        let mut host = Self { container, port: 0 };
        let mapped = docker(&["port", &host.container, "22/tcp"]);
        host.port = mapped
            .lines()
            .next()
            .and_then(|l| l.rsplit(':').next())
            .and_then(|p| p.trim().parse().ok())
            .expect("mapped port");
        host.wait_ready();
        host
    }

    /// Host port on 127.0.0.1 that reaches the container's sshd.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Adds an OpenSSH public key line to both users' `authorized_keys`.
    pub fn authorize(&self, public_key: &str) {
        for user in [Self::USER, Self::NOISY_USER] {
            let script = format!(
                "printf '%s\\n' \"$KEY\" >> /home/{user}/.ssh/authorized_keys && chown {user} /home/{user}/.ssh/authorized_keys && chmod 600 /home/{user}/.ssh/authorized_keys"
            );
            docker(&[
                "exec", "-e", &format!("KEY={public_key}"), &self.container, "sh", "-c", &script,
            ]);
        }
    }

    /// SHA256 fingerprint (`SHA256:...`) of the container's ed25519 host key.
    pub fn host_key_fingerprint(&self) -> String {
        let out = docker(&[
            "exec", &self.container, "ssh-keygen", "-l", "-f", "/etc/ssh/ssh_host_ed25519_key.pub",
        ]);
        out.split_whitespace().nth(1).expect("fingerprint").to_owned()
    }

    /// Replaces the host keys and restarts sshd, as a reinstalled or
    /// impersonated Host would look.
    pub fn rotate_host_keys(&self) {
        docker(&[
            "exec", &self.container, "sh", "-c",
            "rm -f /etc/ssh/ssh_host_* && ssh-keygen -A >/dev/null && pkill -x sshd",
        ]);
        std::thread::sleep(Duration::from_millis(300));
        self.wait_ready();
    }

    fn wait_ready(&self) {
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            if let Ok(mut s) = std::net::TcpStream::connect(("127.0.0.1", self.port)) {
                use std::io::Read;
                s.set_read_timeout(Some(Duration::from_secs(2))).ok();
                let mut buf = [0u8; 4];
                if s.read_exact(&mut buf).is_ok() && &buf == b"SSH-" {
                    return;
                }
            }
            assert!(Instant::now() < deadline, "sshd did not come up");
            std::thread::sleep(Duration::from_millis(200));
        }
    }
}

impl Drop for SshdHost {
    fn drop(&mut self) {
        let _ = Command::new("docker")
            .args(["rm", "-f", &self.container])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

fn docker(args: &[&str]) -> String {
    let out = Command::new("docker")
        .args(args)
        .stdin(Stdio::null())
        .output()
        .expect("run docker");
    assert_ok(&out, &format!("docker {}", args.join(" ")));
    String::from_utf8_lossy(&out.stdout).into_owned()
}
