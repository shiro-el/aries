//! The managed Pi extension: installed, updated, left alone once the user
//! owns it, and removed. Always in a scratch Pi config dir.

use std::path::Path;
use std::process::Command;

use aries_beacon::pi_extension::{MARKER, Outcome, SOURCE, install, managed, path_in, remove};

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

fn read(dir: &Path) -> String {
    std::fs::read_to_string(path_in(dir)).unwrap()
}

#[test]
fn installs_updates_and_removes_its_own_file() {
    let dir = tempfile::tempdir().unwrap();
    let agent = dir.path().join("agent");

    assert_eq!(install(&agent, "v1();\n").unwrap(), Outcome::Installed);
    let text = read(&agent);
    assert!(text.starts_with(MARKER), "{text}");
    assert!(text.ends_with("\nv1();\n"));
    assert_eq!(install(&agent, "v1();\n").unwrap(), Outcome::UpToDate);

    // A newer Beacon replaces an untouched copy.
    assert_eq!(install(&agent, "v2();\n").unwrap(), Outcome::Updated);
    assert_eq!(read(&agent), managed("v2();\n"));
    // No temp file is left next to it.
    let names: Vec<_> = std::fs::read_dir(agent.join("extensions"))
        .unwrap()
        .map(|e| e.unwrap().file_name())
        .collect();
    assert_eq!(names, ["aries-status.ts"]);

    assert_eq!(remove(&agent).unwrap(), Outcome::Removed);
    assert!(!path_in(&agent).exists());
    assert_eq!(remove(&agent).unwrap(), Outcome::Absent);
}

#[test]
fn keeps_a_copy_the_user_edited() {
    let dir = tempfile::tempdir().unwrap();
    let agent = dir.path();
    install(agent, "v1();\n").unwrap();
    let edited = format!("{}// my tweak\n", read(agent));
    std::fs::write(path_in(agent), &edited).unwrap();

    assert_eq!(install(agent, "v2();\n").unwrap(), Outcome::Modified);
    assert_eq!(remove(agent).unwrap(), Outcome::Modified);
    assert_eq!(read(agent), edited);
}

#[test]
fn keeps_a_file_it_did_not_write() {
    let dir = tempfile::tempdir().unwrap();
    let agent = dir.path();
    std::fs::create_dir_all(agent.join("extensions")).unwrap();
    std::fs::write(path_in(agent), "export default () => {};\n").unwrap();

    assert_eq!(install(agent, SOURCE).unwrap(), Outcome::Foreign);
    assert_eq!(remove(agent).unwrap(), Outcome::Foreign);
    assert_eq!(read(agent), "export default () => {};\n");
}

#[test]
fn the_shipped_extension_reports_status_and_pairs_only_inside_herdr_core() {
    assert!(SOURCE.contains("process.env.HC_TERMINAL_ID"));
    assert!(SOURCE.contains("if (!terminal) return;"));
    assert!(SOURCE.contains("]7501;"));
    assert!(SOURCE.contains("aries.pi.file="));
    assert!(SOURCE.contains("aries.pi.session="));
    // MVP: no question tool and no permission gate (Pi's behaviour stays).
    assert!(!SOURCE.contains("registerTool"));
    assert!(!SOURCE.contains("tool_call"));
}

#[test]
fn the_command_uses_pi_agent_dir_and_expands_the_tilde() {
    let home = tempfile::tempdir().unwrap();
    let run = |args: &[&str], agent_dir: Option<&str>| {
        let mut cmd = Command::new(BEACON);
        cmd.arg("pi-extension").args(args).env_clear();
        cmd.env("HOME", home.path());
        if let Some(d) = agent_dir {
            cmd.env("PI_CODING_AGENT_DIR", d);
        }
        let out = cmd.output().unwrap();
        assert!(out.status.success(), "{out:?}");
        String::from_utf8(out.stdout).unwrap()
    };

    let default = home.path().join(".pi/agent/extensions/aries-status.ts");
    assert_eq!(
        run(&["install"], None),
        format!("installed {}\n", default.display())
    );
    assert_eq!(std::fs::read_to_string(&default).unwrap(), managed(SOURCE));

    let custom = home.path().join("pi-cfg/extensions/aries-status.ts");
    assert!(run(&["install"], Some("~/pi-cfg")).starts_with("installed "));
    assert!(custom.exists());
    assert!(run(&["remove"], Some("~/pi-cfg")).starts_with("removed "));
    assert!(!custom.exists());
    assert!(run(&["remove"], None).starts_with("removed "));
    assert!(!default.exists());
}
