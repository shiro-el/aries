//! [`ReleaseServer`]: a local stand-in for GitHub Releases.

use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Serves fixed files over plain HTTP (`GET /<name>`) from an `nginx:alpine`
/// container on the default docker bridge, where docker Hosts
/// ([`crate::SystemdHost`]) reach it by address. A container rather than a
/// socket on this machine, because host firewalls commonly drop traffic
/// from containers to the host. Removed on drop.
pub struct ReleaseServer {
    container: String,
    address: String,
    _files: tempfile::TempDir,
}

impl ReleaseServer {
    /// Serves `files` (name, bytes); other paths get 404.
    pub fn start(files: Vec<(String, Vec<u8>)>) -> Self {
        let dir = tempfile::tempdir().expect("release dir");
        for (name, bytes) in &files {
            let path = dir.path().join(name);
            std::fs::write(&path, bytes).expect("write release file");
            set_mode(&path, 0o644);
        }
        // nginx's workers run as an unprivileged user.
        set_mode(dir.path(), 0o755);
        let prefix =
            std::env::var("ARIES_TEST_CONTAINER_PREFIX").unwrap_or_else(|_| "aries-test".into());
        let container = crate::unique(&format!("{prefix}-release"));
        let mount = format!("{}:/usr/share/nginx/html:ro", dir.path().display());
        crate::docker(&[
            "run",
            "-d",
            "--rm",
            "--name",
            &container,
            "-v",
            &mount,
            "nginx:alpine",
        ]);
        let server = Self {
            address: crate::docker(&[
                "inspect",
                "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                &container,
            ])
            .trim()
            .to_owned(),
            container,
            _files: dir,
        };
        server.wait_ready();
        server
    }

    /// URL of `name` as a docker Host sees it.
    pub fn url(&self, name: &str) -> String {
        format!("http://{}/{name}", self.address)
    }

    /// Request paths so far (`/aries-beacon-linux-x86_64`), from nginx's
    /// access log.
    pub fn requests(&self) -> Vec<String> {
        let out = Command::new("docker")
            .args(["logs", &self.container])
            .stderr(Stdio::null())
            .output()
            .expect("docker logs");
        String::from_utf8_lossy(&out.stdout)
            .lines()
            .filter_map(|l| {
                let request = l.split('"').nth(1)?;
                let mut parts = request.split_whitespace();
                (parts.next()? == "GET").then(|| parts.next().map(str::to_owned))?
            })
            .collect()
    }

    fn wait_ready(&self) {
        let deadline = Instant::now() + Duration::from_secs(30);
        while !Command::new("docker")
            .args(["exec", &self.container, "test", "-s", "/run/nginx.pid"])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok_and(|s| s.success())
        {
            assert!(Instant::now() < deadline, "nginx did not start");
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

impl Drop for ReleaseServer {
    fn drop(&mut self) {
        let _ = Command::new("docker")
            .args(["rm", "-f", &self.container])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

fn set_mode(path: &std::path::Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)).expect("chmod");
}
