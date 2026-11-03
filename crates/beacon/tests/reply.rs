//! `aries-beacon reply CALL CHOICE` as a black box: a fake Agent in a real
//! herdr-core Terminal sends a Call with the real `aries-beacon call`, and
//! the reply types the Choice into that Terminal, once.

use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::time::{Duration, Instant};

use aries_protocol::calls::{AnswerState, Call, CallsLine, ReplyLine, ReplyOutcome};
use aries_test_support::{FakeAgent, HcSession};

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

    fn path(&self) -> String {
        let bin_dir = self.hc.bin().parent().unwrap();
        format!("{}:/usr/bin:/bin", bin_dir.display())
    }

    /// The Beacon run outside any Terminal (like the phone over SSH).
    fn beacon(&self, args: &[&str]) -> Command {
        let mut cmd = Command::new(BEACON);
        cmd.args(args).env_clear();
        cmd.envs(self.hc.env());
        cmd.env("XDG_STATE_HOME", self.state.path());
        cmd.env("PATH", self.path());
        cmd.stdin(Stdio::null());
        cmd
    }

    /// A fake Agent that sends a Call with `choices`, then waits for input
    /// (and keeps recording every line after). Returns it once the Call is
    /// recorded, with the Call.
    fn agent_with_call(&self, name: &str, choices: &[&str]) -> (FakeAgent, Call) {
        self.agent_with_call_then(name, choices, "status idle")
    }

    /// Like [`Env::agent_with_call`], with `after` as the status step after
    /// the Call.
    fn agent_with_call_then(&self, name: &str, choices: &[&str], after: &str) -> (FakeAgent, Call) {
        let choices: String = choices
            .iter()
            .map(|c| format!(" --choice '{c}'"))
            .collect();
        let script = format!(
            "status working\n\
             run env 'PATH={}' 'XDG_STATE_HOME={}' {BEACON} call --title 'Merge?'{choices} >/dev/null 2>&1\n\
             {after}\n\
             wait\n\
             status working\n",
            self.path(),
            self.state.path().display(),
        );
        let agent = self.hc.run_fake_agent(name, &script);
        let call = wait_for("the Call", || {
            self.recorded()
                .into_iter()
                .find(|c| c.terminal.as_deref() == Some(agent.address.as_str()))
        });
        (agent, call)
    }

    fn recorded(&self) -> Vec<Call> {
        let out = self.beacon(&["calls"]).output().unwrap();
        String::from_utf8(out.stdout)
            .unwrap()
            .lines()
            .filter_map(|l| match serde_json::from_str(l).ok()? {
                CallsLine::Call { call, .. } => Some(call),
                _ => None,
            })
            .collect()
    }

    fn call(&self, id: &str) -> Call {
        self.recorded().into_iter().find(|c| c.id == id).unwrap()
    }

    fn reply(&self, id: &str, choice: &str) -> (Output, ReplyLine) {
        let out = self.beacon(&["reply", id, choice]).output().unwrap();
        let line = printed(&out);
        (out, line)
    }

    fn log_path(&self) -> PathBuf {
        self.state.path().join("aries-beacon/calls.jsonl")
    }
}

fn printed(out: &Output) -> ReplyLine {
    let stdout = String::from_utf8_lossy(&out.stdout);
    let line = stdout
        .lines()
        .rfind(|l| l.starts_with('{'))
        .unwrap_or_else(|| panic!("no reply line: {out:?}"));
    ReplyLine::parse(line).unwrap()
}

fn wait_for<T>(what: &str, f: impl Fn() -> Option<T>) -> T {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if let Some(v) = f() {
            return v;
        }
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn inputs_after(agent: &FakeAgent, want: usize) -> Vec<String> {
    wait_for("the Agent's input", || {
        let got = agent.inputs();
        (got.len() >= want).then_some(got)
    })
}

#[test]
fn a_reply_types_the_choice_and_enter_into_the_calling_terminal() {
    let env = Env::new();
    let (agent, call) = env.agent_with_call("agent", &["Merge it", "Wait"]);

    let (out, line) = env.reply(&call.id, "0");

    assert!(out.status.success(), "{out:?}");
    assert_eq!(line.outcome, ReplyOutcome::Delivered);
    // `wait` reads one line: exactly the Choice, ended by Enter.
    assert_eq!(inputs_after(&agent, 1), ["Merge it"]);
    let answer = env.call(&call.id).answer.unwrap();
    assert_eq!(answer.choice, 0);
    assert_eq!(answer.text, "Merge it");
    assert_eq!(answer.state, AnswerState::Delivered);
    assert_eq!(line.answer, Some(answer));
}

#[test]
fn a_second_reply_is_refused_and_types_nothing() {
    let env = Env::new();
    let (agent, call) = env.agent_with_call("agent", &["Yes", "No"]);
    let (first, _) = env.reply(&call.id, "1");
    assert!(first.status.success(), "{first:?}");
    assert_eq!(inputs_after(&agent, 1), ["No"]);

    // The same answer again (a retry) and a different one (another phone).
    for choice in ["1", "0"] {
        let (out, line) = env.reply(&call.id, choice);
        assert!(!out.status.success());
        assert_eq!(line.outcome, ReplyOutcome::Answered);
        assert_eq!(line.answer.as_ref().unwrap().text, "No");
    }
    std::thread::sleep(Duration::from_millis(800));
    assert_eq!(agent.inputs(), ["No"]);
    assert_eq!(env.call(&call.id).answer.unwrap().state, AnswerState::Delivered);
}

#[test]
fn racing_replies_type_only_one_answer() {
    let env = Env::new();
    let (agent, call) = env.agent_with_call("agent", &["Yes", "No"]);
    let children: Vec<_> = (0..6)
        .map(|i| {
            env.beacon(&["reply", &call.id, if i % 2 == 0 { "0" } else { "1" }])
                .stdout(Stdio::piped())
                .spawn()
                .unwrap()
        })
        .collect();
    let lines: Vec<ReplyLine> = children
        .into_iter()
        .map(|c| printed(&c.wait_with_output().unwrap()))
        .collect();

    let delivered = lines
        .iter()
        .filter(|l| l.outcome == ReplyOutcome::Delivered)
        .count();
    assert_eq!(delivered, 1, "{lines:?}");
    assert!(
        lines
            .iter()
            .all(|l| matches!(l.outcome, ReplyOutcome::Delivered | ReplyOutcome::Answered)),
        "{lines:?}"
    );
    let text = env.call(&call.id).answer.unwrap().text;
    std::thread::sleep(Duration::from_millis(800));
    assert_eq!(agent.inputs(), [text]);
}

#[test]
fn a_reply_to_a_closed_terminal_fails_clearly_and_is_recorded() {
    let env = Env::new();
    let (agent, call) = env.agent_with_call("agent", &["Yes"]);
    env.hc.hc(&["kill", &agent.address]);
    wait_for("the Terminal to end", || {
        let out = env.hc.command().args(["get", "--json", &agent.address]).output().unwrap();
        let live = out.status.success()
            && serde_json::from_slice::<serde_json::Value>(&out.stdout)
                .map(|v| v["state"] == "live")
                .unwrap_or(false);
        (!live).then_some(())
    });

    let (out, line) = env.reply(&call.id, "0");

    assert!(!out.status.success());
    assert_eq!(line.outcome, ReplyOutcome::Failed);
    let answer = env.call(&call.id).answer.unwrap();
    assert_eq!(answer.state, AnswerState::Failed);
    assert!(answer.detail.is_some());
    assert!(agent.inputs().is_empty());
}

#[test]
fn a_blocked_agent_is_not_typed_into() {
    let env = Env::new();
    let (agent, call) =
        env.agent_with_call_then("agent", &["Yes"], "status blocked permission Allow rm?");
    std::thread::sleep(Duration::from_millis(300));

    let (out, line) = env.reply(&call.id, "0");

    assert!(!out.status.success());
    assert_eq!(line.outcome, ReplyOutcome::Failed, "{line:?}");
    assert_eq!(env.call(&call.id).answer.unwrap().state, AnswerState::Failed);
    std::thread::sleep(Duration::from_millis(300));
    assert!(agent.inputs().is_empty());
}

#[test]
fn unknown_calls_choices_and_unconfirmed_callers_are_refused() {
    let env = Env::new();
    let (agent, call) = env.agent_with_call("agent", &["Yes"]);
    let out = env
        .beacon(&["call", "--title", "From cron", "--choice", "Ok"])
        .output()
        .unwrap();
    assert!(out.status.success(), "{out:?}");
    let outside = env
        .recorded()
        .into_iter()
        .find(|c| c.terminal.is_none())
        .unwrap();

    assert_eq!(env.reply("c0-nope", "0").1.outcome, ReplyOutcome::NoCall);
    assert_eq!(env.reply(&call.id, "1").1.outcome, ReplyOutcome::NoChoice);
    assert_eq!(env.reply(&outside.id, "0").1.outcome, ReplyOutcome::NotAnswerable);
    // None of them claimed anything.
    assert_eq!(env.call(&call.id).answer, None);
    assert_eq!(env.call(&outside.id).answer, None);
    std::thread::sleep(Duration::from_millis(300));
    assert!(agent.inputs().is_empty());
    let bad = env.beacon(&["reply", &call.id]).output().unwrap();
    assert_eq!(bad.status.code(), Some(2));
}

/// A herdr-core that confirms the Terminal is live and then drops the
/// connection when asked to type, so the send may or may not have landed.
fn write_flaky_core(dir: &Path) -> PathBuf {
    let bin = dir.join("flaky/herdr-core");
    std::fs::create_dir_all(bin.parent().unwrap()).unwrap();
    std::fs::write(
        &bin,
        r#"#!/bin/sh
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  case $line in
    *'"hello"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"version":"0.1.17"}}\n' "$id" ;;
    *'"terminal.get"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"address":"t1","state":"live"}}\n' "$id" ;;
    *'"terminal.send"'*) echo "$line" >> "$(dirname "$0")/sent"; exit 1 ;;
  esac
done
"#,
    )
    .unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
    bin
}

#[test]
fn an_ambiguous_send_is_recorded_unconfirmed_and_never_retried() {
    let env = Env::new();
    let (_agent, call) = env.agent_with_call("agent", &["Yes"]);
    let flaky = write_flaky_core(env.state.path());
    let flaky_path = format!("{}:/usr/bin:/bin", flaky.parent().unwrap().display());

    let out = env
        .beacon(&["reply", &call.id, "0"])
        .env("PATH", &flaky_path)
        .output()
        .unwrap();

    let line = printed(&out);
    assert!(!out.status.success());
    assert_eq!(line.outcome, ReplyOutcome::Unconfirmed);
    let sent = std::fs::read_to_string(flaky.parent().unwrap().join("sent")).unwrap();
    assert_eq!(sent.lines().count(), 1, "sent once, not retried: {sent}");
    let answer = env.call(&call.id).answer.unwrap();
    assert_eq!(answer.state, AnswerState::Unconfirmed);
    // And a later reply does not try again.
    let (_, again) = env.reply(&call.id, "0");
    assert_eq!(again.outcome, ReplyOutcome::Answered);
    assert!(env.log_path().exists());
}
