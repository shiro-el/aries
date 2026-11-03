//! The managed `call-human` skill: `aries-beacon skill install` and
//! `aries-beacon skill uninstall`.
//!
//! The Beacon ships the skill ([`shipped`]) and puts it in every
//! [`roots`] directory as `<root>/call-human/`. Next to the files it writes
//! [`MARKER`], which records exactly what it installed. Only a skill with
//! that marker whose files still read as recorded is the Beacon's to
//! update or remove: a symlinked skill directory, a skill without the
//! marker (another tool's, or the user's own), and a skill with an edited,
//! removed or symlinked file are left as they are. Files the user added
//! next to a managed skill are never touched.
//!
//! Every run holds an exclusive lock on `skill.lock` in the Beacon's state
//! directory, files are replaced by rename, and a new skill directory is
//! built under a temporary name and renamed into place, so concurrent
//! installs and a crash midway leave either the old or the new skill.

use std::collections::BTreeMap;
use std::fs::OpenOptions;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};

use aries_protocol::beacon::{SkillOutcome, SkillReport, SkillRoot};
use serde::{Deserialize, Serialize};

/// The skill's directory name in each root.
pub const NAME: &str = "call-human";
/// What the Beacon installed, inside the skill directory.
pub const MARKER: &str = ".aries-beacon.json";
/// Files are written as `.<name>.aries-tmp` and renamed into place; a new
/// skill directory is built as `.call-human.aries-tmp`.
const TMP_SUFFIX: &str = ".aries-tmp";

/// Skill files by name (no subdirectories), with their contents.
pub type Files = BTreeMap<String, String>;

/// The skill this Beacon release installs.
pub fn shipped() -> Files {
    Files::from([(
        "SKILL.md".to_owned(),
        include_str!("../skill/call-human/SKILL.md").to_owned(),
    )])
}

/// Reads a skill from the regular files directly in `dir` (tests play an
/// older release with this).
pub fn files_in(dir: &Path) -> io::Result<Files> {
    let mut files = Files::new();
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        if entry.file_type()?.is_file() {
            let name = entry
                .file_name()
                .into_string()
                .map_err(|n| io::Error::new(ErrorKind::InvalidData, format!("file name {n:?}")))?;
            files.insert(name, std::fs::read_to_string(entry.path())?);
        }
    }
    Ok(files)
}

/// Claude Code's user skills, then the shared directory other Agents read.
pub fn roots(home: &Path) -> [PathBuf; 2] {
    [home.join(".claude/skills"), home.join(".agents/skills")]
}

#[derive(Debug, Serialize, Deserialize)]
struct Marker {
    /// Beacon release that wrote it.
    version: String,
    /// Exactly what it installed.
    files: Files,
}

/// Installs or updates `files` as the skill in every root.
pub fn install(home: &Path, state: &Path, files: &Files) -> io::Result<SkillReport> {
    locked(home, state, |dir| install_one(dir, files))
}

/// Removes the managed skill from every root, keeping the user's version.
pub fn uninstall(home: &Path, state: &Path) -> io::Result<SkillReport> {
    locked(home, state, uninstall_one)
}

fn locked(
    home: &Path,
    state: &Path,
    mut each: impl FnMut(&Path) -> io::Result<SkillOutcome>,
) -> io::Result<SkillReport> {
    create_private_dir(state)?;
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(state.join("skill.lock"))?;
    lock.lock()?;
    let mut report = Vec::new();
    for root in roots(home) {
        let dir = root.join(NAME);
        let outcome = each(&dir).map_err(|e| with_path(e, &dir))?;
        report.push(SkillRoot {
            path: dir.display().to_string(),
            outcome,
        });
    }
    Ok(SkillReport { roots: report })
}

/// What is at `dir`, before deciding whether it is the Beacon's.
enum Found {
    Nothing,
    Symlink,
    Foreign,
    Managed(Marker),
}

fn look(dir: &Path) -> io::Result<Found> {
    let meta = match std::fs::symlink_metadata(dir) {
        Ok(m) => m,
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(Found::Nothing),
        Err(e) => return Err(e),
    };
    if meta.file_type().is_symlink() {
        return Ok(Found::Symlink);
    }
    if !meta.is_dir() {
        return Ok(Found::Foreign);
    }
    // A marker that is missing, not a plain file, or unreadable means the
    // Beacon cannot tell what it owns here.
    let marker = dir.join(MARKER);
    match read_regular(&marker)? {
        Some(text) => Ok(serde_json::from_str(&text).map_or(Found::Foreign, Found::Managed)),
        None => Ok(Found::Foreign),
    }
}

fn install_one(dir: &Path, files: &Files) -> io::Result<SkillOutcome> {
    let marker = match look(dir)? {
        Found::Nothing => {
            install_fresh(dir, files)?;
            return Ok(SkillOutcome::Installed);
        }
        Found::Symlink => return Ok(SkillOutcome::KeptSymlink),
        Found::Foreign => return Ok(SkillOutcome::KeptForeign),
        Found::Managed(m) => m,
    };

    // Each recorded file must still read as recorded. A crash during an
    // update may have left some files already at the new contents, which
    // also counts as untouched; a stale file already removed is fine.
    let mut current = true;
    for (name, recorded) in &marker.files {
        let wanted = files.get(name);
        match read_regular_or_link(&dir.join(name))? {
            OnDisk::Missing if wanted.is_none() => {}
            OnDisk::Text(t) if &t == recorded || Some(&t) == wanted => {
                current &= Some(&t) == wanted;
            }
            _ => return Ok(SkillOutcome::KeptModified),
        }
    }
    // A file this release adds may already be there as the user's own.
    for (name, content) in files {
        if marker.files.contains_key(name) {
            continue;
        }
        match read_regular_or_link(&dir.join(name))? {
            OnDisk::Missing => current = false,
            OnDisk::Text(t) if &t == content => {}
            _ => return Ok(SkillOutcome::KeptModified),
        }
    }
    if current && marker.files == *files {
        return Ok(SkillOutcome::Unchanged);
    }

    for (name, content) in files {
        if std::fs::read_to_string(dir.join(name)).ok().as_ref() != Some(content) {
            write_replacing(dir, name, content)?;
        }
    }
    for name in marker.files.keys().filter(|n| !files.contains_key(*n)) {
        remove_if_there(&dir.join(name))?;
    }
    write_replacing(dir, MARKER, &marker_json(files))?;
    Ok(SkillOutcome::Updated)
}

/// Builds the skill under a temporary name next to `dir`, then renames it
/// into place.
fn install_fresh(dir: &Path, files: &Files) -> io::Result<()> {
    let root = dir.parent().expect("skill dir has a parent");
    std::fs::create_dir_all(root)?;
    let tmp = root.join(format!(".{NAME}{TMP_SUFFIX}"));
    // Left over from a run that crashed (the lock is ours now).
    if std::fs::symlink_metadata(&tmp).is_ok() {
        std::fs::remove_dir_all(&tmp)?;
    }
    std::fs::create_dir(&tmp)?;
    let built = (|| {
        for (name, content) in files {
            std::fs::write(tmp.join(name), content)?;
        }
        std::fs::write(tmp.join(MARKER), marker_json(files))?;
        std::fs::rename(&tmp, dir)
    })();
    if built.is_err() {
        let _ = std::fs::remove_dir_all(&tmp);
    }
    built
}

fn uninstall_one(dir: &Path) -> io::Result<SkillOutcome> {
    let marker = match look(dir)? {
        Found::Nothing => return Ok(SkillOutcome::Absent),
        Found::Symlink => return Ok(SkillOutcome::KeptSymlink),
        Found::Foreign => return Ok(SkillOutcome::KeptForeign),
        Found::Managed(m) => m,
    };
    for (name, recorded) in &marker.files {
        match read_regular_or_link(&dir.join(name))? {
            OnDisk::Missing => {}
            OnDisk::Text(t) if &t == recorded => {}
            _ => {
                // The user's version now: it stays, and without the marker
                // a later install leaves it alone too.
                remove_if_there(&dir.join(MARKER))?;
                return Ok(SkillOutcome::KeptModified);
            }
        }
    }
    for name in marker.files.keys() {
        remove_if_there(&dir.join(name))?;
    }
    remove_if_there(&dir.join(MARKER))?;
    // Only if the user added nothing.
    let _ = std::fs::remove_dir(dir);
    Ok(SkillOutcome::Removed)
}

fn marker_json(files: &Files) -> String {
    let marker = Marker {
        version: env!("CARGO_PKG_VERSION").to_owned(),
        files: files.clone(),
    };
    serde_json::to_string_pretty(&marker).expect("marker json") + "\n"
}

enum OnDisk {
    Missing,
    /// A symlink, directory, or a file that is not UTF-8.
    Other,
    Text(String),
}

fn read_regular_or_link(path: &Path) -> io::Result<OnDisk> {
    match std::fs::symlink_metadata(path) {
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(OnDisk::Missing),
        Err(e) => return Err(e),
        Ok(m) if !m.file_type().is_file() => return Ok(OnDisk::Other),
        Ok(_) => {}
    }
    match std::fs::read(path) {
        Ok(bytes) => Ok(String::from_utf8(bytes).map_or(OnDisk::Other, OnDisk::Text)),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(OnDisk::Missing),
        Err(e) => Err(e),
    }
}

/// The text of a regular file; `None` if missing or anything else.
fn read_regular(path: &Path) -> io::Result<Option<String>> {
    Ok(match read_regular_or_link(path)? {
        OnDisk::Text(t) => Some(t),
        _ => None,
    })
}

fn write_replacing(dir: &Path, name: &str, content: &str) -> io::Result<()> {
    let tmp = dir.join(format!(".{name}{TMP_SUFFIX}"));
    std::fs::write(&tmp, content)?;
    std::fs::rename(&tmp, dir.join(name)).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })
}

fn remove_if_there(path: &Path) -> io::Result<()> {
    match std::fs::remove_file(path) {
        Err(e) if e.kind() != ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

fn create_private_dir(dir: &Path) -> io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(dir)
}

fn with_path(e: io::Error, path: &Path) -> io::Error {
    io::Error::new(e.kind(), format!("{}: {e}", path.display()))
}
