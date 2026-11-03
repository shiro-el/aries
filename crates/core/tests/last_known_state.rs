//! Public core seam: Last-known state is pulled, survives relaunch and is removed with its Host.
mod common;

use aries_core::{AgentStatus, CoreError};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint, wait_state};

#[tokio::test]
async fn snapshot_persists_agents_and_fetch_time_and_removal_cleans_it() {
    let hc = HcSession::start();
    let plain = hc.run_bg("plain", &["sleep", "300"]);
    let agent = hc.run_fake_agent("writer", "status working\nwait\n");
    wait_state(&hc, &agent.address, "working");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    assert!(core.last_known_state(host.id.clone()).unwrap().is_none());

    let state = core.clone().snapshot(host.id.clone()).await.unwrap();
    assert_eq!(state.host_id, host.id);
    assert_eq!(state.agents.len(), 1);
    let stored = &state.agents[0];
    assert_eq!(stored.address, agent.address);
    assert_eq!(stored.name.as_deref(), Some("writer"));
    assert_eq!(stored.agent_status, Some(AgentStatus::Working));
    assert!(stored.status_ms.is_some());
    assert!(state.fetched_at_ms >= stored.status_ms.unwrap());
    assert!(!state.agents.iter().any(|a| a.address == plain));
    assert_eq!(
        core.last_known_state(host.id.clone()).unwrap(),
        Some(state.clone())
    );

    drop(core);
    let core = core_in(data.path());
    assert_eq!(core.last_known_state(host.id.clone()).unwrap(), Some(state));
    core.remove_host(host.id.clone()).unwrap();
    assert!(core.last_known_states().unwrap().is_empty());
    assert!(matches!(
        core.last_known_state(host.id.clone()),
        Err(CoreError::UnknownHost { .. })
    ));
    assert!(!data.path().join("hosts").join(&host.id).exists());
}

#[tokio::test]
async fn live_lists_replace_stored_state_and_notify_readers_including_removal() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("writer", "status working\nwait\nstatus done\nwait\n");
    wait_state(&hc, &agent.address, "working");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let changes = core.watch_last_known_states();
    assert!(changes.next().await.unwrap().is_empty());
    let conn = core.connect(host.id.clone()).await.unwrap();
    conn.terminals().await.unwrap();
    let first = changes.next().await.unwrap();
    assert_eq!(first[0].agents[0].agent_status, Some(AgentStatus::Working));
    let first_ms = first[0].agents[0].status_ms.unwrap();

    let watch = conn.watch_terminals().await.unwrap();
    watch.next().await.unwrap();
    hc.hc(&["send", &agent.address, "finish"]);
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            let states = changes.next().await.unwrap();
            if states[0].agents[0].agent_status == Some(AgentStatus::Done) {
                assert!(states[0].agents[0].status_ms.unwrap() > first_ms);
                break;
            }
        }
    })
    .await
    .expect("live status saved with server time");
    core.remove_host(host.id.clone()).unwrap();
    assert!(changes.next().await.unwrap().is_empty());
    // A late live update cannot recreate a removed Host's state.
    assert!(conn.terminals().await.is_err());
    assert!(core.last_known_states().unwrap().is_empty());
    changes.stop();
    assert!(matches!(changes.next().await, Err(CoreError::Closed)));
    watch.stop();
    conn.disconnect().await;
}

#[tokio::test]
async fn hosts_are_independent_and_a_failed_refresh_keeps_its_old_fetch_time() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("writer", "status working\nwait\n");
    wait_state(&hc, &agent.address, "working");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let first = core.add_host("first".into(), local_endpoint(&hc)).unwrap();
    let other = core.add_host("other".into(), local_endpoint(&hc)).unwrap();
    let old = core.clone().snapshot(first.id.clone()).await.unwrap();
    assert!(core.last_known_state(other.id.clone()).unwrap().is_none());
    core.edit_host(
        first.id.clone(),
        "first".into(),
        common::script_endpoint("exit 1"),
    )
    .await
    .unwrap();
    assert!(core.clone().snapshot(first.id.clone()).await.is_err());
    assert_eq!(core.last_known_state(first.id.clone()).unwrap(), Some(old));
    core.clone().snapshot(other.id.clone()).await.unwrap();
    core.remove_host(first.id).unwrap();
    assert_eq!(core.last_known_states().unwrap()[0].host_id, other.id);
}

#[tokio::test]
#[ignore = "needs docker"]
async fn snapshot_uses_short_ssh_connection_to_real_herdr_core() {
    use aries_core::{Endpoint, LaunchRequest};
    use aries_test_support::SshdHost;
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    sshd.authorize(&core.ensure_key().unwrap().openssh);
    let host = core
        .add_host(
            "box".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: sshd.port(),
                user: SshdHost::USER.into(),
            },
        )
        .unwrap();
    // Snapshot follows the same trust policy as foreground connections.
    assert!(matches!(
        core.clone().snapshot(host.id.clone()).await,
        Err(CoreError::HostKeyUnknown { .. })
    ));
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();
    let conn = core.connect(host.id.clone()).await.unwrap();
    let terminal = conn
        .launch(LaunchRequest {
            command: r#"printf '\033]7501;state=working\033\\'; sleep 60"#.into(),
            cwd: None,
            space_id: None,
            tab_id: None,
            preset: None,
        })
        .await
        .unwrap();
    conn.disconnect().await;
    let state = tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            let state = core.clone().snapshot(host.id.clone()).await.unwrap();
            if !state.agents.is_empty() {
                break state;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(state.agents[0].address, terminal.address);
    assert_eq!(state.agents[0].agent_status, Some(AgentStatus::Working));
    assert!(state.agents[0].status_ms.is_some());
}

#[tokio::test]
async fn snapshot_retains_child_agents_and_their_parent_addresses() {
    use serde_json::json;
    let hc = HcSession::start();
    let parent = hc.run_fake_agent("parent", "status working\nwait\n");
    let child = hc.rpc(
        "terminal.run",
        json!({
            "argv": ["sh", "-c", "printf '\\033]7501;state=working\\033\\\\'; sleep 60"],
            "name": "child", "parent": parent.address,
        }),
    );
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let state = tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            let state = core.clone().snapshot(host.id.clone()).await.unwrap();
            if state.agents.len() == 2 {
                break state;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let stored = state
        .agents
        .iter()
        .find(|t| t.address == child["address"].as_str().unwrap())
        .unwrap();
    assert_eq!(stored.parent.as_deref(), Some(parent.address.as_str()));
    assert_eq!(stored.name.as_deref(), Some("child"));
    assert!(stored.status_ms.is_some());
    assert_eq!(
        core_in(data.path()).last_known_state(host.id).unwrap(),
        Some(state)
    );
}

#[tokio::test]
async fn quick_open_structure_survives_relaunch_and_live_renames() {
    use serde_json::json;
    let hc = HcSession::start();
    hc.set_doc(
        "herdr-app",
        json!({"spaces": [{"id": "s1", "name": "Aries", "folder": "/repo", "tabs": [
            {"id": "tab1", "name": "Review", "root": {"id": "r", "type": "row", "children": []}}
        ]}]}),
    );
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let state = core.clone().snapshot(host.id.clone()).await.unwrap();
    let structure = state.structure.unwrap();
    assert_eq!(structure.spaces[0].name, "Aries");
    assert_eq!(structure.spaces[0].tabs[0].name, "Review");
    assert_eq!(
        core_in(data.path())
            .last_known_state(host.id.clone())
            .unwrap()
            .unwrap()
            .structure,
        Some(structure)
    );

    hc.set_doc(
        "herdr-app",
        json!({"spaces": [{"id": "s1", "name": "Renamed", "folder": "/repo", "tabs": []}]}),
    );
    let conn = core.connect(host.id.clone()).await.unwrap();
    conn.structure().await.unwrap();
    // Terminal status refreshes must keep the last structure.
    conn.terminals().await.unwrap();
    assert_eq!(
        core.last_known_state(host.id)
            .unwrap()
            .unwrap()
            .structure
            .unwrap()
            .spaces[0]
            .name,
        "Renamed"
    );
    conn.disconnect().await;
}

#[test]
fn quick_open_reads_agent_only_state_from_previous_versions() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host("dev".into(), common::script_endpoint("exit 1"))
        .unwrap();
    let dir = data.path().join("hosts").join(&host.id);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(
        dir.join("last-known-state.json"),
        serde_json::json!({
            "host_id": host.id, "agents": [], "fetched_at_ms": 1234
        })
        .to_string(),
    )
    .unwrap();
    let state = core.last_known_state(host.id).unwrap().unwrap();
    assert_eq!(state.fetched_at_ms, 1234);
    assert!(state.structure.is_none());
    assert!(state.structure_fetched_at_ms.is_none());
}
