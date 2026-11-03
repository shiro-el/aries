//! Seam 1: Agents across Hosts and the machines a Host added, and the shared
//! `herdr.seen` / `herdr.pinned` labels, against real herdr-cores and the
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

fn get<'a>(list: &'a [TerminalInfo], address: &str) -> Option<&'a TerminalInfo> {
    list.iter().find(|t| t.address == address)
}

fn has(list: &[TerminalInfo], address: &str, f: impl Fn(&TerminalInfo) -> bool) -> bool {
    get(list, address).is_some_and(f)
}

/// A Terminal's labels as herdr-core itself reports them.
fn labels(hc: &HcSession, address: &str) -> serde_json::Value {
    let out = hc.hc(&["get", address, "--json"]);
    let v: serde_json::Value = serde_json::from_str(out.trim()).expect("get json");
    v["labels"].clone()
}

#[tokio::test]
async fn viewing_an_agent_at_turn_zero_joins_the_shared_seen_rules() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status working\n");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();
    until(&watch, "Working at turn zero", |list| {
        has(list, &agent.address, |t| {
            t.agent_status == Some(AgentStatus::Working) && t.turn == 0
        })
    })
    .await;

    conn.mark_seen(agent.address.clone(), 0).await.unwrap();
    assert_eq!(labels(&hc, &agent.address)["herdr.seen"], "0");

    // Existing higher labels survive stale viewing snapshots.
    hc.hc(&["label", &agent.address, "herdr.seen=2"]);
    conn.mark_seen(agent.address.clone(), 0).await.unwrap();
    assert_eq!(labels(&hc, &agent.address)["herdr.seen"], "2");
    watch.stop();
    conn.disconnect().await;
}

#[tokio::test]
async fn seen_and_pinned_are_shared_with_other_clients_both_ways() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent(
        "agent",
        "status working\nstatus done\nwait\nstatus working\nstatus done\n",
    );
    let a = agent.address.as_str();

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();

    let list = until(&watch, "the first Done", |l| {
        has(l, a, |t| t.agent_status == Some(AgentStatus::Done))
    })
    .await;
    assert!(!get(&list, a).unwrap().pinned);

    // The desktop pins it and marks the turn seen: the phone follows live.
    hc.hc(&["label", a, "herdr.pinned=1"]);
    until(&watch, "the desktop's pin", |l| has(l, a, |t| t.pinned)).await;
    hc.hc(&["label", a, "herdr.seen=1"]);
    until(&watch, "Idle after the desktop saw it", |l| {
        has(l, a, |t| t.agent_status == Some(AgentStatus::Idle))
    })
    .await;

    // A new turn finishes; the phone confirms the Done.
    hc.hc(&["send", a, "go on"]);
    let list = until(&watch, "the second Done", |l| {
        has(l, a, |t| t.agent_status == Some(AgentStatus::Done))
    })
    .await;
    let turn = get(&list, a).unwrap().turn;
    assert_eq!(turn, 2);
    let after = conn.mark_seen(a.into(), turn).await.unwrap();
    assert_eq!(after.agent_status, Some(AgentStatus::Idle));
    until(&watch, "Idle after the phone saw it", |l| {
        has(l, a, |t| t.agent_status == Some(AgentStatus::Idle))
    })
    .await;
    // What the desktop reads.
    assert_eq!(labels(&hc, a)["herdr.seen"], "2");

    // A stale confirmation never takes seen backwards.
    conn.mark_seen(a.into(), 1).await.unwrap();
    assert_eq!(labels(&hc, a)["herdr.seen"], "2");

    // The phone unpins it; the label goes away for everyone.
    let after = conn.set_pinned(a.into(), false).await.unwrap();
    assert!(!after.pinned);
    assert!(labels(&hc, a).get("herdr.pinned").is_none());
    until(&watch, "the unpin", |l| has(l, a, |t| !t.pinned)).await;
    let after = conn.set_pinned(a.into(), true).await.unwrap();
    assert!(after.pinned);
    assert_eq!(labels(&hc, a)["herdr.pinned"], "1");

    watch.stop();
    conn.disconnect().await;
}

#[tokio::test]
async fn a_machine_the_host_added_shows_its_agents_under_their_full_address() {
    let remote = HcSession::start();
    let there = remote.run_fake_agent("agent", "status working\nstatus done\n");
    let hc = HcSession::start();
    let here = hc.run_fake_agent("agent", "status blocked question Which color?\n");
    assert_eq!(
        here.address, there.address,
        "both cores call it the same tN"
    );
    hc.add_machine("box", &remote);
    let boxed = format!("box/{}", there.address);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("home".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let watch = conn.watch_terminals().await.unwrap();

    let blocked = AgentStatus::Blocked {
        reason: Some(BlockedReason::Question),
        message: Some("Which color?".into()),
    };
    let list = until(&watch, "both Agents", |l| {
        has(l, &boxed, |t| t.agent_status == Some(AgentStatus::Done))
            && has(l, &here.address, |t| {
                t.agent_status == Some(blocked.clone())
            })
    })
    .await;
    assert_eq!(get(&list, &boxed).unwrap().machine.as_deref(), Some("box"));
    assert_eq!(get(&list, &here.address).unwrap().machine, None);

    // Labels written through the Host land on the machine's Terminal, not on
    // the Host's own Terminal with the same tN.
    let turn = get(&list, &boxed).unwrap().turn;
    conn.set_pinned(boxed.clone(), true).await.unwrap();
    conn.mark_seen(boxed.clone(), turn).await.unwrap();
    let remote_labels = labels(&remote, &there.address);
    assert_eq!(remote_labels["herdr.pinned"], "1");
    assert_eq!(remote_labels["herdr.seen"], turn.to_string());
    let local_labels = labels(&hc, &here.address);
    assert!(local_labels.get("herdr.pinned").is_none(), "{local_labels}");
    assert!(local_labels.get("herdr.seen").is_none(), "{local_labels}");
    until(&watch, "the machine's Agent pinned and Idle", |l| {
        has(l, &boxed, |t| {
            t.pinned && t.agent_status == Some(AgentStatus::Idle)
        }) && has(l, &here.address, |t| !t.pinned && t.needs_attention)
    })
    .await;

    // A client on that machine changes it: the phone sees it through the Host.
    remote.hc(&["label", &there.address, "herdr.pinned="]);
    until(&watch, "the machine-side unpin", |l| {
        has(l, &boxed, |t| !t.pinned)
    })
    .await;

    watch.stop();
    conn.disconnect().await;
}

#[tokio::test]
async fn two_hosts_with_the_same_terminal_address_stay_apart() {
    let one = HcSession::start();
    let a1 = one.run_fake_agent("agent", "status working\nstatus done\n");
    let two = HcSession::start();
    let a2 = two.run_fake_agent("agent", "status working\n");
    assert_eq!(a1.address, a2.address);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let h1 = core.add_host("home".into(), local_endpoint(&one)).unwrap();
    let h2 = core
        .add_host("server".into(), local_endpoint(&two))
        .unwrap();
    assert_eq!(core.hosts().len(), 2);
    let c1 = core.connect(h1.id).await.unwrap();
    let c2 = core.connect(h2.id).await.unwrap();
    let w1 = c1.watch_terminals().await.unwrap();
    let w2 = c2.watch_terminals().await.unwrap();

    let l1 = until(&w1, "home's Agent Done", |l| {
        has(l, &a1.address, |t| {
            t.agent_status == Some(AgentStatus::Done)
        })
    })
    .await;
    until(&w2, "server's Agent Working", |l| {
        has(l, &a2.address, |t| {
            t.agent_status == Some(AgentStatus::Working)
        })
    })
    .await;

    let turn = get(&l1, &a1.address).unwrap().turn;
    c1.mark_seen(a1.address.clone(), turn).await.unwrap();
    c1.set_pinned(a1.address.clone(), true).await.unwrap();
    assert_eq!(labels(&one, &a1.address)["herdr.pinned"], "1");
    let other = labels(&two, &a2.address);
    assert!(other.get("herdr.pinned").is_none(), "{other}");
    assert!(other.get("herdr.seen").is_none(), "{other}");

    w1.stop();
    w2.stop();
    c1.disconnect().await;
    c2.disconnect().await;
}
