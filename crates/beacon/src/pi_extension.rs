//! The Pi extension the Beacon manages: Pi reports no OSC 7501 status and
//! names no session file on its own, so `aries-status.ts` (in Pi's global
//! extension dir) does both. Facts: `docs/research/pi-integration.md`.
//!
//! The installed file starts with a marker line holding the SHA-256 of the
//! rest. A file with the marker and a matching hash is ours, untouched: it
//! is updated and removed freely. Anything else at that path (no marker, or
//! the user edited it) is left alone.
//!
//! `aries-beacon pi-extension install|remove` runs this on the Host; the
//! phone runs `install` after installing or updating the Beacon. Removal
//! is a shell step of Beacon removal (`aries_core`'s `UNINSTALL_STEPS`)
//! that applies the same check.

use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

/// What the Beacon ships.
pub const SOURCE: &str = include_str!("pi/aries-status.ts");

pub const FILE_NAME: &str = "aries-status.ts";

/// First-line prefix; the hash of the rest of the file follows it.
pub const MARKER: &str = "// @aries-managed-pi-extension sha256=";

/// Pi's config dir: `$PI_CODING_AGENT_DIR` (with `~` expanded) or
/// `~/.pi/agent`, as Pi resolves it.
pub fn agent_dir() -> PathBuf {
    match std::env::var("PI_CODING_AGENT_DIR") {
        Ok(d) if !d.is_empty() => {
            if d == "~" {
                crate::home()
            } else if let Some(rest) = d.strip_prefix("~/") {
                crate::home().join(rest)
            } else {
                PathBuf::from(d)
            }
        }
        _ => crate::home().join(".pi/agent"),
    }
}

pub fn path_in(agent_dir: &Path) -> PathBuf {
    agent_dir.join("extensions").join(FILE_NAME)
}

/// The whole installed file for `source`.
pub fn managed(source: &str) -> String {
    format!("{MARKER}{}\n{source}", sha256_hex(source.as_bytes()))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Installed,
    Updated,
    UpToDate,
    /// The user edited our file: kept as is.
    Modified,
    /// A file we did not write is there: kept as is.
    Foreign,
    Removed,
    Absent,
}

impl Outcome {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Installed => "installed",
            Self::Updated => "updated",
            Self::UpToDate => "up-to-date",
            Self::Modified => "modified",
            Self::Foreign => "foreign",
            Self::Removed => "removed",
            Self::Absent => "absent",
        }
    }
}

enum Found {
    Absent,
    Ours { body: String },
    Modified,
    Foreign,
}

fn inspect(path: &Path) -> std::io::Result<Found> {
    let text = match std::fs::read(path) {
        Ok(b) => b,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Found::Absent),
        Err(e) => return Err(e),
    };
    let Some(nl) = text.iter().position(|b| *b == b'\n') else {
        return Ok(Found::Foreign);
    };
    let first = String::from_utf8_lossy(&text[..nl]);
    let Some(want) = first.strip_prefix(MARKER) else {
        return Ok(Found::Foreign);
    };
    let rest = &text[nl + 1..];
    if want.trim() != sha256_hex(rest) {
        return Ok(Found::Modified);
    }
    Ok(Found::Ours {
        body: String::from_utf8_lossy(rest).into_owned(),
    })
}

/// Puts `source` in place unless the user owns the file there.
pub fn install(agent_dir: &Path, source: &str) -> std::io::Result<Outcome> {
    let path = path_in(agent_dir);
    let outcome = match inspect(&path)? {
        Found::Absent => Outcome::Installed,
        Found::Ours { body } if body == source => return Ok(Outcome::UpToDate),
        Found::Ours { .. } => Outcome::Updated,
        Found::Modified => return Ok(Outcome::Modified),
        Found::Foreign => return Ok(Outcome::Foreign),
    };
    let dir = path.parent().expect("extensions dir");
    std::fs::create_dir_all(dir)?;
    // Pi may load the file at any moment: never let it see half of it.
    let tmp = dir.join(format!(".{FILE_NAME}.aries-new"));
    std::fs::write(&tmp, managed(source))?;
    std::fs::rename(&tmp, &path)?;
    Ok(outcome)
}

/// Deletes our untouched file; leaves an edited or foreign one.
pub fn remove(agent_dir: &Path) -> std::io::Result<Outcome> {
    let path = path_in(agent_dir);
    Ok(match inspect(&path)? {
        Found::Absent => Outcome::Absent,
        Found::Ours { .. } => {
            std::fs::remove_file(&path)?;
            Outcome::Removed
        }
        Found::Modified => Outcome::Modified,
        Found::Foreign => Outcome::Foreign,
    })
}

/// `aries-beacon pi-extension install|remove`: prints the outcome.
pub fn command(args: &[String]) -> i32 {
    let dir = agent_dir();
    let result = match args.first().map(String::as_str) {
        Some("install") => install(&dir, SOURCE),
        Some("remove") => remove(&dir),
        _ => {
            eprintln!("usage: aries-beacon pi-extension install|remove");
            return 2;
        }
    };
    match result {
        Ok(outcome) => {
            println!("{} {}", outcome.as_str(), path_in(&dir).display());
            0
        }
        Err(e) => {
            eprintln!("aries-beacon: pi extension: {e}");
            1
        }
    }
}

fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}
