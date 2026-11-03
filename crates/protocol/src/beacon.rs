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

fn parse(version: &str) -> Option<[u64; 3]> {
    let mut parts = version.trim().split('.').map(|p| p.parse::<u64>().ok());
    let v = [parts.next()??, parts.next()??, parts.next()??];
    parts.next().is_none().then_some(v)
}
