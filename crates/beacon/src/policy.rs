//! The push policy file, `<state>/push-policy.json` (a [`PushPolicy`] as
//! JSON; missing fields take the defaults). `serve` and `call` read it for
//! each push, so a change takes effect at once. The phone changes it over
//! SSH with `aries-beacon policy set`.
//!
//! - `policy`: prints the policy in effect (the defaults without a file).
//! - `policy set`: reads a [`PushPolicy`] JSON on stdin, writes it
//!   atomically (0600) and prints it. Bad input: exit 2, nothing changes.

use std::io::Read;
use std::path::PathBuf;

use aries_protocol::push_policy::PushPolicy;

fn path() -> PathBuf {
    crate::state_dir().join("push-policy.json")
}

/// The policy in effect: the file, or the defaults without one. An
/// unreadable file means the defaults too (with a line on stderr).
pub fn read() -> PushPolicy {
    let path = path();
    match std::fs::read(&path) {
        Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|e| {
            eprintln!("aries-beacon: {}: {e}; using the defaults", path.display());
            PushPolicy::default()
        }),
        Err(_) => PushPolicy::default(),
    }
}

fn write(policy: &PushPolicy) -> Result<(), String> {
    use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
    let dir = crate::state_dir();
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?;
    let tmp = dir.join(format!(".push-policy.json.{}.tmp", std::process::id()));
    let _ = std::fs::remove_file(&tmp);
    let result = (|| {
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)?;
        std::io::Write::write_all(&mut f, &serde_json::to_vec(policy).expect("policy json"))?;
        f.sync_all()?;
        std::fs::rename(&tmp, path())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result.map_err(|e| format!("{}: {e}", path().display()))
}

/// `policy [set]`; the process exit code.
pub fn command(args: &[String]) -> i32 {
    let policy = match args {
        [] => read(),
        [a] if a == "set" => {
            let mut input = String::new();
            if let Err(e) = std::io::stdin().read_to_string(&mut input) {
                eprintln!("aries-beacon: policy set: {e}");
                return 2;
            }
            let policy: PushPolicy = match serde_json::from_str(&input) {
                Ok(p) => p,
                Err(e) => {
                    eprintln!("aries-beacon: policy set: not a push policy: {e}");
                    return 2;
                }
            };
            if let Err(e) = write(&policy) {
                eprintln!("aries-beacon: policy set: {e}");
                return 1;
            }
            policy
        }
        _ => {
            eprintln!("aries-beacon: usage: aries-beacon policy [set]");
            return 2;
        }
    };
    println!("{}", serde_json::to_string(&policy).expect("policy json"));
    0
}
