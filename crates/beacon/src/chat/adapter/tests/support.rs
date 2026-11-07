pub(super) use super::super::super::Options;
pub(super) use super::super::delivery as answer;
pub(super) use super::super::{
    Agent, Batch, Interpreter, JsonlSource, KeysError, Located, Opened, Poll, Source,
    TerminalFacts, TerminalInput, TerminalLookup, claude, codex, opencode, permission, pi,
};
use std::io::Write;

pub(super) fn run(
    opts: Options,
    lookup: &mut dyn TerminalLookup,
    agents: &[Box<dyn Agent>],
    out: &mut dyn Write,
) -> std::io::Result<()> {
    let state = tempfile::tempdir()?;
    let feed =
        super::super::Feed::new(state.path(), agents, &opts.terminal, opts.cursor.as_deref());
    super::super::super::follow::run_feed(&opts, lookup, feed, out)
}

pub(super) struct Beacon;
pub(super) const BEACON: Beacon = Beacon;
fn beacon_path() -> &'static std::path::Path {
    static PATH: std::sync::OnceLock<std::path::PathBuf> = std::sync::OnceLock::new();
    PATH.get_or_init(|| {
        std::env::current_exe()
            .expect("test binary")
            .parent()
            .expect("deps directory")
            .parent()
            .expect("target profile")
            .join("aries-beacon")
    })
}
impl AsRef<std::ffi::OsStr> for Beacon {
    fn as_ref(&self) -> &std::ffi::OsStr {
        beacon_path().as_os_str()
    }
}
impl std::fmt::Display for Beacon {
    fn fmt(&self, out: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        beacon_path().display().fmt(out)
    }
}

pub(in crate::chat::adapter) fn isolated_test(name: &str) -> bool {
    if std::env::var("ARIES_ADAPTER_TEST_CHILD").as_deref() == Ok(name) {
        return false;
    }
    let output = std::process::Command::new(std::env::current_exe().unwrap())
        .args(["--exact", name.split_once("::").unwrap().1, "--nocapture"])
        .env("ARIES_ADAPTER_TEST_CHILD", name)
        .output()
        .unwrap();
    assert!(output.status.success(), "{output:?}");
    true
}
