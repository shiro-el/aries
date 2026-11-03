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

    /// One plain-mode JSON-RPC call through `herdr-core connect` (for API-only
    /// methods such as `doc.set`). Returns `result`; panics on an error reply.
    pub fn rpc(&self, method: &str, params: serde_json::Value) -> serde_json::Value {
        use std::io::{BufRead, BufReader, Write};
        let mut child = self
            .command()
            .arg("connect")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .expect("run herdr-core connect");
        let mut stdin = child.stdin.take().expect("stdin");
        let hello =
            serde_json::json!({"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":1}});
        let call = serde_json::json!({"jsonrpc":"2.0","id":1,"method":method,"params":params});
        writeln!(stdin, "{hello}\n{call}").expect("write rpc");
        let mut reply = None;
        for line in BufReader::new(child.stdout.take().expect("stdout")).lines() {
            let Ok(line) = line else { break };
            let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else {
                continue;
            };
            if v["id"] == 1 {
                reply = Some(v);
                break;
            }
        }
        drop(stdin);
        let _ = child.kill();
        let _ = child.wait();
        let reply = reply.unwrap_or_else(|| panic!("no reply to {method}"));
        assert!(reply.get("error").is_none(), "{method} failed: {reply}");
        reply["result"].clone()
    }

    /// Writes document `name` (any JSON) over whatever version is there.
    pub fn set_doc(&self, name: &str, value: serde_json::Value) {
        let version = self.rpc("doc.get", serde_json::json!({ "name": name }))["version"].clone();
        self.rpc(
            "doc.set",
            serde_json::json!({ "name": name, "value": value, "version": version }),
        );
    }

    /// Starts `argv` in a new background Terminal with `labels`; returns its address.
    pub fn run_labeled(&self, name: &str, labels: &[(&str, &str)], argv: &[&str]) -> String {
        let mut args: Vec<String> = ["run", "--bg", "--json", "--no-parent", "--name", name]
            .iter()
            .map(|s| s.to_string())
            .collect();
        for (k, v) in labels {
            args.push("--label".into());
            args.push(format!("{k}={v}"));
        }
        args.push("--".into());
        args.extend(argv.iter().map(|s| s.to_string()));
        let refs: Vec<&str> = args.iter().map(String::as_str).collect();
        let out = self.hc(&refs);
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

/// Image built from `fixtures/sshd/Dockerfile`, tagged by a hash of the
/// Dockerfile so branches with different fixtures do not overwrite each
/// other's image.
pub fn sshd_image() -> String {
    use std::hash::{Hash, Hasher};
    let dockerfile =
        std::fs::read(repo_root().join("fixtures/sshd/Dockerfile")).expect("read sshd Dockerfile");
    let mut h = std::collections::hash_map::DefaultHasher::new();
    dockerfile.hash(&mut h);
    format!("aries-test-sshd:{:016x}", h.finish())
}

/// A docker container running a real sshd with the pinned herdr-core.
///
/// Users: [`SshdHost::USER`] (plain `/bin/sh`) and [`SshdHost::NOISY_USER`]
/// (a login shell that prints a banner on stdout before running the command)
/// accept keys only. [`SshdHost::PASSWORD_USER`] (no `~/.ssh` yet) and
/// [`SshdHost::LOCKED_USER`] (a root-owned `~/.ssh`) also accept
/// [`SshdHost::PASSWORD`]. Removed on drop. Container names start with `ARIES_TEST_CONTAINER_PREFIX`
/// (default `aries-test`).
pub struct SshdHost {
    container: String,
    port: u16,
}

impl SshdHost {
    pub const USER: &'static str = "aries";
    pub const NOISY_USER: &'static str = "noisy";
    pub const PASSWORD_USER: &'static str = "bootstrap";
    pub const LOCKED_USER: &'static str = "locked";
    pub const PASSWORD: &'static str = "aries-test-password";

    /// Builds the image if needed and starts a container.
    pub fn start() -> Self {
        let bin = herdr_core_bin();
        let fixture = repo_root().join("fixtures/sshd");
        let image = sshd_image();
        docker(&["build", "-q", "-t", &image, &fixture.to_string_lossy()]);
        let prefix =
            std::env::var("ARIES_TEST_CONTAINER_PREFIX").unwrap_or_else(|_| "aries-test".into());
        let container = unique(&format!("{prefix}-sshd"));
        let mount = format!("{}:/usr/local/bin/herdr-core:ro", bin.display());
        docker(&[
            "run",
            "-d",
            "--rm",
            "--name",
            &container,
            "-p",
            "127.0.0.1::22",
            "-v",
            &mount,
            &image,
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
                "exec",
                "-e",
                &format!("KEY={public_key}"),
                &self.container,
                "sh",
                "-c",
                &script,
            ]);
        }
    }

    /// Contents of `user`'s `~/.ssh/authorized_keys` (empty if missing).
    pub fn authorized_keys(&self, user: &str) -> String {
        docker(&[
            "exec",
            &self.container,
            "sh",
            "-c",
            &format!("cat /home/{user}/.ssh/authorized_keys 2>/dev/null || true"),
        ])
    }

    /// SHA256 fingerprint (`SHA256:...`) of the container's ed25519 host key.
    pub fn host_key_fingerprint(&self) -> String {
        let out = docker(&[
            "exec",
            &self.container,
            "ssh-keygen",
            "-l",
            "-f",
            "/etc/ssh/ssh_host_ed25519_key.pub",
        ]);
        out.split_whitespace()
            .nth(1)
            .expect("fingerprint")
            .to_owned()
    }

    /// Replaces the host keys and restarts sshd, as a reinstalled or
    /// impersonated Host would look.
    pub fn rotate_host_keys(&self) {
        docker(&[
            "exec",
            &self.container,
            "sh",
            "-c",
            "rm -f /etc/ssh/ssh_host_* && ssh-keygen -A >/dev/null && kill $(cat /run/sshd.pid)",
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

/// Path of the fake Agent (`fixtures/agents/fake/fake-agent`, POSIX sh).
pub fn fake_agent_path() -> PathBuf {
    repo_root().join("fixtures/agents/fake/fake-agent")
}

/// A fake Agent running in a Terminal: it reports OSC 7501 status, writes
/// transcript fixtures and records every input line, as its script says.
pub struct FakeAgent {
    /// Terminal address (`t3`).
    pub address: String,
    input_log: PathBuf,
}

impl FakeAgent {
    /// The input lines the Agent has read so far, in order.
    pub fn inputs(&self) -> Vec<String> {
        std::fs::read_to_string(&self.input_log)
            .unwrap_or_default()
            .lines()
            .map(str::to_owned)
            .collect()
    }
}

impl HcSession {
    /// Starts the fake Agent in a new background Terminal running `script`
    /// (steps are documented in the fake-agent file).
    pub fn run_fake_agent(&self, name: &str, script: &str) -> FakeAgent {
        let dir = self.dir.path().join("agents").join(name);
        std::fs::create_dir_all(&dir).expect("agent dir");
        let script_path = dir.join("script");
        std::fs::write(&script_path, script).expect("write agent script");
        let input_log = dir.join("input");
        let log_env = format!("FAKE_AGENT_INPUT={}", input_log.display());
        let agent = fake_agent_path();
        let address = self.run_bg(
            name,
            &[
                "env",
                &log_env,
                "/bin/sh",
                &agent.to_string_lossy(),
                &script_path.to_string_lossy(),
            ],
        );
        FakeAgent { address, input_log }
    }
}

impl HcSession {
    /// Adds `remote` to this core as machine `name` (`hc machine add --via`),
    /// so its Terminals show up here as `name/tN`. The `--via` command is a
    /// small script that runs herdr-core's shell line in `remote`'s
    /// environment instead of over ssh.
    pub fn add_machine(&self, name: &str, remote: &HcSession) {
        let via = self.dir.path().join(format!("via-{name}.sh"));
        let mut script = String::from("#!/bin/sh\nfor line; do :; done\nexec env");
        for (k, v) in remote.env() {
            script.push_str(&format!(" '{k}={v}'"));
        }
        let bin_dir = remote.bin.parent().expect("bin dir").display().to_string();
        script.push_str(&format!(" \"PATH={bin_dir}:$PATH\" /bin/sh -c \"$line\"\n"));
        std::fs::write(&via, script).expect("write via script");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&via, std::fs::Permissions::from_mode(0o755))
                .expect("chmod via script");
        }
        self.hc(&[
            "machine",
            "add",
            "--json",
            "--via",
            &via.to_string_lossy(),
            "--name",
            name,
            "--session",
            remote.name(),
        ]);
    }
}
