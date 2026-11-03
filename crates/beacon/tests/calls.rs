//! `aries-beacon call` and `aries-beacon calls` as a black box, against an
//! isolated herdr-core session whose Terminals run the calls.

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::calls::{Call, CallsLine, LogEntry, Urgency};
use aries_test_support::HcSession;

const BEACON: &str = env!("CARGO_BIN_EXE_aries-beacon");

struct Env {
    hc: HcSession,
    state: tempfile::TempDir,
}

impl Env {
    fn new() -> Self {
        Self {
            hc: HcSession::start(),
            state: tempfile::tempdir().unwrap(),
        }
    }

    fn path_var(&self) -> String {
        let bin_dir = self.hc.bin().parent().unwrap();
        format!("PATH={}:/usr/bin:/bin", bin_dir.display())
    }

    fn state_var(&self) -> String {
        format!("XDG_STATE_HOME={}", self.state.path().display())
    }

    fn log_path(&self) -> PathBuf {
        self.state.path().join("aries-beacon/calls.jsonl")
    }

    /// The Beacon run outside any Terminal (like the phone over SSH).
    fn beacon(&self, args: &[&str]) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.args(args).env_clear();
        cmd.envs(self.hc.env());
        cmd.env("XDG_STATE_HOME", self.state.path());
        cmd.env("PATH", &self.path_var()[5..]);
        cmd.stdin(Stdio::null());
        cmd
    }

    /// Runs `aries-beacon call <args>` inside a new top-level Terminal and
    /// returns (its address, what `call` printed).
    fn call_in_terminal(&self, name: &str, args: &[&str]) -> (String, String) {
        let out = self.state.path().join(format!("{name}.out"));
        let script = self.call_script(&out, args);
        let address = self.hc.run_bg(name, &["sh", "-c", &script]);
        (address, wait_file(&out))
    }

    fn call_script(&self, out: &Path, args: &[&str]) -> String {
        let quoted: Vec<String> = args.iter().map(|a| shell_quote(a)).collect();
        let out = out.display();
        format!(
            "env '{}' '{}' {BEACON} call {} > {out}.tmp 2>&1; mv {out}.tmp {out}; sleep 60",
            self.path_var(),
            self.state_var(),
            quoted.join(" "),
        )
    }

    fn calls(&self) -> Vec<CallsLine> {
        let out = self.beacon(&["calls"]).output().unwrap();
        assert!(out.status.success(), "calls failed: {out:?}");
        String::from_utf8(out.stdout)
            .unwrap()
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect()
    }

    fn recorded(&self) -> Vec<Call> {
        self.calls()
            .into_iter()
            .filter_map(|l| match l {
                CallsLine::Call { call, .. } => Some(call),
                _ => None,
            })
            .collect()
    }
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

fn wait_file(path: &Path) -> String {
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        if let Ok(s) = std::fs::read_to_string(path) {
            return s;
        }
        assert!(
            Instant::now() < deadline,
            "{} never appeared",
            path.display()
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// The JSON line `call` printed (it may also print a note on stderr first).
fn printed_call(out: &str) -> Call {
    let line = out
        .lines()
        .rfind(|l| l.starts_with('{'))
        .unwrap_or_else(|| panic!("no Call printed: {out:?}"));
    serde_json::from_str(line).unwrap()
}

#[test]
fn a_call_from_a_top_level_terminal_is_recorded_with_its_terminal() {
    let env = Env::new();
    let (address, out) = env.call_in_terminal(
        "agent",
        &[
            "--title",
            "Review ready",
            "--body",
            "Tests pass. Merge?",
            "--urgency",
            "urgent",
            "--choice",
            "Merge",
            "--choice",
            "Wait",
        ],
    );
    let call = printed_call(&out);
    assert_eq!(call.terminal.as_deref(), Some(address.as_str()));
    assert!(call.top_level);
    assert_eq!(call.title, "Review ready");
    assert_eq!(call.body, "Tests pass. Merge?");
    assert_eq!(call.urgency, Urgency::Urgent);
    assert_eq!(call.choices, ["Merge", "Wait"]);
    assert_eq!(call.answer, None);

    let lines = env.calls();
    assert_eq!(
        lines,
        [
            CallsLine::Call {
                seq: 1,
                call: call.clone()
            },
            CallsLine::Synced { seq: 1 }
        ]
    );
}

#[test]
fn a_call_from_a_helper_terminal_is_not_top_level() {
    let env = Env::new();
    let parent = env.hc.run_bg("coordinator", &["sleep", "60"]);
    let out_path = env.state.path().join("helper.out");
    let script = env.call_script(&out_path, &["--title", "Helper question"]);
    // Started from the coordinator's Terminal, so herdr-core makes it a child.
    let out = env
        .hc
        .command()
        .env("HC_TERMINAL_ID", &parent)
        .args([
            "run", "--bg", "--json", "--name", "helper", "--", "sh", "-c", &script,
        ])
        .stdin(Stdio::null())
        .output()
        .unwrap();
    assert!(out.status.success(), "{out:?}");
    let helper: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(helper["parent"], parent.as_str());

    let call = printed_call(&wait_file(&out_path));
    assert_eq!(call.terminal.as_deref(), helper["address"].as_str());
    assert!(!call.top_level);
    assert!(!call.pushes_by_default());
}

#[test]
fn a_call_outside_any_terminal_is_only_recorded() {
    let env = Env::new();
    let out = env
        .beacon(&["call", "--title", "From cron"])
        .output()
        .unwrap();
    assert!(out.status.success(), "{out:?}");
    let call = printed_call(&String::from_utf8(out.stdout).unwrap());
    assert_eq!(call.terminal, None);
    assert!(!call.top_level);
    assert!(String::from_utf8_lossy(&out.stderr).contains("only recorded"));
    assert_eq!(env.recorded(), [call]);
}

#[test]
fn a_borrowed_terminal_id_is_not_confirmed() {
    let env = Env::new();
    let victim = env.hc.run_bg("victim", &["sleep", "60"]);
    let out = env
        .beacon(&["call", "--title", "Spoofed"])
        .env("HC_TERMINAL_ID", &victim)
        .output()
        .unwrap();
    assert!(out.status.success(), "{out:?}");
    let call = printed_call(&String::from_utf8(out.stdout).unwrap());
    assert_eq!(call.terminal, None);
}

#[test]
fn an_invalid_call_is_refused_and_not_recorded() {
    let env = Env::new();
    for args in [
        &["call", "--body", "no title"][..],
        &[
            "call", "--title", "t", "--choice", "a", "--choice", "b", "--choice", "c", "--choice",
            "d",
        ],
        &["call", "--title", "t", "--urgency", "loud"],
        &["call", "--title", "t", "--choice", "two\nlines"],
    ] {
        let out = env.beacon(args).output().unwrap();
        assert_eq!(out.status.code(), Some(2), "{args:?}: {out:?}");
    }
    assert_eq!(env.recorded(), []);
}

#[test]
fn the_body_can_come_from_stdin() {
    let env = Env::new();
    let mut child = env
        .beacon(&["call", "--title", "Long", "--body", "-"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    use std::io::Write;
    child
        .stdin
        .take()
        .unwrap()
        .write_all("첫 줄\n둘째 줄\n".as_bytes())
        .unwrap();
    let out = child.wait_with_output().unwrap();
    assert!(out.status.success());
    assert_eq!(env.recorded()[0].body, "첫 줄\n둘째 줄");
}

#[test]
fn concurrent_calls_are_all_recorded_once() {
    let env = Env::new();
    let children: Vec<_> = (0..16)
        .map(|i| {
            env.beacon(&["call", "--title", &format!("call {i}")])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap()
        })
        .collect();
    for mut c in children {
        assert!(c.wait().unwrap().success());
    }
    let lines = env.calls();
    let mut seqs = Vec::new();
    let mut titles = Vec::new();
    for l in &lines {
        if let CallsLine::Call { seq, call } = l {
            seqs.push(*seq);
            titles.push(call.title.clone());
        }
    }
    seqs.sort();
    assert_eq!(seqs, (1..=16).collect::<Vec<_>>());
    titles.sort();
    titles.dedup();
    assert_eq!(titles.len(), 16);
    assert_eq!(lines.last(), Some(&CallsLine::Synced { seq: 16 }));
}

#[test]
fn the_history_is_private_to_the_user() {
    use std::os::unix::fs::PermissionsExt;
    let env = Env::new();
    assert!(
        env.beacon(&["call", "--title", "t"])
            .output()
            .unwrap()
            .status
            .success()
    );
    let mode = |p: &Path| std::fs::metadata(p).unwrap().permissions().mode() & 0o777;
    assert_eq!(mode(env.log_path().parent().unwrap()), 0o700);
    assert_eq!(mode(&env.log_path()), 0o600);
}

#[test]
fn follow_streams_new_calls_after_the_snapshot() {
    let env = Env::new();
    assert!(
        env.beacon(&["call", "--title", "before"])
            .output()
            .unwrap()
            .status
            .success()
    );
    let mut follow = env
        .beacon(&["calls", "--follow"])
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    let mut lines = BufReader::new(follow.stdout.take().unwrap()).lines();
    let mut next =
        || -> CallsLine { serde_json::from_str(&lines.next().unwrap().unwrap()).unwrap() };
    assert!(matches!(next(), CallsLine::Call { seq: 1, call } if call.title == "before"));
    assert_eq!(next(), CallsLine::Synced { seq: 1 });

    assert!(
        env.beacon(&["call", "--title", "after"])
            .output()
            .unwrap()
            .status
            .success()
    );
    assert!(matches!(next(), CallsLine::Call { seq: 2, call } if call.title == "after"));
    follow.kill().unwrap();
    follow.wait().unwrap();
}

fn old_call(seq: u64, at_ms: u64, pad: usize) -> String {
    let entry = LogEntry::Call {
        seq,
        call: Call {
            id: format!("old{seq}"),
            at_ms,
            terminal: None,
            recipient: None,
            top_level: false,
            title: format!("old {seq}"),
            body: "x".repeat(pad),
            urgency: Urgency::Record,
            choices: vec![],
            answer: None,
        },
    };
    serde_json::to_string(&entry).unwrap() + "\n"
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

#[test]
fn calls_older_than_thirty_days_are_dropped() {
    let env = Env::new();
    let day = 24 * 60 * 60 * 1000;
    let now = now_ms();
    std::fs::create_dir_all(env.log_path().parent().unwrap()).unwrap();
    let log = old_call(1, now - 31 * day, 10) + &old_call(2, now - 29 * day, 10);
    std::fs::write(env.log_path(), log).unwrap();

    assert!(
        env.beacon(&["call", "--title", "new"])
            .output()
            .unwrap()
            .status
            .success()
    );
    let titles: Vec<_> = env.recorded().into_iter().map(|c| c.title).collect();
    assert_eq!(titles, ["old 2", "new"]);
    assert_eq!(env.calls().last(), Some(&CallsLine::Synced { seq: 3 }));
}

#[test]
fn the_log_stays_under_its_size_cap_and_keeps_the_newest() {
    let env = Env::new();
    let now = now_ms();
    std::fs::create_dir_all(env.log_path().parent().unwrap()).unwrap();
    // 300 Calls of ~4 KiB: about 1.2 MiB, past the 1 MiB cap.
    let log: String = (1..=300).map(|s| old_call(s, now, 4000)).collect();
    std::fs::write(env.log_path(), log).unwrap();

    assert!(
        env.beacon(&["call", "--title", "new"])
            .output()
            .unwrap()
            .status
            .success()
    );
    let size = std::fs::metadata(env.log_path()).unwrap().len();
    assert!(size <= 1024 * 1024, "log is {size} bytes");
    let calls = env.recorded();
    assert_eq!(calls.last().unwrap().title, "new");
    assert_eq!(calls[calls.len() - 2].title, "old 300");
    assert!(calls.len() < 301);
    // Seqs keep counting: a follower that saw seq 300 sees the new Call.
    assert_eq!(env.calls().last(), Some(&CallsLine::Synced { seq: 301 }));
}
