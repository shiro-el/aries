//! Calls through the core API: Agents in real herdr-core Terminals run the
//! real `aries-beacon call`; the core reads and follows the Host's Call
//! history through the real `aries-beacon calls`.

mod common;

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use aries_core::{
    AriesCore, CallFeedState, CallInfo, CallListener, CallUrgency, CoreError, Endpoint,
};
use aries_test_support::{HcSession, beacon_bin};
use common::{core_in, local_endpoint};

struct Setup {
    hc: HcSession,
    core: Arc<AriesCore>,
    host: String,
    beacon: String,
    _data: tempfile::TempDir,
}

fn setup() -> Setup {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let beacon = beacon_bin().to_string_lossy().into_owned();
    let host = core
        .add_host("dev".into(), beacon_endpoint(&hc, &beacon))
        .unwrap()
        .id;
    Setup {
        hc,
        core,
        host,
        beacon,
        _data: data,
    }
}

impl Setup {
    fn env_prefix(&self) -> String {
        let state = self
            .hc
            .env()
            .into_iter()
            .find(|(k, _)| k == "XDG_STATE_HOME")
            .unwrap()
            .1;
        let bin_dir = self.hc.bin().parent().unwrap().display().to_string();
        format!("env 'PATH={bin_dir}:/usr/bin:/bin' 'XDG_STATE_HOME={state}'")
    }

    /// An Agent Terminal that sends one Call, then stays; returns its address
    /// once the Call is recorded.
    fn agent_calls(&self, name: &str, title: &str, extra: &str) -> String {
        let done = self._data.path().join(format!("{name}.done"));
        let script = format!(
            "{} {} call --title '{title}' {extra} >/dev/null 2>&1; touch {}; sleep 120",
            self.env_prefix(),
            self.beacon,
            done.display()
        );
        let address = self.hc.run_bg(name, &["sh", "-c", &script]);
        wait_until("the Call is recorded", || done.exists());
        address
    }

    /// A Call from outside any Terminal (no caller to confirm).
    fn call_from_outside(&self, title: &str) {
        let script = format!(
            "{} {} call --title '{title}'",
            self.env_prefix(),
            self.beacon
        );
        let out = std::process::Command::new("sh")
            .args(["-c", &script])
            .env_remove("HC_TERMINAL_ID")
            .output()
            .unwrap();
        assert!(out.status.success(), "{out:?}");
    }
}

/// A Local Host whose Beacon is `beacon` (through `ARIES_BEACON`).
fn beacon_endpoint(hc: &HcSession, beacon: &str) -> Endpoint {
    let Endpoint::Local { argv, mut env } = local_endpoint(hc) else {
        unreachable!()
    };
    env.insert("ARIES_BEACON".into(), beacon.into());
    Endpoint::Local { argv, env }
}

fn wait_until(what: &str, ok: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(20);
    while !ok() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(50));
    }
}

#[derive(Default)]
struct Recorder {
    lists: Mutex<Vec<Vec<CallInfo>>>,
    new: Mutex<Vec<CallInfo>>,
    states: Mutex<Vec<CallFeedState>>,
}

impl CallListener for Recorder {
    fn on_calls(&self, calls: Vec<CallInfo>) {
        self.lists.lock().unwrap().push(calls);
    }
    fn on_new_call(&self, call: CallInfo) {
        self.new.lock().unwrap().push(call);
    }
    fn on_state(&self, state: CallFeedState) {
        self.states.lock().unwrap().push(state);
    }
}

impl Recorder {
    fn titles(&self) -> Vec<String> {
        self.lists
            .lock()
            .unwrap()
            .last()
            .map(|l| l.iter().map(|c| c.title.clone()).collect())
            .unwrap_or_default()
    }
    fn new_titles(&self) -> Vec<String> {
        self.new
            .lock()
            .unwrap()
            .iter()
            .map(|c| c.title.clone())
            .collect()
    }
}

#[tokio::test]
async fn the_history_lists_calls_newest_first_with_their_terminal() {
    let s = setup();
    let agent = s.agent_calls(
        "agent",
        "Review ready",
        "--body 'Tests pass.' --urgency urgent --choice Merge --choice Wait",
    );
    s.call_from_outside("From cron");

    let calls = s.core.calls(s.host.clone()).await.unwrap();
    assert_eq!(calls.len(), 2);
    let (cron, review) = (&calls[0], &calls[1]);
    assert_eq!(cron.title, "From cron");
    assert_eq!(cron.terminal, None);
    assert!(!cron.pushes_by_default);

    assert_eq!(review.host_id, s.host);
    assert_eq!(review.title, "Review ready");
    assert_eq!(review.body, "Tests pass.");
    assert_eq!(review.urgency, CallUrgency::Urgent);
    assert_eq!(review.choices, ["Merge", "Wait"]);
    assert_eq!(review.terminal.as_deref(), Some(agent.as_str()));
    assert!(review.top_level);
    assert!(review.pushes_by_default);
    assert_eq!(review.answer, None);

    // The calling Terminal is one the app can open from the Call.
    let conn = s.core.connect(s.host.clone()).await.unwrap();
    let terminals = conn.terminals().await.unwrap();
    assert!(
        terminals
            .iter()
            .any(|t| Some(&t.address) == review.terminal.as_ref())
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn a_watch_reports_new_calls_once_across_reconnects() {
    let s = setup();
    s.call_from_outside("old");
    let rec = Arc::new(Recorder::default());
    let watch = s
        .core
        .watch_calls(s.host.clone(), rec.clone())
        .await
        .unwrap();
    wait_until("the first sync", || rec.titles() == ["old"]);
    assert_eq!(
        rec.new_titles(),
        Vec::<String>::new(),
        "the first sync is not news"
    );
    assert_eq!(
        rec.states.lock().unwrap().first(),
        Some(&CallFeedState::Live)
    );

    s.agent_calls("agent", "live call", "");
    wait_until("the live call", || rec.titles() == ["live call", "old"]);
    assert_eq!(rec.new_titles(), ["live call"]);

    // The network drops; a Call arrives while the phone is away.
    watch.drop_connection();
    s.call_from_outside("while away");
    wait_until("the reconnect", || {
        rec.titles() == ["while away", "live call", "old"]
    });
    assert!(
        rec.states
            .lock()
            .unwrap()
            .iter()
            .any(|st| matches!(st, CallFeedState::Reconnecting { .. }))
    );
    assert_eq!(
        rec.states.lock().unwrap().last(),
        Some(&CallFeedState::Live)
    );
    assert_eq!(rec.new_titles(), ["live call", "while away"]);

    // And it keeps following after the reconnect.
    s.call_from_outside("after");
    wait_until("the call after", || rec.titles().len() == 4);
    assert_eq!(rec.new_titles(), ["live call", "while away", "after"]);
    for list in rec.lists.lock().unwrap().iter() {
        let mut ids: Vec<_> = list.iter().map(|c| &c.id).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), list.len(), "no Call listed twice");
    }

    watch.stop();
    let count = rec.lists.lock().unwrap().len();
    s.call_from_outside("after stop");
    tokio::time::sleep(Duration::from_millis(800)).await;
    assert_eq!(rec.lists.lock().unwrap().len(), count);
}

#[tokio::test]
async fn a_host_without_a_beacon_says_so() {
    let s = setup();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "bare".into(),
            beacon_endpoint(&s.hc, "/nonexistent/aries-beacon"),
        )
        .unwrap()
        .id;
    assert!(matches!(
        core.calls(host).await,
        Err(CoreError::BeaconNotInstalled)
    ));
}

#[tokio::test]
async fn a_watch_reports_a_missing_beacon_as_a_typed_state() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "bare".into(),
            beacon_endpoint(&hc, "/nonexistent/aries-beacon"),
        )
        .unwrap()
        .id;
    let rec = Arc::new(Recorder::default());
    let watch = core.watch_calls(host, rec.clone()).await.unwrap();
    wait_until("the missing Beacon state", || {
        !rec.states.lock().unwrap().is_empty()
    });
    assert_eq!(
        rec.states.lock().unwrap().first(),
        Some(&CallFeedState::BeaconNotInstalled {
            detail: "the Beacon is not installed on this Host".into(),
        })
    );
    watch.stop();
}
