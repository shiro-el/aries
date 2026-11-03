//! Seam 1: launching a Terminal from a custom command line, against a real
//! herdr-core over the local transport.

mod common;

use std::sync::Arc;

use aries_core::{AriesCore, CoreError, HostConnection, LaunchRequest};
use aries_test_support::{HcSession, repo_root};
use common::{core_in, local_endpoint};
use serde_json::{Value, json};

fn request(
    command: &str,
    cwd: Option<&str>,
    space: Option<&str>,
    tab: Option<&str>,
) -> LaunchRequest {
    LaunchRequest {
        command: command.into(),
        cwd: cwd.map(Into::into),
        space_id: space.map(Into::into),
        tab_id: tab.map(Into::into),
    }
}

async fn connect(core: &Arc<AriesCore>, host_id: &str) -> Arc<HostConnection> {
    core.connect(host_id.into()).await.unwrap()
}

fn doc_version(hc: &HcSession) -> Value {
    hc.rpc("doc.get", json!({ "name": "herdr-app" }))["version"].clone()
}

fn labels_of(hc: &HcSession, address: &str) -> Value {
    let list = hc.rpc("terminal.list", json!({}));
    list["terminals"]
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["address"] == address)
        .unwrap_or_else(|| panic!("{address} not listed"))["labels"]
        .clone()
}

fn normal_doc(alpha: &str, beta: &str, gamma: &str) -> Value {
    let path = repo_root().join("fixtures/herdr-app/normal.json");
    let text = std::fs::read_to_string(path)
        .unwrap()
        .replace("\"$alpha\"", &format!("\"{alpha}\""))
        .replace("\"$beta\"", &format!("\"{beta}\""))
        .replace("\"$gamma\"", &format!("\"{gamma}\""));
    serde_json::from_str(&text).unwrap()
}

#[tokio::test]
async fn launches_into_the_chosen_space_and_tab_without_writing_the_document() {
    let hc = HcSession::start();
    let alpha = hc.run_bg("alpha", &["sleep", "300"]);
    let beta = hc.run_bg("beta", &["sleep", "300"]);
    let gamma = hc.run_bg("gamma", &["sleep", "300"]);
    hc.set_doc("herdr-app", normal_doc(&alpha, &beta, &gamma));
    let version = doc_version(&hc);
    let cwd = tempfile::tempdir().unwrap();
    let cwd = cwd.path().to_string_lossy().into_owned();

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = connect(&core, &host.id).await;

    let t = conn
        .launch(request(
            "echo launched | cat; sleep 300",
            Some(&cwd),
            Some("s-aries"),
            Some("tab-empty"),
        ))
        .await
        .unwrap();

    assert_eq!(t.state, "live");
    assert_eq!(t.cwd.as_deref(), Some(cwd.as_str()));
    assert_eq!(
        labels_of(&hc, &t.address),
        json!({ "herdr.space": "s-aries", "herdr.tab": "tab-empty" })
    );
    // Shown at once in the chosen Tab, before the desktop adopts it.
    let s = conn.structure().await.unwrap();
    let tab = &s.spaces[0].tabs[1];
    assert_eq!(tab.id, "tab-empty");
    assert_eq!(
        tab.panes
            .iter()
            .map(|p| p.terminal_address.clone())
            .collect::<Vec<_>>(),
        vec![t.address.clone()]
    );
    assert!(s.unplaced.iter().all(|u| u.address != t.address));
    assert_eq!(
        doc_version(&hc),
        version,
        "launching must not write the document"
    );
    // The shell line really ran in that cwd.
    let screen = hc.hc(&["read", &t.address]);
    assert!(screen.contains("launched"), "{screen}");
    conn.disconnect().await;
}

#[tokio::test]
async fn a_plain_command_runs_as_argv_and_a_space_without_tab_gets_only_the_space_label() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = connect(&core, &host.id).await;

    let t = conn
        .launch(request("  sleep   300 ", None, Some("s9"), None))
        .await
        .unwrap();
    assert_eq!(t.argv, vec!["sleep", "300"]);
    assert_eq!(labels_of(&hc, &t.address), json!({ "herdr.space": "s9" }));

    let t = conn
        .launch(request("sleep 301", None, None, Some("ignored")))
        .await
        .unwrap();
    assert_eq!(labels_of(&hc, &t.address), json!({}));
    assert!(
        conn.terminals()
            .await
            .unwrap()
            .iter()
            .any(|x| x.address == t.address)
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn reports_why_a_launch_failed() {
    let hc = HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let conn = connect(&core, &host.id).await;

    for req in [
        request("/no/such/program --flag", None, None, None),
        request("sleep 300", Some("/no/such/dir"), None, None),
        request("echo hi; sleep 300", Some("/no/such/dir"), None, None),
    ] {
        match conn.launch(req).await {
            Err(CoreError::Rpc { kind, detail }) => {
                assert_eq!(kind, "spawn_failed");
                assert!(!detail.is_empty());
            }
            other => panic!("expected spawn_failed, got {other:?}"),
        }
    }
    assert!(
        conn.recent_cwds().is_empty(),
        "failed launches are not remembered"
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn remembers_recent_cwds_per_host_across_restarts_until_removed() {
    let hc = HcSession::start();
    let dirs: Vec<tempfile::TempDir> = (0..12).map(|_| tempfile::tempdir().unwrap()).collect();
    let path = |i: usize| dirs[i].path().to_string_lossy().into_owned();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let other = core.add_host("other".into(), local_endpoint(&hc)).unwrap();
    let conn = connect(&core, &host.id).await;

    for i in [0, 1, 0] {
        conn.launch(request("sleep 300", Some(&path(i)), None, None))
            .await
            .unwrap();
    }
    // No cwd: nothing to remember.
    conn.launch(request("sleep 300", None, None, None))
        .await
        .unwrap();
    assert_eq!(conn.recent_cwds(), vec![path(0), path(1)]);
    conn.disconnect().await;

    // Survives a relaunch of the app; other Hosts have their own list.
    let core = core_in(data.path());
    let conn = connect(&core, &host.id).await;
    assert_eq!(conn.recent_cwds(), vec![path(0), path(1)]);
    assert!(connect(&core, &other.id).await.recent_cwds().is_empty());

    for i in 2..12 {
        conn.launch(request("true", Some(&path(i)), None, None))
            .await
            .unwrap();
    }
    let recent = conn.recent_cwds();
    assert_eq!(recent.len(), aries_core::RECENT_CWDS_KEPT as usize);
    assert_eq!(recent[0], path(11));
    conn.disconnect().await;

    core.remove_host(host.id.clone()).unwrap();
    let again = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    assert!(connect(&core, &again.id).await.recent_cwds().is_empty());
    assert!(!data.path().join("hosts").join(&host.id).exists());
}
