//! Beacon manager: installs, updates, inspects and removes the Beacon on a
//! Host.
//!
//! Install: detect OS/CPU with `uname`, pick the matching artifact of the
//! release pinned in the app ([`pinned_release`], filled by the Beacon
//! release pipeline). The Host downloads it itself (`curl`/`wget`) and
//! checks it against the pinned SHA-256; a Host that cannot download gets it
//! from the phone instead (the app's [`BeaconFetcher`], checked on the phone,
//! uploaded over SSH and checked again on the Host). A download that arrives
//! with the wrong checksum is refused, never installed. Then the binary's
//! `--version` must be the pinned one, and it is registered as a service:
//! a systemd user service plus `loginctl enable-linger` on Linux, a launchd
//! LaunchAgent on macOS.
//!
//! On the Host: binary `~/.local/bin/aries-beacon`; Linux unit
//! `~/.config/systemd/user/aries-beacon.service`, macOS agent
//! `~/Library/LaunchAgents/io.github.shiroel.aries.beacon.plist`; private
//! state (Call records) in `~/.local/state/aries-beacon/`, which removal
//! keeps.
//!
//! The Beacon also manages files in Agents' own configuration (the OpenCode
//! plugin `${XDG_CONFIG_HOME:-~/.config}/opencode/plugins/aries-agent-state.js`):
//! every install and update runs `aries-beacon opencode-plugin install`, which
//! leaves a copy the user edited alone; removal deletes an unedited copy.

use aries_protocol::beacon::{
    BeaconStatus, SkillOutcome, SkillReport, VersionRange, parse_version,
};
use sha2::{Digest, Sha256};

use crate::error::CoreError;
use crate::transport::{ExecOutput, Ssh};

/// Beacon releases this app works with.
pub const ALLOWED_BEACON: VersionRange = VersionRange::new("0.1.0", "0.2.0");

/// launchd label of the macOS LaunchAgent.
pub(crate) const LAUNCHD_LABEL: &str = "io.github.shiroel.aries.beacon";

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
#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize, uniffi::Record)]
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
#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize, uniffi::Record)]
pub struct BeaconRelease {
    pub version: String,
    pub artifacts: Vec<BeaconArtifact>,
}

/// The `beacon-release.json` pinned at build time (see `build.rs`).
const PINNED: &str = include_str!(concat!(env!("OUT_DIR"), "/beacon-release.json"));

/// The release this app installs: the manifest `scripts/beacon-manifest.sh`
/// wrote for this build's release (`ARIES_BEACON_MANIFEST`). Development
/// builds have none and refuse with [`CoreError::BeaconUnavailable`].
pub fn pinned_release() -> BeaconRelease {
    serde_json::from_str(PINNED).expect("build.rs checked the Beacon manifest")
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
    /// macOS: a LaunchAgent belongs to the user's login session. It starts
    /// at login and stops at logout; after a reboot it waits for a login.
    LoginSession,
}

/// How the binary reached the Host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BeaconSource {
    /// The Host downloaded it from the release itself.
    HostDownload,
    /// The Host could not download; the phone did and uploaded it over SSH.
    PhoneUpload,
}

/// An aries-call-human path and the result of installing or inspecting it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BeaconSkillRoot {
    pub path: String,
    pub outcome: BeaconSkillOutcome,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BeaconSkillOutcome {
    Installed,
    Updated,
    Unchanged,
    Removed,
    Absent,
    KeptModified,
    KeptForeign,
    KeptSymlink,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BeaconInstall {
    pub version: String,
    pub linger: Linger,
    pub source: BeaconSource,
    pub skills: Vec<BeaconSkillRoot>,
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
        /// Linux: systemd keeps the service running without a login.
        linger: bool,
        /// Within [`ALLOWED_BEACON`] and the same contract as this app.
        supported: bool,
        /// The range this app accepts, for the message (`>=0.1.0, <0.2.0`).
        allowed: String,
        /// The Host's OS: `linux` or `macos`.
        os: String,
        /// None means this Beacon did not report skill state.
        skills: Option<Vec<BeaconSkillRoot>>,
    },
}

// Legacy cleanup is reported by the Beacon separately; only current-name
// roots determine whether Agents can use the Aries skill.
fn skill_roots(report: SkillReport) -> Vec<BeaconSkillRoot> {
    report
        .roots
        .into_iter()
        .map(|root| BeaconSkillRoot {
            path: root.path,
            outcome: match root.outcome {
                SkillOutcome::Installed => BeaconSkillOutcome::Installed,
                SkillOutcome::Updated => BeaconSkillOutcome::Updated,
                SkillOutcome::Unchanged => BeaconSkillOutcome::Unchanged,
                SkillOutcome::Removed => BeaconSkillOutcome::Removed,
                SkillOutcome::Absent => BeaconSkillOutcome::Absent,
                SkillOutcome::KeptModified => BeaconSkillOutcome::KeptModified,
                SkillOutcome::KeptForeign => BeaconSkillOutcome::KeptForeign,
                SkillOutcome::KeptSymlink => BeaconSkillOutcome::KeptSymlink,
            },
        })
        .collect()
}

fn parse_skill_report(stdout: &[u8]) -> Result<Vec<BeaconSkillRoot>, CoreError> {
    let report = serde_json::from_str::<SkillReport>(&last_line(stdout))
        .map_err(|e| step_failed("skill", format!("invalid skill report: {e}")))?;
    Ok(skill_roots(report))
}

/// What connecting found, and what it did about the Beacon's version.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BeaconCheck {
    /// The Beacon as it is now (after an update, the new one).
    pub state: BeaconState,
    pub update: BeaconUpdate,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum BeaconUpdate {
    /// Not installed, or a version this app is happy with.
    NotNeeded,
    /// It was out of date and was replaced with the pinned release.
    Updated {
        from: String,
        install: BeaconInstall,
    },
    /// It is out of date, and automatic updates are off: the app only says so.
    Needed,
}

/// The installed Beacon must be replaced by `pinned`: outside the range
/// this app accepts (or another contract), or older than the pinned
/// release. A newer Beacon within the range is left alone.
pub(crate) fn needs_update(state: &BeaconState, pinned: &str) -> bool {
    match state {
        BeaconState::NotInstalled => false,
        BeaconState::Installed {
            version, supported, ..
        } => {
            !supported
                || matches!(
                    (parse_version(version), parse_version(pinned)),
                    (Some(v), Some(p)) if v < p
                )
        }
    }
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

/// The LaunchAgent, as a shell heredoc body: `$HOME` expands on the Host
/// (launchd does not expand `~`). `PATH` covers where herdr-core is usually
/// installed, since launchd's default PATH is only the system directories.
fn launch_agent() -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>{LAUNCHD_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>$HOME/.local/bin/aries-beacon</string>
    <string>serve</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>5</integer>
  <key>StandardErrorPath</key>
  <string>$HOME/.local/state/aries-beacon/beacon.log</string>
</dict>
</plist>
"#
    )
}

/// Removal, one step per resource. Tickets that put more on the Host
/// (pairing 27, managed skill 34) add their own step here. The Beacon's
/// state directory is not removed: it holds the Call records.
pub(crate) const UNINSTALL_STEPS: &[(&str, &str)] = &[
    // Ticket 42: the OpenCode plugin, unless the user edited it (its first
    // line holds the SHA-256 of the rest; same check as the Beacon's
    // `opencode-plugin remove`, in shell so it works without the binary).
    (
        "opencode plugin",
        r#"c="${XDG_CONFIG_HOME:-$HOME/.config}/opencode"; f="$c/plugins/aries-agent-state.js"
[ -f "$f" ] || exit 0
want=$(sed -n '1s/^\/\/ aries-managed: .* sha256=\([0-9a-f]\{64\}\)$/\1/p' "$f")
[ -n "$want" ] || exit 0
if command -v sha256sum >/dev/null 2>&1; then got=$(tail -n +2 "$f" | sha256sum); else got=$(tail -n +2 "$f" | shasum -a 256); fi
if [ "${got%% *}" = "$want" ]; then
  rm -f "$f"
  rmdir "$c/plugins" "$c" 2>/dev/null
fi
exit 0"#,
    ),
    // Ticket 27: revoke the push pairing on the Relay (best effort: the
    // phone revokes it too) and drop it from the Host.
    (
        "pairing",
        r#"b="$HOME/.local/bin/aries-beacon"
if [ -x "$b" ]; then "$b" unpair >/dev/null 2>&1 || true; fi
rm -f "${XDG_STATE_HOME:-$HOME/.local/state}/aries-beacon/pairing.json""#,
    ),
    // Ticket 34: the managed `aries-call-human` skill, unless the user changed it
    // (the Beacon decides). Best effort, like the pairing: a Beacon from
    // before the skill existed has no `skill` command.
    (
        "skill",
        r#"b="$HOME/.local/bin/aries-beacon"
if [ -x "$b" ]; then "$b" skill uninstall >/dev/null 2>&1 || true; fi"#,
    ),
    // Ticket 43: the managed Pi extension, unless the user edited it
    // (same check as `aries_beacon::pi_extension::remove`, in plain sh so
    // it works without the Beacon binary).
    ("pi extension", PI_EXTENSION_REMOVE),
    (
        "service",
        r#"if [ "$(uname -s)" = Darwin ]; then
  l=io.github.shiroel.aries.beacon; uid=$(id -u)
  launchctl bootout "gui/$uid/$l" >/dev/null 2>&1 || true
  launchctl bootout "user/$uid/$l" >/dev/null 2>&1 || true
  rm -f "$HOME/Library/LaunchAgents/$l.plist"
  exit 0
fi
systemctl --user disable --now aries-beacon.service >/dev/null 2>&1 || true
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

/// Removes `aries-status.ts` from Pi's extension dir if its marker line's
/// hash still matches the rest of the file, then the dirs it leaves empty.
const PI_EXTENSION_REMOVE: &str = r#"a="${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}"
case "$a" in "~") a="$HOME" ;; "~/"*) a="$HOME/${a#\~/}" ;; esac
f="$a/extensions/aries-status.ts"
[ -f "$f" ] || exit 0
m='// @aries-managed-pi-extension sha256='
first=$(head -n 1 "$f")
case "$first" in "$m"*) ;; *) exit 0 ;; esac
want=${first#"$m"}
if command -v sha256sum >/dev/null 2>&1; then
  have=$(tail -n +2 "$f" | sha256sum)
else
  have=$(tail -n +2 "$f" | shasum -a 256)
fi
have=${have%% *}
[ "$want" = "$have" ] || exit 0
rm -f "$f"
rmdir "$a/extensions" 2>/dev/null || true
if [ -z "${PI_CODING_AGENT_DIR:-}" ]; then rmdir "$a" "$HOME/.pi" 2>/dev/null || true; fi
exit 0"#;

/// Installs or updates the managed Pi extension with the Beacon just put
/// in place (`aries-beacon pi-extension install`). Best effort: a Host
/// where it cannot be written still gets its Beacon.
const PI_EXTENSION_INSTALL: &str =
    r#""$HOME/.local/bin/aries-beacon" pi-extension install >/dev/null 2>&1 || true"#;

/// Where the Beacon binary is installed on the Host (for `sh`).
pub(crate) const BEACON_BIN: &str = "$HOME/.local/bin/aries-beacon";

/// Installs or updates the OpenCode status plugin (it keeps a user-edited copy).
const OPENCODE_PLUGIN_INSTALL: &str = r#""$HOME/.local/bin/aries-beacon" opencode-plugin install"#;

const DISCARD_UPLOAD: &str = r#"rm -f "$HOME/.local/bin/.aries-beacon.new""#;

/// SHA-256 of `$t` as `<hex>  <path>`: `sha256sum` (Linux) or `shasum`
/// (macOS).
const SUM: &str =
    r#"if command -v sha256sum >/dev/null 2>&1; then sha256sum "$t"; else shasum -a 256 "$t"; fi"#;

/// Prints the Host's kernel name, then the Beacon's status line.
const STATUS: &str = r#"uname -s; b="$HOME/.local/bin/aries-beacon"; if [ -x "$b" ]; then exec "$b" status; else echo not-installed; fi"#;

/// Printed by the download step when the Host cannot download.
const NO_DOWNLOAD: &str = "aries-no-download";

pub(crate) async fn install(
    ssh: &Ssh,
    release: &BeaconRelease,
    fetcher: std::sync::Arc<dyn BeaconFetcher>,
) -> Result<BeaconInstall, CoreError> {
    let (os, arch) = detect(ssh).await?;
    if os != "linux" && os != "macos" {
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
    let expected = artifact.sha256.to_ascii_lowercase();

    let source = match host_download(ssh, &artifact.url).await? {
        Some(landed) if landed == expected => BeaconSource::HostDownload,
        Some(actual) => {
            let _ = ssh.exec(&sh_c(DISCARD_UPLOAD), &[]).await;
            return Err(CoreError::BeaconChecksum { expected, actual });
        }
        None => {
            phone_upload(ssh, &artifact.url, &expected, fetcher).await?;
            BeaconSource::PhoneUpload
        }
    };

    let out = run(
        ssh,
        "verify",
        &sh_c(r#""$HOME/.local/bin/.aries-beacon.new" --version"#),
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

    let linger = if os == "macos" {
        register_launch_agent(ssh).await?;
        Linger::LoginSession
    } else {
        register_systemd(ssh).await?
    };
    // Ticket 34: the new binary installs or updates its `aries-call-human` skill
    // (it leaves the user's own version alone).
    let skill_output = run(
        ssh,
        "skill",
        &sh_c(r#""$HOME/.local/bin/aries-beacon" skill install"#),
        &[],
    )
    .await?;
    let skills = parse_skill_report(&skill_output.stdout)?;
    // Ticket 43: and the Pi status extension.
    let _ = ssh.exec(&sh_c(PI_EXTENSION_INSTALL), &[]).await;
    // Ticket 42: and the OpenCode status plugin. Best effort, like Pi's: an
    // Agent's configuration the Beacon cannot write does not make the
    // Beacon itself any less installed.
    let _ = ssh.exec(&sh_c(OPENCODE_PLUGIN_INSTALL), &[]).await;
    Ok(BeaconInstall {
        version: release.version.clone(),
        linger,
        source,
        skills,
    })
}

/// Has the Host fetch `url` next to the final path. `Some(sha256)` of what
/// arrived, or `None` if the Host has no downloader or cannot reach `url`.
async fn host_download(ssh: &Ssh, url: &str) -> Result<Option<String>, CoreError> {
    let script = format!(
        r#"d="$HOME/.local/bin"; mkdir -p "$d" || exit 1; t="$d/.aries-beacon.new"; rm -f "$t"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL --connect-timeout 10 --max-time 300 -o "$t" "$1" 2>/dev/null
elif command -v wget >/dev/null 2>&1; then
  wget -q -T 30 -O "$t" "$1" 2>/dev/null
else
  false
fi || {{ rm -f "$t"; echo {NO_DOWNLOAD}; exit 0; }}
chmod 755 "$t" && {SUM}"#
    );
    let out = run(ssh, "download", &sh_c_args(&script, &[url]), &[]).await?;
    let line = last_line(&out.stdout);
    if line == NO_DOWNLOAD {
        return Ok(None);
    }
    Ok(Some(
        line.split_whitespace()
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase(),
    ))
}

/// The phone downloads `url`, checks it, uploads it, and the Host checks
/// what landed.
async fn phone_upload(
    ssh: &Ssh,
    url: &str,
    expected: &str,
    fetcher: std::sync::Arc<dyn BeaconFetcher>,
) -> Result<(), CoreError> {
    let url = url.to_owned();
    let bytes = tokio::task::spawn_blocking(move || fetcher.fetch(url))
        .await
        .map_err(|e| CoreError::BeaconDownload {
            detail: e.to_string(),
        })?
        .map_err(|e| CoreError::BeaconDownload {
            detail: e.to_string(),
        })?;
    let actual = sha256_hex(&bytes);
    if actual != expected {
        return Err(CoreError::BeaconChecksum {
            expected: expected.to_owned(),
            actual,
        });
    }
    let upload = format!(
        r#"set -e; d="$HOME/.local/bin"; mkdir -p "$d"; t="$d/.aries-beacon.new"; rm -f "$t"; cat > "$t"; chmod 755 "$t"; {SUM}"#
    );
    let out = run(ssh, "upload", &sh_c(&upload), &bytes).await?;
    let landed = last_line(&out.stdout);
    if landed.split_whitespace().next() != Some(expected) {
        let _ = ssh.exec(&sh_c(DISCARD_UPLOAD), &[]).await;
        return Err(step_failed(
            "upload",
            "the uploaded file's checksum differs",
        ));
    }
    Ok(())
}

async fn register_systemd(ssh: &Ssh) -> Result<Linger, CoreError> {
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
    run(ssh, "service", &sh_c(&service), &[]).await?;

    let linger = r#"u=$(id -un)
if [ -e "/var/lib/systemd/linger/$u" ]; then exit 0; fi
loginctl enable-linger "$u" 2>&1"#;
    let out = ssh.exec(&sh_c(linger), &[]).await?;
    Ok(if out.success() {
        Linger::Enabled
    } else {
        let mut reason = String::from_utf8_lossy(&out.stdout).trim().to_owned();
        if reason.is_empty() {
            reason = format!("loginctl exited with {:?}", out.status);
        }
        Linger::Failed { reason }
    })
}

/// Writes the LaunchAgent and loads it. Over SSH the `gui/<uid>` domain
/// exists only while the user is logged in at the Mac; otherwise the agent
/// goes into the `user/<uid>` domain. A reload right after `bootout` can
/// fail briefly, hence the retries.
async fn register_launch_agent(ssh: &Ssh) -> Result<(), CoreError> {
    let plist = launch_agent();
    let script = format!(
        r#"set -e
d="$HOME/.local/bin"
mv -f "$d/.aries-beacon.new" "$d/aries-beacon"
mkdir -p "$HOME/Library/LaunchAgents" "$HOME/.local/state/aries-beacon"
l={LAUNCHD_LABEL}
p="$HOME/Library/LaunchAgents/$l.plist"
cat > "$p" <<ARIES_PLIST
{plist}ARIES_PLIST
uid=$(id -u)
launchctl bootout "gui/$uid/$l" >/dev/null 2>&1 || true
launchctl bootout "user/$uid/$l" >/dev/null 2>&1 || true
i=0
while [ $i -lt 5 ]; do
  for dom in "gui/$uid" "user/$uid"; do
    if launchctl bootstrap "$dom" "$p" 2>/dev/null; then
      launchctl kickstart -k "$dom/$l" >/dev/null 2>&1 || true
      exit 0
    fi
  done
  i=$((i + 1))
  sleep 1
done
launchctl bootstrap "user/$uid" "$p""#
    );
    run(ssh, "service", &sh_c(&script), &[]).await?;
    Ok(())
}

pub(crate) async fn status(ssh: &Ssh) -> Result<BeaconState, CoreError> {
    let out = run(ssh, "status", &sh_c(STATUS), &[]).await?;
    let text = String::from_utf8_lossy(&out.stdout);
    // The last two lines: a login shell may print a banner first.
    let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty()).rev();
    let line = lines.next().unwrap_or_default();
    let (os, _) = normalize(lines.next().unwrap_or_default(), "");
    if line == "not-installed" {
        return Ok(BeaconState::NotInstalled);
    }
    let s: BeaconStatus = serde_json::from_str(line).map_err(|e| CoreError::Protocol {
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
        os,
        skills: s.skills.map(skill_roots),
    })
}

/// Reads the Beacon and, if it is out of date, updates it when `auto` is
/// on or reports that it needs one.
pub(crate) async fn check(
    ssh: &Ssh,
    release: &BeaconRelease,
    auto: bool,
    fetcher: std::sync::Arc<dyn BeaconFetcher>,
) -> Result<BeaconCheck, CoreError> {
    let state = status(ssh).await?;
    if !needs_update(&state, &release.version) {
        return Ok(BeaconCheck {
            state,
            update: BeaconUpdate::NotNeeded,
        });
    }
    if !auto {
        return Ok(BeaconCheck {
            state,
            update: BeaconUpdate::Needed,
        });
    }
    let BeaconState::Installed { version: from, .. } = state else {
        unreachable!("needs_update is false when not installed")
    };
    let install = install(ssh, release, fetcher).await?;
    Ok(BeaconCheck {
        state: status(ssh).await?,
        update: BeaconUpdate::Updated { from, install },
    })
}

pub(crate) async fn uninstall(ssh: &Ssh) -> Result<(), CoreError> {
    for (name, script) in UNINSTALL_STEPS {
        run(ssh, name, &sh_c(script), &[]).await?;
    }
    Ok(())
}

/// `uname -s` / `uname -m`, normalised to `linux`/`macos` and
/// `x86_64`/`aarch64`.
async fn detect(ssh: &Ssh) -> Result<(String, String), CoreError> {
    let out = run(ssh, "detect", &sh_c("uname -s; uname -m"), &[]).await?;
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

/// Runs `command` (already wrapped with [`sh_c`]) and fails the step on a
/// non-zero exit.
async fn run(ssh: &Ssh, step: &str, command: &str, stdin: &[u8]) -> Result<ExecOutput, CoreError> {
    let out = ssh.exec(command, stdin).await?;
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

fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

/// Runs `script` under `sh` whatever the user's login shell is.
pub(crate) fn sh_c(script: &str) -> String {
    format!("sh -c {}", quote(script))
}

/// [`sh_c`] with positional arguments (`$1`, ...), each quoted.
fn sh_c_args(script: &str, args: &[&str]) -> String {
    let mut cmd = format!("{} aries", sh_c(script));
    for a in args {
        cmd.push(' ');
        cmd.push_str(&quote(a));
    }
    cmd
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

    /// The removal step on this machine's `sh`, with a scratch `HOME`.
    #[test]
    fn removes_only_an_untouched_managed_pi_extension() {
        let marker = "// @aries-managed-pi-extension sha256=";
        let managed = |body: &str| format!("{marker}{}\n{body}", sha256_hex(body.as_bytes()));
        let remove = |home: &std::path::Path, agent_dir: Option<&str>| {
            let mut cmd = std::process::Command::new("sh");
            cmd.arg("-c").arg(PI_EXTENSION_REMOVE).env_clear();
            cmd.env("HOME", home).env("PATH", "/usr/bin:/bin");
            if let Some(d) = agent_dir {
                cmd.env("PI_CODING_AGENT_DIR", d);
            }
            assert!(cmd.status().unwrap().success());
        };
        let home = tempfile::tempdir().unwrap();
        let ext = home.path().join(".pi/agent/extensions");
        let file = ext.join("aries-status.ts");
        std::fs::create_dir_all(&ext).unwrap();

        // Untouched: removed with the dirs it leaves empty.
        std::fs::write(&file, managed("export default () => {};\n")).unwrap();
        remove(home.path(), None);
        assert!(!home.path().join(".pi").exists());

        // Edited, or not ours: kept.
        std::fs::create_dir_all(&ext).unwrap();
        let edited = format!("{}// mine\n", managed("x\n"));
        std::fs::write(&file, &edited).unwrap();
        remove(home.path(), None);
        assert_eq!(std::fs::read_to_string(&file).unwrap(), edited);
        std::fs::write(&file, "x\n").unwrap();
        remove(home.path(), None);
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "x\n");

        // `PI_CODING_AGENT_DIR` with a tilde; other files in the dir stay.
        let custom = home.path().join("cfg/extensions");
        std::fs::create_dir_all(&custom).unwrap();
        std::fs::write(custom.join("aries-status.ts"), managed("y\n")).unwrap();
        std::fs::write(custom.join("other.ts"), "z").unwrap();
        remove(home.path(), Some("~/cfg"));
        assert!(!custom.join("aries-status.ts").exists());
        assert!(custom.join("other.ts").exists());
    }

    #[test]
    fn hashes_like_sha256sum() {
        // `printf abc | sha256sum`
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    /// With `ARIES_BEACON_MANIFEST` set at build time (the release workflow
    /// runs this), the app pins exactly that manifest; without it, nothing.
    #[test]
    fn pins_the_manifest_the_release_pipeline_built() {
        let pinned = pinned_release();
        assert_eq!(pinned.version, env!("CARGO_PKG_VERSION"));
        match option_env!("ARIES_BEACON_MANIFEST").filter(|p| !p.is_empty()) {
            Some(path) => {
                let text = std::fs::read_to_string(path).unwrap();
                let built: BeaconRelease = serde_json::from_str(&text).unwrap();
                assert_eq!(pinned, built);
                assert!(!pinned.artifacts.is_empty());
                if std::env::var_os("ARIES_BEACON_EXPECT_ALL").is_some() {
                    let mut targets: Vec<_> = pinned
                        .artifacts
                        .iter()
                        .map(|a| format!("{}-{}", a.os, a.arch))
                        .collect();
                    targets.sort();
                    assert_eq!(
                        targets,
                        [
                            "linux-aarch64",
                            "linux-x86_64",
                            "macos-aarch64",
                            "macos-x86_64"
                        ]
                    );
                }
            }
            None => assert!(pinned.artifacts.is_empty()),
        }
    }

    fn installed(version: &str, supported: bool) -> BeaconState {
        BeaconState::Installed {
            version: version.into(),
            contract: 1,
            running: true,
            core_version: None,
            linger: true,
            supported,
            allowed: ALLOWED_BEACON.to_string(),
            os: "linux".into(),
            skills: None,
        }
    }

    #[test]
    fn skill_reports_preserve_paths_and_every_outcome_after_a_login_banner() {
        let outcomes = [
            ("installed", BeaconSkillOutcome::Installed),
            ("updated", BeaconSkillOutcome::Updated),
            ("unchanged", BeaconSkillOutcome::Unchanged),
            ("removed", BeaconSkillOutcome::Removed),
            ("absent", BeaconSkillOutcome::Absent),
            ("kept_modified", BeaconSkillOutcome::KeptModified),
            ("kept_foreign", BeaconSkillOutcome::KeptForeign),
            ("kept_symlink", BeaconSkillOutcome::KeptSymlink),
        ];
        let roots: Vec<_> = outcomes
            .iter()
            .map(|(outcome, _)| {
                serde_json::json!({
                    "path": "/home/u/.agents/skills/aries-call-human", "outcome": outcome,
                })
            })
            .collect();
        let stdout = format!(
            "Welcome to the Host\n{}\n",
            serde_json::json!({ "roots": roots })
        );
        let parsed = parse_skill_report(stdout.as_bytes()).unwrap();
        assert_eq!(parsed.len(), outcomes.len());
        for (root, (_, expected)) in parsed.iter().zip(outcomes) {
            assert_eq!(root.path, "/home/u/.agents/skills/aries-call-human");
            assert_eq!(root.outcome, expected);
        }
    }

    #[test]
    fn empty_or_malformed_skill_output_is_not_a_quiet_success() {
        for stdout in [
            "",
            "Welcome\n",
            "{}",
            r#"{"roots":[{"path":"p","outcome":"typo"}]}"#,
        ] {
            assert!(
                matches!(parse_skill_report(stdout.as_bytes()), Err(CoreError::BeaconStep { step, .. }) if step == "skill")
            );
        }
    }

    #[test]
    fn updates_unsupported_or_older_beacons_only() {
        assert!(!needs_update(&BeaconState::NotInstalled, "0.1.2"));
        assert!(!needs_update(&installed("0.1.2", true), "0.1.2"));
        assert!(needs_update(&installed("0.1.1", true), "0.1.2"));
        assert!(needs_update(&installed("0.0.9", false), "0.1.2"));
        // Outside the range, even if newer: the app cannot talk to it.
        assert!(needs_update(&installed("9.0.0", false), "0.1.2"));
        // Newer but still accepted: leave it.
        assert!(!needs_update(&installed("0.1.3", true), "0.1.2"));
    }

    #[test]
    fn the_launch_agent_keeps_the_beacon_serving() {
        let plist = launch_agent();
        assert!(
            plist.contains("<key>Label</key>\n  <string>io.github.shiroel.aries.beacon</string>")
        );
        assert!(plist.contains(
            "<string>$HOME/.local/bin/aries-beacon</string>\n    <string>serve</string>"
        ));
        assert!(plist.contains("<key>KeepAlive</key>\n  <true/>"));
        assert!(plist.contains("<key>RunAtLoad</key>\n  <true/>"));
    }

    #[test]
    fn quotes_arguments_for_sh() {
        assert_eq!(
            sh_c_args("echo \"$1\"", &["it's"]),
            r#"sh -c 'echo "$1"' aries 'it'\''s'"#
        );
    }
}
