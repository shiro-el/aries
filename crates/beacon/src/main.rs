//! `aries-beacon`: runs on a Host.
//!
//! - `serve`: the resident service (a systemd user service on Linux). Stays
//!   attached to the user's local herdr-core (default session) and records
//!   that in its service file.
//! - `status`: prints one [`BeaconStatus`] JSON line.
//! - `pair` / `push-test` / `unpair`: the push pairing with one phone
//!   ([`push`]); run by the phone over SSH.
//! - `--version`: `aries-beacon <version> (contract <n>)`.

mod push;

use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

use aries_protocol::beacon::BeaconStatus;
use serde::{Deserialize, Serialize};

fn main() {
    match std::env::args().nth(1).as_deref() {
        Some("--version" | "version") => println!(
            "aries-beacon {} (contract {})",
            env!("CARGO_PKG_VERSION"),
            aries_protocol::CONTRACT_VERSION
        ),
        Some("status") => {
            let line = serde_json::to_string(&status()).expect("status json");
            println!("{line}");
        }
        Some("serve") => serve(),
        Some("pair") => exit_on_error(push::pair_command()),
        Some("push-test") => exit_on_error(push::push_test_command()),
        Some("unpair") => exit_on_error(push::unpair_command()),
        _ => {
            eprintln!("usage: aries-beacon --version | status | serve | pair | push-test | unpair");
            std::process::exit(2);
        }
    }
}

fn exit_on_error(result: Result<(), String>) {
    if let Err(e) = result {
        eprintln!("aries-beacon: {e}");
        std::process::exit(1);
    }
}

/// What `serve` records about itself, in [`service_file`].
#[derive(Debug, Default, Serialize, Deserialize)]
struct ServiceState {
    pid: u32,
    core_version: Option<String>,
}

/// The Beacon's private directory. Call records live here too, so it
/// survives an uninstall; only [`service_file`] is removed then.
fn state_dir() -> PathBuf {
    let base = std::env::var_os("XDG_STATE_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(".local/state"));
    base.join("aries-beacon")
}

fn service_file() -> PathBuf {
    state_dir().join("service.json")
}

fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
}

fn status() -> BeaconStatus {
    let state: Option<ServiceState> = std::fs::read(service_file())
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok());
    let live = state.filter(|s| is_beacon(s.pid));
    BeaconStatus {
        version: env!("CARGO_PKG_VERSION").into(),
        contract: aries_protocol::CONTRACT_VERSION,
        running: live.is_some(),
        core_version: live.and_then(|s| s.core_version),
        linger: lingering(),
    }
}

/// `pid` is a live `aries-beacon` process (not a reused pid).
fn is_beacon(pid: u32) -> bool {
    std::fs::read(format!("/proc/{pid}/cmdline"))
        .map(|c| {
            let argv0 = c.split(|b| *b == 0).next().unwrap_or_default();
            String::from_utf8_lossy(argv0).ends_with("aries-beacon")
        })
        .unwrap_or(false)
}

/// logind records lingering users as files in this directory.
fn lingering() -> bool {
    let user = std::env::var("USER").or_else(|_| std::env::var("LOGNAME"));
    user.is_ok_and(|u| Path::new("/var/lib/systemd/linger").join(u).exists())
}

fn write_state(state: &ServiceState) {
    let dir = state_dir();
    if let Err(e) = std::fs::create_dir_all(&dir) {
        eprintln!("aries-beacon: {}: {e}", dir.display());
        return;
    }
    let tmp = dir.join("service.json.tmp");
    let body = serde_json::to_vec(state).expect("state json");
    if let Err(e) = std::fs::write(&tmp, body).and_then(|_| std::fs::rename(&tmp, service_file())) {
        eprintln!("aries-beacon: write service state: {e}");
    }
}

fn serve() {
    let pid = std::process::id();
    let mut backoff = Duration::from_secs(1);
    loop {
        write_state(&ServiceState {
            pid,
            core_version: None,
        });
        match attach(|version| {
            eprintln!("aries-beacon: attached to herdr-core {version}");
            write_state(&ServiceState {
                pid,
                core_version: Some(version.to_owned()),
            });
        }) {
            Ok(()) => {
                eprintln!("aries-beacon: herdr-core connection ended");
                backoff = Duration::from_secs(1);
            }
            Err(e) => eprintln!("aries-beacon: {e}"),
        }
        write_state(&ServiceState {
            pid,
            core_version: None,
        });
        std::thread::sleep(backoff);
        backoff = (backoff * 2).min(Duration::from_secs(30));
    }
}

/// Runs `herdr-core connect` (the user's default session), says `hello`,
/// calls `on_attached` with the core version, then holds the connection
/// until it ends.
fn attach(on_attached: impl FnOnce(&str)) -> Result<(), String> {
    let bin = herdr_core().ok_or("herdr-core not found on PATH or in ~/.local/bin")?;
    let mut child = Command::new(&bin)
        .arg("connect")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("{}: {e}", bin.display()))?;
    let mut stdin = child.stdin.take().expect("piped stdin");
    let stdout = child.stdout.take().expect("piped stdout");
    let hello = r#"{"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":1}}"#;
    let result = (|| {
        writeln!(stdin, "{hello}").map_err(|e| format!("send hello: {e}"))?;
        let mut lines = BufReader::new(stdout).lines();
        let version = loop {
            let line = lines
                .next()
                .ok_or("herdr-core closed before answering hello")?
                .map_err(|e| format!("read hello: {e}"))?;
            let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else {
                continue; // login noise
            };
            if v["id"] == 0 {
                if let Some(e) = v.get("error") {
                    return Err(format!("hello refused: {e}"));
                }
                break v["result"]["version"]
                    .as_str()
                    .unwrap_or_default()
                    .to_owned();
            }
        };
        on_attached(&version);
        // Held open; later tickets subscribe to events here.
        for line in lines {
            if line.is_err() {
                break;
            }
        }
        Ok(())
    })();
    drop(stdin);
    let _ = child.kill();
    let _ = child.wait();
    result
}

/// Same lookup the phone uses over SSH: `PATH`, then `~/.local/bin`.
fn herdr_core() -> Option<PathBuf> {
    let path = std::env::var_os("PATH").unwrap_or_default();
    std::env::split_paths(&path)
        .map(|d| d.join("herdr-core"))
        .chain(std::iter::once(home().join(".local/bin/herdr-core")))
        .find(|p| p.is_file())
}
