//! Beacon manager: installs, inspects and removes the Beacon on a Host.
//!
//! Install (Linux): detect OS/CPU with `uname`, pick the matching artifact of
//! the release pinned in the app, have the phone download it (the app's
//! [`BeaconFetcher`]), verify it against the pinned SHA-256, upload it over
//! SSH, check it again on the Host, register a systemd user service and try
//! `loginctl enable-linger`. The Host needs no internet access.
//!
//! On the Host: binary `~/.local/bin/aries-beacon`, unit
//! `~/.config/systemd/user/aries-beacon.service`, private state (Call records)
//! in `~/.local/state/aries-beacon/`, which removal keeps.

use aries_protocol::beacon::{BeaconStatus, VersionRange};
use sha2::{Digest, Sha256};

use crate::error::CoreError;
use crate::transport::{ExecOutput, Ssh};

/// Beacon releases this app works with.
pub const ALLOWED_BEACON: VersionRange = VersionRange::new("0.1.0", "0.2.0");

/// Downloads a Beacon release artifact on the phone (HTTP on Android).
#[uniffi::export(with_foreign)]
pub trait BeaconFetcher: Send + Sync {
    fn fetch(&self, url: String) -> Result<Vec<u8>, FetchError>;
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FetchError {
    #[error("{detail}")]
    Failed { detail: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for FetchError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed { detail: e.reason }
    }
}

/// One downloadable Beacon binary.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BeaconArtifact {
    /// `linux` or `macos`.
    pub os: String,
    /// `x86_64` or `aarch64`.
    pub arch: String,
    pub url: String,
    /// Lowercase hex SHA-256 of the binary.
    pub sha256: String,
}

/// A Beacon release as pinned in the app.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BeaconRelease {
    pub version: String,
    pub artifacts: Vec<BeaconArtifact>,
}

/// The release this app installs. Its artifacts and checksums are filled in
/// by the Beacon release pipeline (ticket 26); until then the app has none
/// and refuses with [`CoreError::BeaconUnavailable`].
pub fn pinned_release() -> BeaconRelease {
    BeaconRelease {
        version: env!("CARGO_PKG_VERSION").into(),
        artifacts: Vec::new(),
    }
}

/// Whether the Beacon's service survives logout and reboot.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Linger {
    Enabled,
    /// `loginctl enable-linger` failed; without it the service only runs
    /// while the user is logged in. The fix is `sudo loginctl enable-linger
    /// <user>` on the Host.
    Failed {
        reason: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BeaconInstall {
    pub version: String,
    pub linger: Linger,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum BeaconState {
    NotInstalled,
    Installed {
        version: String,
        contract: u32,
        /// The resident service is running.
        running: bool,
        /// herdr-core version of the local core the service is attached to.
        core_version: Option<String>,
        linger: bool,
        /// Within [`ALLOWED_BEACON`] and the same contract as this app.
        supported: bool,
        /// The range this app accepts, for the message (`>=0.1.0, <0.2.0`).
        allowed: String,
    },
}

const UNIT: &str = "\
[Unit]
Description=Aries Beacon

[Service]
ExecStart=%h/.local/bin/aries-beacon serve
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
";

/// Removal, one step per resource. Tickets that put more on the Host
/// (pairing 27, managed skill 34) add their own step here. The Beacon's
/// state directory is not removed: it holds the Call records.
pub(crate) const UNINSTALL_STEPS: &[(&str, &str)] = &[
    (
        "service",
        r#"systemctl --user disable --now aries-beacon.service >/dev/null 2>&1 || true
u="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
rm -f "$u/aries-beacon.service"
systemctl --user daemon-reload >/dev/null 2>&1 || true
systemctl --user reset-failed aries-beacon.service >/dev/null 2>&1 || true"#,
    ),
    (
        "binary",
        r#"rm -f "$HOME/.local/bin/aries-beacon" "$HOME/.local/bin/.aries-beacon.new""#,
    ),
    (
        "service state",
        r#"rm -f "${XDG_STATE_HOME:-$HOME/.local/state}/aries-beacon/service.json""#,
    ),
];

const DISCARD_UPLOAD: &str = r#"rm -f "$HOME/.local/bin/.aries-beacon.new""#;

const STATUS: &str = r#"b="$HOME/.local/bin/aries-beacon"; if [ -x "$b" ]; then exec "$b" status; else echo not-installed; fi"#;

pub(crate) async fn install(
    ssh: &Ssh,
    release: &BeaconRelease,
    fetcher: std::sync::Arc<dyn BeaconFetcher>,
) -> Result<BeaconInstall, CoreError> {
    let (os, arch) = detect(ssh).await?;
    if os != "linux" {
        return Err(CoreError::UnsupportedHost { os, arch });
    }
    let artifact = release
        .artifacts
        .iter()
        .find(|a| a.os == os && a.arch == arch)
        .ok_or_else(|| CoreError::BeaconUnavailable {
            os: os.clone(),
            arch: arch.clone(),
        })?
        .clone();

    let url = artifact.url.clone();
    let bytes = tokio::task::spawn_blocking(move || fetcher.fetch(url))
        .await
        .map_err(|e| CoreError::BeaconDownload {
            detail: e.to_string(),
        })?
        .map_err(|e| CoreError::BeaconDownload {
            detail: e.to_string(),
        })?;
    let actual = sha256_hex(&bytes);
    let expected = artifact.sha256.to_ascii_lowercase();
    if actual != expected {
        return Err(CoreError::BeaconChecksum { expected, actual });
    }

    // Upload next to the final path, then check what landed on the Host.
    let upload = r#"set -e; d="$HOME/.local/bin"; mkdir -p "$d"; t="$d/.aries-beacon.new"; rm -f "$t"; cat > "$t"; chmod 755 "$t"; sha256sum "$t""#;
    let out = run(ssh, "upload", upload, &bytes).await?;
    let landed = last_line(&out.stdout);
    if landed.split_whitespace().next() != Some(expected.as_str()) {
        let _ = ssh.exec(&sh_c(DISCARD_UPLOAD), &[]).await;
        return Err(step_failed(
            "upload",
            "the uploaded file's checksum differs",
        ));
    }
    let out = run(
        ssh,
        "verify",
        r#""$HOME/.local/bin/.aries-beacon.new" --version"#,
        &[],
    )
    .await?;
    let reported = last_line(&out.stdout);
    if reported.split_whitespace().nth(1) != Some(release.version.as_str()) {
        let _ = ssh.exec(&sh_c(DISCARD_UPLOAD), &[]).await;
        return Err(step_failed(
            "verify",
            format!(
                "expected Beacon {}, got {}",
                release.version,
                reported.trim()
            ),
        ));
    }

    let service = format!(
        r#"set -e
d="$HOME/.local/bin"
mv -f "$d/.aries-beacon.new" "$d/aries-beacon"
u="${{XDG_CONFIG_HOME:-$HOME/.config}}/systemd/user"
mkdir -p "$u"
cat > "$u/aries-beacon.service" <<'ARIES_UNIT'
{UNIT}ARIES_UNIT
systemctl --user daemon-reload
systemctl --user enable aries-beacon.service
systemctl --user restart aries-beacon.service"#
    );
    run(ssh, "service", &service, &[]).await?;

    let linger = r#"u=$(id -un)
if [ -e "/var/lib/systemd/linger/$u" ]; then exit 0; fi
loginctl enable-linger "$u" 2>&1"#;
    let out = ssh.exec(&sh_c(linger), &[]).await?;
    let linger = if out.success() {
        Linger::Enabled
    } else {
        let mut reason = String::from_utf8_lossy(&out.stdout).trim().to_owned();
        if reason.is_empty() {
            reason = format!("loginctl exited with {:?}", out.status);
        }
        Linger::Failed { reason }
    };
    Ok(BeaconInstall {
        version: release.version.clone(),
        linger,
    })
}

pub(crate) async fn status(ssh: &Ssh) -> Result<BeaconState, CoreError> {
    let out = run(ssh, "status", STATUS, &[]).await?;
    let line = last_line(&out.stdout);
    if line == "not-installed" {
        return Ok(BeaconState::NotInstalled);
    }
    let s: BeaconStatus = serde_json::from_str(&line).map_err(|e| CoreError::Protocol {
        detail: format!("aries-beacon status: {e}: {line}"),
    })?;
    Ok(BeaconState::Installed {
        supported: ALLOWED_BEACON.contains(&s.version)
            && s.contract == aries_protocol::CONTRACT_VERSION,
        allowed: ALLOWED_BEACON.to_string(),
        version: s.version,
        contract: s.contract,
        running: s.running,
        core_version: s.core_version,
        linger: s.linger,
    })
}

pub(crate) async fn uninstall(ssh: &Ssh) -> Result<(), CoreError> {
    for (name, script) in UNINSTALL_STEPS {
        run(ssh, name, script, &[]).await?;
    }
    Ok(())
}

/// `uname -s` / `uname -m`, normalised to `linux`/`macos` and
/// `x86_64`/`aarch64`.
async fn detect(ssh: &Ssh) -> Result<(String, String), CoreError> {
    let out = run(ssh, "detect", "uname -s; uname -m", &[]).await?;
    let text = String::from_utf8_lossy(&out.stdout);
    // The last two lines: a login shell may print a banner first.
    let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty()).rev();
    let machine = lines.next().unwrap_or_default();
    let kernel = lines.next().unwrap_or_default();
    Ok(normalize(kernel, machine))
}

fn normalize(kernel: &str, machine: &str) -> (String, String) {
    let os = match kernel {
        "Linux" => "linux",
        "Darwin" => "macos",
        other => other,
    };
    let arch = match machine {
        "x86_64" | "amd64" => "x86_64",
        "aarch64" | "arm64" => "aarch64",
        other => other,
    };
    (os.to_owned(), arch.to_owned())
}

async fn run(ssh: &Ssh, step: &str, script: &str, stdin: &[u8]) -> Result<ExecOutput, CoreError> {
    let out = ssh.exec(&sh_c(script), stdin).await?;
    if !out.success() {
        let detail = String::from_utf8_lossy(&out.stderr).trim().to_owned();
        return Err(step_failed(
            step,
            if detail.is_empty() {
                format!("exited with {:?}", out.status)
            } else {
                detail
            },
        ));
    }
    Ok(out)
}

/// The last non-empty line of `out` (a login shell may print a banner first).
fn last_line(out: &[u8]) -> String {
    String::from_utf8_lossy(out)
        .lines()
        .map(str::trim)
        .rfind(|l| !l.is_empty())
        .unwrap_or_default()
        .to_owned()
}

/// Runs `script` under `sh` whatever the user's login shell is.
fn sh_c(script: &str) -> String {
    format!("sh -c '{}'", script.replace('\'', r"'\''"))
}

fn step_failed(step: &str, detail: impl Into<String>) -> CoreError {
    CoreError::BeaconStep {
        step: step.to_owned(),
        detail: detail.into(),
    }
}

fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_uname_to_release_targets() {
        assert_eq!(
            normalize("Linux", "x86_64"),
            ("linux".into(), "x86_64".into())
        );
        assert_eq!(
            normalize("Linux", "aarch64"),
            ("linux".into(), "aarch64".into())
        );
        assert_eq!(
            normalize("Darwin", "arm64"),
            ("macos".into(), "aarch64".into())
        );
        assert_eq!(
            normalize("FreeBSD", "riscv64"),
            ("FreeBSD".into(), "riscv64".into())
        );
    }

    #[test]
    fn hashes_like_sha256sum() {
        // `printf abc | sha256sum`
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
