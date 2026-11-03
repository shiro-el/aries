//! Explicit, checksum-pinned herdr-core installation over the saved SSH route.
//! Downloads are staged privately, verified before extraction/execution, and
//! removed after success or failure. Updates use hc's terminal-preserving updater.
use std::sync::Arc;

use aries_protocol::beacon::parse_version;
use sha2::{Digest, Sha256};

use crate::beacon::sh_c;
use crate::{BeaconFetcher, CoreError, MIN_CORE_VERSION, transport::Ssh};

#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize, uniffi::Record)]
pub struct HerdrCoreArtifact {
    pub os: String,
    pub arch: String,
    pub url: String,
    /// SHA-256 of the release tar.gz, not the unpacked binary.
    pub sha256: String,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize, uniffi::Record)]
pub struct HerdrCoreRelease {
    pub version: String,
    pub artifacts: Vec<HerdrCoreArtifact>,
}

pub fn pinned_herdr_core_release() -> HerdrCoreRelease {
    serde_json::from_str(include_str!("herdr-release.json")).expect("pinned herdr-core release")
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HerdrCoreState {
    Missing,
    Installed { version: String, needs_update: bool },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HerdrInstallStep {
    Detect,
    Download,
    Verify,
    Install,
    Done,
}

/// Progress and the bounded stdout/stderr of completed remote steps.
#[uniffi::export(with_foreign)]
pub trait HerdrInstallListener: Send + Sync {
    fn progress(&self, step: HerdrInstallStep, output: String);
}

// Same lookup as the SSH transport. Do not misclassify an unexecutable or
// broken installation as missing, and never use hc (which can be another tool).
const LOOKUP: &str = r#"p=$(command -v herdr-core) || p="$HOME/.local/bin/herdr-core""#;

pub(crate) async fn status(ssh: &Ssh) -> Result<HerdrCoreState, CoreError> {
    let output = run(ssh, "status", &format!(r#"{LOOKUP}; if [ -e "$p" ] || [ -L "$p" ]; then "$p" --version; else echo aries-core-missing; fi"#), &[]).await?;
    let reported = last_line(&output);
    if reported == "aries-core-missing" {
        return Ok(HerdrCoreState::Missing);
    }
    let version = reported
        .strip_prefix("hc ")
        .and_then(|v| parse_version(v).map(|_| v))
        .ok_or_else(|| {
            failed(
                "status",
                format!("Unrecognized herdr-core version: {reported}"),
            )
        })?;
    Ok(HerdrCoreState::Installed {
        version: version.into(),
        needs_update: parse_version(version) < parse_version(MIN_CORE_VERSION),
    })
}

pub(crate) async fn install(
    ssh: &Ssh,
    release: &HerdrCoreRelease,
    fetcher: Arc<dyn BeaconFetcher>,
    listener: Arc<dyn HerdrInstallListener>,
) -> Result<HerdrCoreState, CoreError> {
    listener.progress(HerdrInstallStep::Detect, String::new());
    let out = run(ssh, "detect", "uname -s; uname -m", &[]).await?;
    let lines: Vec<_> = out
        .lines()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect();
    let (os, arch) = match lines.as_slice() {
        [.., kernel, machine] => (
            match *kernel {
                "Linux" => "linux",
                "Darwin" => "macos",
                other => other,
            },
            match *machine {
                "amd64" => "x86_64",
                "arm64" => "aarch64",
                other => other,
            },
        ),
        _ => return Err(failed("detect", "Could not detect the Host OS and CPU")),
    };
    let artifact = release
        .artifacts
        .iter()
        .find(|a| a.os == os && a.arch == arch)
        .ok_or_else(|| failed("detect", format!("No herdr-core release for {os}/{arch}")))?;
    if parse_version(&release.version).is_none_or(|v| v < parse_version(MIN_CORE_VERSION).unwrap())
        || artifact.sha256.len() != 64
        || !artifact.sha256.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Err(failed("verify", "Invalid pinned herdr-core release"));
    }
    let current = status(ssh).await?;
    if matches!(
        current,
        HerdrCoreState::Installed {
            needs_update: false,
            ..
        }
    ) {
        listener.progress(HerdrInstallStep::Done, String::new());
        return Ok(current);
    }
    let out = run(ssh, "stage", r#"set -e; umask 077; mkdir -p "$HOME/.local/bin"; mktemp -d "$HOME/.local/bin/.aries-herdr.XXXXXX""#, &[]).await?;
    let dir = last_line(&out);
    // Only accept a path created by our mktemp, even on a noisy login shell.
    if !dir.starts_with('/') || !dir.contains("/.local/bin/.aries-herdr.") || dir.contains('\n') {
        return Err(failed("stage", "Invalid temporary installation directory"));
    }
    let result = install_staged(ssh, release, artifact, &dir, fetcher, &*listener).await;
    let _ = ssh
        .exec(&sh_c(&format!("rm -rf {}", quote(&dir))), &[])
        .await;
    result
}

async fn install_staged(
    ssh: &Ssh,
    release: &HerdrCoreRelease,
    artifact: &HerdrCoreArtifact,
    dir: &str,
    fetcher: Arc<dyn BeaconFetcher>,
    listener: &dyn HerdrInstallListener,
) -> Result<HerdrCoreState, CoreError> {
    use HerdrInstallStep::*;
    let archive = quote(&format!("{dir}/core.tar.gz"));
    listener.progress(Download, String::new());
    let out = run(
        ssh,
        "download",
        &format!(
            r#"if command -v curl >/dev/null 2>&1; then
curl -fLsS --connect-timeout 10 --max-time 300 -o {archive} {url}
elif command -v wget >/dev/null 2>&1; then
wget -q -T 30 -O {archive} {url}
else false; fi && echo aries-downloaded || echo aries-phone-download"#,
            url = quote(&artifact.url)
        ),
        &[],
    )
    .await?;
    if last_line(&out) != "aries-downloaded" {
        let url = artifact.url.clone();
        let bytes = tokio::task::spawn_blocking(move || fetcher.fetch(url))
            .await
            .map_err(|e| failed("download", e.to_string()))?
            .map_err(|e| failed("download", e.to_string()))?;
        let actual: String = Sha256::digest(&bytes)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        checksum(&artifact.sha256, &actual)?;
        run(ssh, "upload", &format!("cat > {archive}"), &bytes).await?;
    }
    listener.progress(Verify, String::new());
    let out = run(ssh, "checksum", &format!(r#"if command -v sha256sum >/dev/null 2>&1; then sha256sum {archive}; else shasum -a 256 {archive}; fi"#), &[]).await?;
    let line = last_line(&out);
    checksum(
        &artifact.sha256,
        line.split_whitespace().next().unwrap_or_default(),
    )?;
    let binary = quote(&format!("{dir}/herdr-core"));
    let out = run(
        ssh,
        "verify",
        &format!(
            "set -e; tar -xzf {archive} -C {} herdr-core; chmod 755 {binary}; {binary} --version",
            quote(dir)
        ),
        &[],
    )
    .await?;
    if last_line(&out) != format!("hc {}", release.version) {
        return Err(failed(
            "verify",
            format!("Expected hc {}, got {}", release.version, last_line(&out)),
        ));
    }
    listener.progress(Verify, out);
    listener.progress(Install, String::new());
    // A preexisting core updates itself, preserving its running terminals.
    // Refuse a foreign PATH install: putting a second binary in ~/.local/bin
    // would not change the one Aries connects to. Its package manager owns it.
    let script = format!(
        r#"set -e
{LOOKUP}
if [ -e "$p" ] || [ -L "$p" ]; then
  [ "$p" = "$HOME/.local/bin/herdr-core" ] || {{ echo 'Update the existing herdr-core with its package manager or the way it was installed, then retry.' >&2; exit 1; }}
  "$p" update --from {binary}
else
  mv -f {binary} "$HOME/.local/bin/herdr-core"
fi
if ! command -v hc >/dev/null 2>&1 && [ ! -e "$HOME/.local/bin/hc" ] && [ ! -L "$HOME/.local/bin/hc" ]; then
  ln -s herdr-core "$HOME/.local/bin/hc"
fi
"$HOME/.local/bin/herdr-core" --version"#
    );
    let out = run(ssh, "install", &script, &[]).await?;
    listener.progress(Install, out);
    let state = status(ssh).await?;
    if !matches!(&state, HerdrCoreState::Installed { version, needs_update: false } if version == &release.version)
    {
        return Err(failed(
            "install",
            "Installation did not activate the pinned version; retry or check the Host PATH",
        ));
    }
    listener.progress(Done, String::new());
    Ok(state)
}

fn checksum(expected: &str, actual: &str) -> Result<(), CoreError> {
    if expected.eq_ignore_ascii_case(actual) {
        Ok(())
    } else {
        Err(CoreError::HerdrChecksum {
            expected: expected.into(),
            actual: actual.into(),
        })
    }
}
fn failed(step: &str, detail: impl Into<String>) -> CoreError {
    CoreError::HerdrInstall {
        step: step.into(),
        detail: detail.into(),
    }
}
fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}
fn last_line(s: &str) -> String {
    s.lines()
        .map(str::trim)
        .rfind(|s| !s.is_empty())
        .unwrap_or_default()
        .into()
}
async fn run(ssh: &Ssh, step: &str, script: &str, stdin: &[u8]) -> Result<String, CoreError> {
    let out = ssh.exec(&sh_c(script), stdin).await?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    // Keep diagnostics useful without unbounded UI/log growth.
    let text: String = text.chars().take(16_384).collect();
    if !out.success() {
        return Err(failed(step, format!("exit {:?}: {text}", out.status)));
    }
    Ok(text)
}
