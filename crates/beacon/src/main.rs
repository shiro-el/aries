//! `aries-beacon`: runs on a Host.
//!
//! - `serve`: the resident service (a systemd user service on Linux, a
//!   launchd LaunchAgent on macOS). Stays
//!   attached to the user's local herdr-core (default session), records
//!   that in its service file and pushes Agent status changes to the paired
//!   phone as the push policy says ([`status_push`]).
//! - `status`: prints one [`BeaconStatus`] JSON line.
//! - `call`: an Agent asks for the user (see `calls.rs`).
//! - `calls [--follow]`: Call history as JSON lines (see `calls.rs`).
//! - `reply CALL CHOICE`: the phone answers a Call; types the Choice into
//!   the calling Terminal once (see `calls.rs`).
//! - `pair` / `push-test` / `unpair`: the push pairing with one phone
//!   ([`push`]); run by the phone over SSH.
//! - `chat <terminal> [--follow] [--cursor C]`: the Terminal's Agent Chat
//!   as JSON lines (`aries_protocol::chat`).
//! - `answer <terminal> <question> <picks>...`: answers the question the
//!   Terminal's Agent shows (`aries_protocol::answer`); one reply line.
//! - `policy [set]`: prints or (from stdin) changes the push policy
//!   ([`policy`]); run by the phone over SSH.
//! - `skill install` / `skill uninstall`: the managed `call-human` skill
//!   for Agents ([`aries_beacon::skill`]); run by the phone over SSH when it
//!   installs, updates or removes the Beacon. One `SkillReport` line.
//! - `pi-extension install|remove`: the managed Pi status extension
//!   ([`aries_beacon::pi_extension`]); the phone runs `install` after
//!   installing or updating the Beacon.
//! - `opencode-plugin install|remove`: the managed OpenCode status plugin
//!   ([`aries_beacon::opencode_plugin`]); the phone runs `install` after
//!   installing or updating the Beacon. One JSON line of what it did.
//! - `--version`: `aries-beacon <version> (contract <n>)`.

mod call_push;
mod calls;
mod policy;
mod push;
mod status_push;

use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

use aries_beacon::core_link::CoreLink;
use aries_beacon::status_watch::{StatusWatch, Step};
use aries_beacon::{herdr_core, home};
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
        Some("call") => std::process::exit(calls::call_command(&call_log(), &rest_args())),
        Some("calls") => std::process::exit(calls::calls_command(&call_log(), &rest_args())),
        Some("reply") => std::process::exit(calls::reply_command(&call_log(), &rest_args())),
        Some("pair") => exit_on_error(push::pair_command(false)),
        Some("pair-prepare") => exit_on_error(push::pair_command(true)),
        Some("pair-commit") => exit_on_error(push::commit_command()),
        Some("push-test") => exit_on_error(push::push_test_command()),
        Some("unpair") => exit_on_error(push::unpair_command()),
        Some("chat") => chat(std::env::args().skip(2).collect()),
        Some("answer") => answer(std::env::args().skip(2).collect()),
        Some("skill") => exit_on_error(skill(&rest_args())),
        Some("policy") => std::process::exit(policy::command(&rest_args())),
        Some("opencode-plugin") => opencode_plugin(std::env::args().nth(2).as_deref()),
        Some("pi-extension") => {
            std::process::exit(aries_beacon::pi_extension::command(&rest_args()))
        }
        _ => {
            eprintln!(
                "usage: aries-beacon --version | status | serve | call ... | calls [--follow] | reply CALL CHOICE | pair | push-test | unpair | chat TERMINAL [--follow] [--cursor C] | answer TERMINAL QUESTION PICKS... | skill install|uninstall | pi-extension install|remove | opencode-plugin install|remove | policy [set]"
            );
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

/// `skill install|uninstall`. `ARIES_BEACON_SKILL_SOURCE=<dir>` installs
/// the files in that directory instead of the shipped skill (tests use it
/// to play an older release).
fn skill(args: &[String]) -> Result<(), String> {
    use aries_beacon::skill;
    let report = match args {
        [a] if a == "install" => {
            let files = match std::env::var_os("ARIES_BEACON_SKILL_SOURCE") {
                Some(dir) => {
                    skill::files_in(Path::new(&dir)).map_err(|e| format!("skill source: {e}"))?
                }
                None => skill::shipped(),
            };
            skill::install(&home(), &state_dir(), &files)
        }
        [a] if a == "uninstall" => skill::uninstall(&home(), &state_dir()),
        _ => return Err("usage: aries-beacon skill install|uninstall".into()),
    }
    .map_err(|e| format!("skill: {e}"))?;
    println!(
        "{}",
        serde_json::to_string(&report).expect("skill report json")
    );
    Ok(())
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

fn call_log() -> calls::Log {
    calls::Log::new(state_dir())
}

fn rest_args() -> Vec<String> {
    std::env::args().skip(2).collect()
}

fn service_file() -> PathBuf {
    state_dir().join("service.json")
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

/// `pid` is a live `aries-beacon` process (not a reused pid). Linux reads
/// `/proc`; macOS, which has none, asks `ps`.
fn is_beacon(pid: u32) -> bool {
    if Path::new("/proc/self").exists() {
        return std::fs::read(format!("/proc/{pid}/cmdline"))
            .map(|c| {
                let argv0 = c.split(|b| *b == 0).next().unwrap_or_default();
                String::from_utf8_lossy(argv0).ends_with("aries-beacon")
            })
            .unwrap_or(false);
    }
    Command::new("ps")
        .args(["-p", &pid.to_string(), "-o", "comm="])
        .stderr(Stdio::null())
        .output()
        .map(|o| {
            o.status.success()
                && String::from_utf8_lossy(&o.stdout)
                    .trim()
                    .ends_with("aries-beacon")
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
    let pushes = status_push::Sender::start();
    // Kept across reconnects: what was already pushed is not pushed again.
    let mut watch = StatusWatch::new();
    loop {
        write_state(&ServiceState {
            pid,
            core_version: None,
        });
        match attach(&mut watch, &pushes, |version| {
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
/// subscribes to events (plain mode: the connection then only carries
/// notifications), calls `on_attached` with the core version, then feeds
/// every notification to `watch` until the connection ends.
fn attach(
    watch: &mut StatusWatch,
    pushes: &status_push::Sender,
    on_attached: impl FnOnce(&str),
) -> Result<(), String> {
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
    let subscribe = r#"{"jsonrpc":"2.0","id":1,"method":"events.subscribe","params":{}}"#;
    let result = (|| {
        writeln!(stdin, "{hello}\n{subscribe}").map_err(|e| format!("send hello: {e}"))?;
        let mut lines = BufReader::new(stdout).lines();
        let mut reply = |id: u64| -> Result<serde_json::Value, String> {
            loop {
                let line = lines
                    .next()
                    .ok_or("herdr-core closed before answering")?
                    .map_err(|e| format!("read: {e}"))?;
                let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else {
                    continue; // login noise
                };
                if v["id"] == id {
                    if let Some(e) = v.get("error") {
                        return Err(format!("herdr-core refused: {e}"));
                    }
                    return Ok(v["result"].clone());
                }
            }
        };
        let version = reply(0)?["version"].as_str().unwrap_or_default().to_owned();
        let snapshot = reply(1)?;
        pushes.send(watch.snapshot(&snapshot));
        on_attached(&version);
        // Terminals an event does not describe are read on a second link.
        let mut lookup: Option<CoreLink> = None;
        for line in lines {
            let Ok(line) = line else { break };
            let Ok(msg) = serde_json::from_str::<serde_json::Value>(&line) else {
                continue;
            };
            let changes = match watch.apply(&msg) {
                Step::Changes(c) => c,
                Step::Refetch(address) => {
                    let got = refetch(&mut lookup, &address)?;
                    watch.replace(&address, got.as_ref())
                }
            };
            pushes.send(changes);
        }
        Ok(())
    })();
    drop(stdin);
    let _ = child.kill();
    let _ = child.wait();
    result
}

/// `terminal.get` for `address`; `None` when the core has no such Terminal.
fn refetch(
    link: &mut Option<CoreLink>,
    address: &str,
) -> Result<Option<serde_json::Value>, String> {
    if link.is_none() {
        *link = Some(CoreLink::connect()?);
    }
    let l = link.as_mut().expect("link");
    match l.call("terminal.get", serde_json::json!({ "terminal": address })) {
        Ok(v) => Ok(Some(v)),
        Err(e) if e.starts_with("rpc") => Ok(None),
        Err(e) => {
            *link = None;
            Err(e)
        }
    }
}

fn chat(args: Vec<String>) {
    let mut opts = None;
    let mut follow = false;
    let mut cursor = None;
    let mut args = args.into_iter();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--follow" => follow = true,
            "--cursor" => cursor = args.next(),
            _ if opts.is_none() && !arg.starts_with('-') => {
                opts = Some(aries_beacon::chat::Options::new(arg))
            }
            _ => {
                eprintln!("aries-beacon chat: unexpected argument {arg}");
                std::process::exit(2);
            }
        }
    }
    let Some(mut opts) = opts else {
        eprintln!("usage: aries-beacon chat TERMINAL [--follow] [--cursor C]");
        std::process::exit(2);
    };
    opts.follow = follow;
    opts.cursor = cursor.filter(|c| !c.is_empty());
    let mut lookup = aries_beacon::core_link::CoreLookup::default();
    let agents = aries_beacon::chat::agents();
    let stdout = std::io::stdout();
    let mut out = std::io::LineWriter::new(stdout.lock());
    if let Err(e) = aries_beacon::chat::run(opts, &mut lookup, &agents, &mut out) {
        // A closed stdout is how the app stops following.
        if e.kind() != std::io::ErrorKind::BrokenPipe {
            eprintln!("aries-beacon chat: {e}");
            std::process::exit(1);
        }
    }
}

fn answer(args: Vec<String>) {
    use aries_protocol::answer::Answer;
    let parsed = args
        .split_first()
        .ok_or_else(|| "missing terminal".to_string())
        .and_then(|(t, rest)| Ok((t.clone(), Answer::from_args(rest)?)));
    let (terminal, answer) = match parsed {
        Ok(p) => p,
        Err(e) => {
            eprintln!("aries-beacon answer: {e}");
            eprintln!("usage: aries-beacon answer TERMINAL QUESTION PICKS...");
            std::process::exit(2);
        }
    };
    let mut link = aries_beacon::core_link::CoreLookup::default();
    let agents = aries_beacon::chat::agents();
    let reply = aries_beacon::chat::answer::answer(&terminal, &answer, &mut link, &agents)
        .unwrap_or_else(|e| {
            aries_protocol::answer::AnswerReply::new(
                aries_protocol::answer::AnswerOutcome::Gone,
                Some(format!("transcript: {e}")),
            )
        });
    println!("{}", reply.to_json());
    if !reply.outcome.is_sent() {
        std::process::exit(1);
    }
}

/// `opencode-plugin install`: `{"opencode":"installed"|"updated"|"unchanged"|"kept_user_copy"}`.
/// `opencode-plugin remove`: `{"opencode":"removed"|"kept"}`.
fn opencode_plugin(action: Option<&str>) {
    use aries_beacon::opencode_plugin as i;
    let home = i::config_home();
    let result = match action {
        Some("install") => {
            i::install_opencode_plugin(&home).map(|o| serde_json::json!({ "opencode": o }))
        }
        Some("remove") => i::remove_opencode_plugin(&home).map(
            |removed| serde_json::json!({ "opencode": if removed { "removed" } else { "kept" } }),
        ),
        _ => {
            eprintln!("usage: aries-beacon opencode-plugin install|remove");
            std::process::exit(2);
        }
    };
    match result {
        Ok(v) => println!("{v}"),
        Err(e) => {
            eprintln!("aries-beacon opencode-plugin: {}: {e}", home.display());
            std::process::exit(1);
        }
    }
}

/// One plain-mode call to the local herdr-core (the session the environment
/// points at): `hello`, then `method`. Gives up after `timeout`.
fn rpc_once(
    method: &str,
    params: serde_json::Value,
    timeout: Duration,
) -> Result<serde_json::Value, String> {
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
    let hello =
        serde_json::json!({"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":1}});
    let call = serde_json::json!({"jsonrpc":"2.0","id":1,"method":method,"params":params});
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let reply = (|| {
            for line in BufReader::new(stdout).lines() {
                let line = line.map_err(|e| e.to_string())?;
                let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else {
                    continue; // login noise
                };
                if v["id"] == 1 {
                    return Ok(v);
                }
            }
            Err("herdr-core closed without answering".to_owned())
        })();
        let _ = tx.send(reply);
    });
    let result = writeln!(stdin, "{hello}\n{call}")
        .map_err(|e| format!("talk to herdr-core: {e}"))
        .and_then(|_| {
            rx.recv_timeout(timeout)
                .map_err(|_| "herdr-core did not answer in time".to_owned())?
        });
    drop(stdin);
    let _ = child.kill();
    let _ = child.wait();
    let reply = result?;
    match reply.get("error") {
        Some(e) => Err(format!(
            "{method}: {}",
            e["message"].as_str().unwrap_or("refused")
        )),
        None => Ok(reply["result"].clone()),
    }
}
