//! What the Beacon reports about itself, and the version rule the app uses
//! to decide whether it can work with an installed Beacon.

use serde::{Deserialize, Serialize};

/// One JSON line printed by `aries-beacon status`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BeaconStatus {
    /// Beacon release, `MAJOR.MINOR.PATCH`.
    pub version: String,
    /// The [`crate::CONTRACT_VERSION`] this Beacon was built with.
    pub contract: u32,
    /// The resident service (`aries-beacon serve`) is running.
    pub running: bool,
    /// herdr-core version of the local core the service is attached to;
    /// `None` while it is not attached.
    pub core_version: Option<String>,
    /// systemd keeps the user's services running without a login.
    pub linger: bool,
    /// Absent on older Beacons or when the skills could not be inspected.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub skills: Option<SkillReport>,
}

/// Half-open range of Beacon releases `[minimum, limit)`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VersionRange {
    pub minimum: &'static str,
    pub limit: &'static str,
}

impl VersionRange {
    pub const fn new(minimum: &'static str, limit: &'static str) -> Self {
        Self { minimum, limit }
    }

    /// `version` is a plain `MAJOR.MINOR.PATCH` within the range.
    pub fn contains(&self, version: &str) -> bool {
        match (parse(version), parse(self.minimum), parse(self.limit)) {
            (Some(v), Some(min), Some(limit)) => min <= v && v < limit,
            _ => false,
        }
    }
}

impl std::fmt::Display for VersionRange {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, ">={}, <{}", self.minimum, self.limit)
    }
}

/// A plain `MAJOR.MINOR.PATCH` as comparable numbers.
pub fn parse_version(version: &str) -> Option<[u64; 3]> {
    parse(version)
}

fn parse(version: &str) -> Option<[u64; 3]> {
    let mut parts = version.trim().split('.').map(|p| p.parse::<u64>().ok());
    let v = [parts.next()??, parts.next()??, parts.next()??];
    parts.next().is_none().then_some(v)
}

/// One JSON line printed by `aries-beacon skill install` and
/// `aries-beacon skill uninstall`: what happened to the managed
/// `aries-call-human` skill in each skills directory.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SkillReport {
    /// Current `aries-call-human` paths; these determine readiness.
    pub roots: Vec<SkillRoot>,
    /// Old `call-human` paths: cleanup outcomes (or inspection on status).
    /// Kept copies do not conflict with the current-name skill.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub legacy_roots: Vec<SkillRoot>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SkillRoot {
    /// The skill directory, e.g. `/home/u/.claude/skills/aries-call-human`.
    pub path: String,
    pub outcome: SkillOutcome,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SkillOutcome {
    /// Install: there was none; the Beacon's skill is there now.
    Installed,
    /// Install: an older managed copy was replaced.
    Updated,
    /// Install: the managed copy is already current.
    Unchanged,
    /// Uninstall: the managed files are gone (the user's own files stay).
    Removed,
    /// Uninstall: there was no skill.
    Absent,
    /// A managed file was edited, removed or replaced by the user: the
    /// skill is left as it is.
    KeptModified,
    /// A `aries-call-human` skill the Beacon did not install: left alone.
    KeptForeign,
    /// The skill directory is a symlink: left alone.
    KeptSymlink,
}
