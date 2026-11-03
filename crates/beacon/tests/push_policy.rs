//! `aries-beacon policy [set]`: the phone reads and changes the push policy
//! over SSH. The file it writes is the one `serve` and `call` obey
//! (`status_push.rs`, `call_push.rs`).

use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::{Command, Output, Stdio};

use aries_protocol::push_policy::PushPolicy;

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

fn beacon(state: &Path, args: &[&str], stdin: &str) -> Output {
    let mut child = Command::new(BEACON)
        .args(args)
        .env_clear()
        .env("HOME", state)
        .env("XDG_STATE_HOME", state)
        .env("PATH", "/usr/bin:/bin")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .take()
        .unwrap()
        .write_all(stdin.as_bytes())
        .unwrap();
    child.wait_with_output().unwrap()
}

fn printed(out: &Output) -> PushPolicy {
    assert!(out.status.success(), "{out:?}");
    serde_json::from_slice(&out.stdout).unwrap()
}

#[test]
fn prints_the_defaults_when_nothing_was_chosen() {
    let state = tempfile::tempdir().unwrap();
    let p = printed(&beacon(state.path(), &["policy"], ""));
    assert_eq!(
        p,
        PushPolicy {
            blocked: true,
            error: true,
            done: false,
            child_agents: false,
        }
    );
}

#[test]
fn set_saves_the_choice_privately_and_prints_it() {
    let state = tempfile::tempdir().unwrap();
    let chosen = PushPolicy {
        blocked: false,
        error: true,
        done: true,
        child_agents: true,
    };
    let json = serde_json::to_string(&chosen).unwrap();

    assert_eq!(
        printed(&beacon(state.path(), &["policy", "set"], &json)),
        chosen
    );
    assert_eq!(printed(&beacon(state.path(), &["policy"], "")), chosen);

    let file = state.path().join("aries-beacon/push-policy.json");
    let mode = std::fs::metadata(&file).unwrap().permissions().mode();
    assert_eq!(mode & 0o777, 0o600);
    let leftovers: Vec<_> = std::fs::read_dir(state.path().join("aries-beacon"))
        .unwrap()
        .map(|e| e.unwrap().file_name())
        .filter(|n| n != "push-policy.json")
        .collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");
}

#[test]
fn set_fills_missing_options_with_the_defaults() {
    let state = tempfile::tempdir().unwrap();
    let p = printed(&beacon(
        state.path(),
        &["policy", "set"],
        r#"{"done":true}"#,
    ));
    assert_eq!(
        p,
        PushPolicy {
            done: true,
            ..PushPolicy::default()
        }
    );
}

#[test]
fn set_refuses_bad_json_and_keeps_the_old_choice() {
    let state = tempfile::tempdir().unwrap();
    printed(&beacon(
        state.path(),
        &["policy", "set"],
        r#"{"done":true}"#,
    ));

    let out = beacon(state.path(), &["policy", "set"], "not json");
    assert_eq!(out.status.code(), Some(2), "{out:?}");
    assert!(String::from_utf8_lossy(&out.stderr).contains("policy"));

    assert!(printed(&beacon(state.path(), &["policy"], "")).done);
}
