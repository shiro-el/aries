//! Seam 1: live Agent status on one Host, against a real herdr-core and the
//! fake Agent.

mod common;

use std::time::Duration;

use aries_core::{AgentStatus, BlockedReason, TerminalInfo, TerminalWatch};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

/// Waits until the watched list satisfies `pred`; returns that list.
async fn until(
    watch: &TerminalWatch,
    what: &str,
    pred: impl Fn(&[TerminalInfo]) -> bool,
) -> Vec<TerminalInfo> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    let mut last = Vec::new();
    loop {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(left, watch.next()).await {
            Ok(Ok(list)) => {
                if pred(&list) {
                    return list;
                }
                last = list;
            }
            Ok(Err(e)) => panic!("watch ended while waiting for {what}: {e:?}"),
            Err(_) => panic!("timed out waiting for {what}; last list: {last:#?}"),
        }
    }
}

fn find<'a>(list: &'a [TerminalInfo], address: &str) -> &'a TerminalInfo {
    list.iter()
        .find(|t| t.address == address)
        .unwrap_or_else(|| panic!("{address} not in {list:#?}"))
}

fn status_is(list: &[TerminalInfo], address: &str, want: &AgentStatus) -> bool {
    list.iter()
        .any(|t| t.address == address && t.agent_status.as_ref() == Some(want))
}

#[tokio::test]
async fn shows_each_agents_status_live_and_leaves_other_programs_without_one() {
    let hc = HcSession::start();
    let plain = hc.run_bg("plain", &["sleep", "300"]);
    let agent = hc.run_fake_agent(
        "agent",
        "status idle\n\
         wait\n\
         status working - Writing hello.txt\n\
         status blocked permission approve Write: hello.txt\n\
         wait\n\
         status working\n\
         status done\n",
    );

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();

    let list = until(&watch, "the agent to be Idle", |l| {
        status_is(l, &agent.address, &AgentStatus::Idle)
    })
    .await;
    let other = find(&list, &plain);
    assert_eq!(other.agent_status, None, "sleep never reports status");
    assert!(!other.needs_attention);

    hc.hc(&["send", &agent.address, "write hello.txt"]);
    let blocked = AgentStatus::Blocked {
        reason: Some(BlockedReason::Permission),
        message: Some("approve Write: hello.txt".into()),
    };
    let list = until(&watch, "the agent to be Blocked", |l| {
        status_is(l, &agent.address, &blocked)
    })
    .await;
    assert!(find(&list, &agent.address).needs_attention);
    assert_eq!(find(&list, &plain).agent_status, None);

    hc.hc(&["send", "--force", &agent.address, "yes"]);
    let list = until(&watch, "the agent to be Done", |l| {
        status_is(l, &agent.address, &AgentStatus::Done)
    })
    .await;
    let done = find(&list, &agent.address);
    assert!(!done.needs_attention);

    // Another client (the desktop app) marks the turn seen: Done becomes Idle.
    let seen = format!("herdr.seen={}", done.turn);
    hc.hc(&["label", &agent.address, &seen]);
    until(&watch, "the seen agent to be Idle", |l| {
        status_is(l, &agent.address, &AgentStatus::Idle)
    })
    .await;

    assert_eq!(agent.inputs(), vec!["write hello.txt", "yes"]);
    watch.stop();
    conn.disconnect().await;
}

#[tokio::test]
async fn an_error_needs_attention_like_blocked() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let transcript = data.path().join("session.jsonl");
    let agent = hc.run_fake_agent(
        "broken",
        &format!(
            "status working\n\
             transcript claude-code/api-error-bad-model.jsonl {}\n\
             status error - model not found\n",
            transcript.display()
        ),
    );

    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();

    let error = AgentStatus::Error {
        message: Some("model not found".into()),
    };
    let list = until(&watch, "the agent to be Error", |l| {
        status_is(l, &agent.address, &error)
    })
    .await;
    assert!(find(&list, &agent.address).needs_attention);
    let fixture = aries_test_support::repo_root()
        .join("fixtures/agents/claude-code/api-error-bad-model.jsonl");
    assert_eq!(
        std::fs::read(&transcript).unwrap(),
        std::fs::read(fixture).unwrap(),
        "the fake Agent wrote its transcript fixture"
    );

    // The one-shot list agrees with the live one.
    let listed = conn.terminals().await.unwrap();
    assert_eq!(find(&listed, &agent.address).agent_status, Some(error));
    conn.disconnect().await;
}

#[tokio::test]
async fn terminals_started_and_removed_later_show_up_live() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();
    until(&watch, "the empty snapshot", |l| l.is_empty()).await;

    let agent = hc.run_fake_agent("late", "status blocked question Which color?\n");
    until(&watch, "the new agent to be Blocked", |l| {
        status_is(
            l,
            &agent.address,
            &AgentStatus::Blocked {
                reason: Some(BlockedReason::Question),
                message: Some("Which color?".into()),
            },
        )
    })
    .await;

    hc.hc(&["kill", &agent.address]);
    let list = until(&watch, "the killed agent to lose its status", |l| {
        l.iter()
            .any(|t| t.address == agent.address && t.state == "finished")
    })
    .await;
    assert_eq!(find(&list, &agent.address).agent_status, None);

    hc.hc(&["rm", &agent.address]);
    until(&watch, "the removed terminal to go", |l| {
        l.iter().all(|t| t.address != agent.address)
    })
    .await;
    conn.disconnect().await;
}

#[tokio::test]
async fn after_reconnecting_to_a_restarted_core_a_fresh_snapshot_is_used() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent(
        "survivor",
        "status working\nstatus done\nwait\nstatus working\nstatus blocked auth sign in again\n",
    );
    // Push the old core's event sequence well past where a new core starts.
    for i in 0..20 {
        hc.hc(&["label", &agent.address, &format!("n={i}")]);
    }

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id.clone()).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();
    until(&watch, "Done before the restart", |l| {
        status_is(l, &agent.address, &AgentStatus::Done)
    })
    .await;

    hc.hc(&["server", "restart"]);
    let ended = tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if watch.next().await.is_err() {
                break;
            }
        }
    })
    .await;
    assert!(
        ended.is_ok(),
        "the watch should end when the core goes away"
    );

    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();
    until(&watch, "Done from the new core's snapshot", |l| {
        status_is(l, &agent.address, &AgentStatus::Done)
    })
    .await;

    // Events from the new core start at a low seq again and must still apply.
    hc.hc(&["send", &agent.address, "continue"]);
    until(&watch, "Blocked from the new core's events", |l| {
        status_is(
            l,
            &agent.address,
            &AgentStatus::Blocked {
                reason: Some(BlockedReason::Auth),
                message: Some("sign in again".into()),
            },
        )
    })
    .await;
    conn.disconnect().await;
}
