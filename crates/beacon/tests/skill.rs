//! `aries-beacon skill install` / `skill uninstall` as a black box, with a
//! temporary `HOME`: the managed `call-human` skill in `~/.claude/skills`
//! and `~/.agents/skills`, and what the user changed there left alone.

use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};

use aries_protocol::beacon::{SkillOutcome, SkillReport};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");
/// The skill this Beacon ships: an independent copy of the source file.
const SHIPPED: &str = include_str!("../skill/call-human/SKILL.md");

struct Home {
    dir: tempfile::TempDir,
}

impl Home {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
        }
    }

    fn path(&self) -> &Path {
        self.dir.path()
    }

    fn claude(&self) -> PathBuf {
        self.path().join(".claude/skills/call-human")
    }

    fn agents(&self) -> PathBuf {
        self.path().join(".agents/skills/call-human")
    }

    fn command(&self, args: &[&str]) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.args(args)
            .env_clear()
            .env("HOME", self.path())
            .env("PATH", "/usr/bin:/bin");
        cmd
    }

    fn run(&self, args: &[&str]) -> SkillReport {
        let out = self.command(args).output().unwrap();
        report(&out)
    }

    fn install(&self) -> SkillReport {
        self.run(&["skill", "install"])
    }

    /// Installs the skill files in `source` instead of the shipped ones:
    /// plays an older Beacon release.
    fn install_from(&self, source: &Path) -> SkillReport {
        let out = self
            .command(&["skill", "install"])
            .env("ARIES_BEACON_SKILL_SOURCE", source)
            .output()
            .unwrap();
        report(&out)
    }

    fn uninstall(&self) -> SkillReport {
        self.run(&["skill", "uninstall"])
    }
}

fn report(out: &Output) -> SkillReport {
    assert!(
        out.status.success(),
        "aries-beacon skill failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    let text = String::from_utf8(out.stdout.clone()).unwrap();
    serde_json::from_str(text.trim()).unwrap_or_else(|e| panic!("{e}: {text}"))
}

fn outcomes(r: &SkillReport) -> Vec<(String, SkillOutcome)> {
    r.roots
        .iter()
        .map(|x| (x.path.clone(), x.outcome))
        .collect()
}

fn outcome_of(r: &SkillReport, dir: &Path) -> SkillOutcome {
    let path = dir.display().to_string();
    r.roots
        .iter()
        .find(|x| x.path == path)
        .unwrap_or_else(|| panic!("no {path} in {r:?}"))
        .outcome
}

fn read(path: PathBuf) -> String {
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// An older release's skill: different text and one more file.
fn old_release() -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("SKILL.md"), "old instructions\n").unwrap();
    std::fs::write(dir.path().join("EXAMPLES.md"), "old examples\n").unwrap();
    dir
}

#[test]
fn installs_the_skill_for_claude_code_and_other_agents() {
    let home = Home::new();
    let first = home.install();
    assert_eq!(
        outcomes(&first),
        [
            (home.claude().display().to_string(), SkillOutcome::Installed),
            (home.agents().display().to_string(), SkillOutcome::Installed),
        ]
    );
    for dir in [home.claude(), home.agents()] {
        assert_eq!(read(dir.join("SKILL.md")), SHIPPED);
    }
    assert!(SHIPPED.contains("name: call-human"));
    assert!(SHIPPED.contains("aries-beacon call"));

    let again = home.install();
    assert_eq!(outcome_of(&again, &home.claude()), SkillOutcome::Unchanged);
    assert_eq!(outcome_of(&again, &home.agents()), SkillOutcome::Unchanged);
}

#[test]
fn an_update_replaces_a_managed_skill_the_user_did_not_touch() {
    let home = Home::new();
    let old = old_release();
    home.install_from(old.path());
    assert_eq!(read(home.claude().join("SKILL.md")), "old instructions\n");

    let r = home.install();
    assert_eq!(outcome_of(&r, &home.claude()), SkillOutcome::Updated);
    assert_eq!(outcome_of(&r, &home.agents()), SkillOutcome::Updated);
    for dir in [home.claude(), home.agents()] {
        assert_eq!(read(dir.join("SKILL.md")), SHIPPED);
        // The old release's file is no longer part of the skill.
        assert!(!dir.join("EXAMPLES.md").exists());
    }
}

#[test]
fn keeps_what_the_user_changed_added_or_linked() {
    let home = Home::new();
    let old = old_release();
    home.install_from(old.path());

    // Claude's copy edited by the user; the other one extended with a file.
    std::fs::write(home.claude().join("SKILL.md"), "my own rules\n").unwrap();
    std::fs::write(home.agents().join("NOTES.md"), "my notes\n").unwrap();

    let r = home.install();
    assert_eq!(outcome_of(&r, &home.claude()), SkillOutcome::KeptModified);
    assert_eq!(read(home.claude().join("SKILL.md")), "my own rules\n");
    assert_eq!(read(home.claude().join("EXAMPLES.md")), "old examples\n");
    // Updating the managed files keeps the user's own file.
    assert_eq!(outcome_of(&r, &home.agents()), SkillOutcome::Updated);
    assert_eq!(read(home.agents().join("SKILL.md")), SHIPPED);
    assert_eq!(read(home.agents().join("NOTES.md")), "my notes\n");

    // A managed file replaced by a symlink counts as the user's change.
    let elsewhere = home.path().join("elsewhere.md");
    std::fs::write(&elsewhere, SHIPPED).unwrap();
    std::fs::remove_file(home.agents().join("SKILL.md")).unwrap();
    std::os::unix::fs::symlink(&elsewhere, home.agents().join("SKILL.md")).unwrap();
    let r = home.install();
    assert_eq!(outcome_of(&r, &home.agents()), SkillOutcome::KeptModified);
    assert!(
        std::fs::symlink_metadata(home.agents().join("SKILL.md"))
            .unwrap()
            .file_type()
            .is_symlink()
    );
}

#[test]
fn leaves_a_linked_or_foreign_call_human_skill_alone() {
    let home = Home::new();
    // Claude's skill links to the shared one (a common dotfiles setup) and
    // the shared one is another tool's skill, not the Beacon's.
    std::fs::create_dir_all(home.agents()).unwrap();
    std::fs::write(home.agents().join("SKILL.md"), "another tool's skill\n").unwrap();
    std::fs::create_dir_all(home.claude().parent().unwrap()).unwrap();
    std::os::unix::fs::symlink("../../.agents/skills/call-human", home.claude()).unwrap();

    let r = home.install();
    assert_eq!(outcome_of(&r, &home.claude()), SkillOutcome::KeptSymlink);
    assert_eq!(outcome_of(&r, &home.agents()), SkillOutcome::KeptForeign);
    assert_eq!(
        read(home.agents().join("SKILL.md")),
        "another tool's skill\n"
    );
    assert_eq!(
        std::fs::read_dir(home.agents()).unwrap().count(),
        1,
        "nothing added next to a foreign skill"
    );

    let r = home.uninstall();
    assert_eq!(outcome_of(&r, &home.claude()), SkillOutcome::KeptSymlink);
    assert_eq!(outcome_of(&r, &home.agents()), SkillOutcome::KeptForeign);
    assert_eq!(
        read(home.claude().join("SKILL.md")),
        "another tool's skill\n"
    );
}

#[test]
fn uninstall_removes_the_managed_skill_and_keeps_the_users_version() {
    let home = Home::new();
    home.install();
    std::fs::write(home.claude().join("SKILL.md"), "my own rules\n").unwrap();
    std::fs::write(home.agents().join("NOTES.md"), "my notes\n").unwrap();

    let r = home.uninstall();
    assert_eq!(outcome_of(&r, &home.claude()), SkillOutcome::KeptModified);
    assert_eq!(read(home.claude().join("SKILL.md")), "my own rules\n");
    assert_eq!(outcome_of(&r, &home.agents()), SkillOutcome::Removed);
    assert!(!home.agents().join("SKILL.md").exists());
    assert_eq!(read(home.agents().join("NOTES.md")), "my notes\n");

    // The kept copy is the user's now: a new install does not take it back.
    let r = home.install();
    assert_eq!(outcome_of(&r, &home.claude()), SkillOutcome::KeptForeign);
    assert_eq!(read(home.claude().join("SKILL.md")), "my own rules\n");

    // Nothing left of a skill nobody touched.
    let clean = Home::new();
    clean.install();
    let r = clean.uninstall();
    assert_eq!(outcome_of(&r, &clean.claude()), SkillOutcome::Removed);
    assert!(!clean.claude().exists() && !clean.agents().exists());
    let r = clean.uninstall();
    assert_eq!(outcome_of(&r, &clean.claude()), SkillOutcome::Absent);
}

#[test]
fn concurrent_installs_end_with_one_good_copy() {
    race(&Home::new());
}

#[test]
fn concurrent_updates_end_with_one_good_copy() {
    let home = Home::new();
    let old = old_release();
    home.install_from(old.path());
    race(&home);
}

/// Eight Beacons install at once (two phones, a retry, ...).
fn race(home: &Home) {
    let children: Vec<_> = (0..8)
        .map(|_| {
            home.command(&["skill", "install"])
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap()
        })
        .collect();
    for child in children {
        let out = child.wait_with_output().unwrap();
        assert!(out.status.success());
    }
    for dir in [home.claude(), home.agents()] {
        assert_eq!(read(dir.join("SKILL.md")), SHIPPED);
        let mut names: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .filter(|n| !n.starts_with('.'))
            .collect();
        names.sort();
        assert_eq!(names, ["SKILL.md"], "no temporary or stale files");
    }
    // Every root still reads as unmodified after the race.
    let r = home.install();
    assert!(
        r.roots.iter().all(|x| x.outcome == SkillOutcome::Unchanged),
        "{r:?}"
    );
    // And no leftover temporary directories next to the skills.
    for dir in [home.claude(), home.agents()] {
        let parent = dir.parent().unwrap();
        assert_eq!(std::fs::read_dir(parent).unwrap().count(), 1);
    }
}

/// Status must surface the conflicts without installing anything or hiding
/// them behind a healthy resident service.
#[test]
fn status_reports_both_foreign_skills_without_changing_them() {
    let h = Home::new();
    for dir in [h.claude(), h.agents()] {
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("SKILL.md"), "another tools skill\n").unwrap();
    }
    for _ in 0..2 {
        let out = h.command(&["status"]).output().unwrap();
        assert!(out.status.success());
        let status: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
        let skills: SkillReport = serde_json::from_value(status["skills"].clone())
            .expect("status must report the preserved call-human skills");
        assert_eq!(skills.roots.len(), 2);
        for dir in [h.claude(), h.agents()] {
            assert_eq!(outcome_of(&skills, &dir), SkillOutcome::KeptForeign);
            assert_eq!(read(dir.join("SKILL.md")), "another tools skill\n");
            assert!(!dir.join(".aries-beacon.json").exists());
        }
        assert!(
            !h.path().join(".local/state").exists(),
            "status must be read-only"
        );
    }
    assert!(
        h.install()
            .roots
            .iter()
            .all(|r| r.outcome == SkillOutcome::KeptForeign)
    );
}

#[test]
fn status_reports_modified_and_symlink_skills_without_repairing_them() {
    use std::os::unix::fs::symlink;
    let h = Home::new();
    h.install();
    std::fs::write(h.claude().join("SKILL.md"), "my edited skill\n").unwrap();
    std::fs::remove_dir_all(h.agents()).unwrap();
    let target = h.path().join("foreign");
    std::fs::create_dir(&target).unwrap();
    std::fs::write(target.join("SKILL.md"), "linked skill\n").unwrap();
    symlink(&target, h.agents()).unwrap();

    let out = h.command(&["status"]).output().unwrap();
    assert!(out.status.success());
    let status: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    let skills: SkillReport = serde_json::from_value(status["skills"].clone()).unwrap();
    assert_eq!(outcome_of(&skills, &h.claude()), SkillOutcome::KeptModified);
    assert_eq!(outcome_of(&skills, &h.agents()), SkillOutcome::KeptSymlink);
    assert_eq!(read(h.claude().join("SKILL.md")), "my edited skill\n");
    assert_eq!(std::fs::read_link(h.agents()).unwrap(), target);
    assert_eq!(read(target.join("SKILL.md")), "linked skill\n");
}

#[test]
fn status_distinguishes_missing_and_untouched_managed_skills() {
    let h = Home::new();
    let status = || {
        let out = h.command(&["status"]).output().unwrap();
        assert!(out.status.success());
        serde_json::from_slice::<aries_protocol::beacon::BeaconStatus>(&out.stdout)
            .unwrap()
            .skills
            .unwrap()
    };
    assert!(
        status()
            .roots
            .iter()
            .all(|r| r.outcome == SkillOutcome::Absent)
    );
    assert!(!h.path().join(".local").exists());
    h.install();
    // User-added files do not make an untouched Aries skill a conflict.
    std::fs::write(h.claude().join("NOTES.md"), "notes\n").unwrap();
    assert!(
        status()
            .roots
            .iter()
            .all(|r| r.outcome == SkillOutcome::Unchanged)
    );
    assert_eq!(read(h.claude().join("NOTES.md")), "notes\n");
}
