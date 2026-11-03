//! Seam 1: Space / Tab / Pane structure from the `herdr-app` document,
//! against a real herdr-core over the local transport.

mod common;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use aries_core::{HostStructure, StructureListener, StructureProblem};
use aries_test_support::{HcSession, repo_root};
use common::{core_in, local_endpoint};
use serde_json::{Value, json};

/// A fixture document with `$name` replaced by that Terminal's address.
fn fixture(file: &str, terminals: &[(&str, &str)]) -> Value {
    let path = repo_root().join("fixtures/herdr-app").join(file);
    let mut text = std::fs::read_to_string(&path).expect("fixture");
    for (name, address) in terminals {
        text = text.replace(&format!("\"${name}\""), &format!("\"{address}\""));
    }
    serde_json::from_str(&text).expect("fixture json")
}

fn doc_version(hc: &HcSession) -> Value {
    hc.rpc("doc.get", json!({ "name": "herdr-app" }))["version"].clone()
}

/// `space/tab: pane=terminal` lines, `!` marking a Pane whose Terminal is gone.
fn outline(s: &HostStructure) -> Vec<String> {
    let mut out = vec![];
    for space in &s.spaces {
        out.push(format!(
            "space {} {:?} {:?}",
            space.id, space.name, space.folder
        ));
        for tab in &space.tabs {
            out.push(format!("  tab {} {:?}", tab.id, tab.name));
            for pane in &tab.panes {
                let gone = if pane.terminal.is_some() { "" } else { "!" };
                out.push(format!(
                    "    pane {}={}{gone}",
                    pane.id, pane.terminal_address
                ));
            }
        }
    }
    out
}

fn unplaced(s: &HostStructure) -> Vec<String> {
    let mut v: Vec<String> = s.unplaced.iter().map(|t| t.address.clone()).collect();
    v.sort();
    v
}

async fn connect(hc: &HcSession) -> (tempfile::TempDir, Arc<aries_core::HostConnection>) {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    (data, conn)
}

#[tokio::test]
async fn shows_the_documents_spaces_tabs_and_panes_without_writing_it() {
    let hc = HcSession::start();
    let alpha = hc.run_bg("alpha", &["sleep", "300"]);
    let beta = hc.run_bg("beta", &["sleep", "300"]);
    let gamma = hc.run_bg("gamma", &["sleep", "300"]);
    let outside = hc.run_bg("outside", &["sleep", "300"]);
    hc.set_doc(
        "herdr-app",
        fixture(
            "normal.json",
            &[("alpha", &alpha), ("beta", &beta), ("gamma", &gamma)],
        ),
    );
    let version = doc_version(&hc);

    let (_data, conn) = connect(&hc).await;
    let s = conn.structure().await.unwrap();

    assert_eq!(s.problem, None);
    assert_eq!(
        outline(&s),
        vec![
            "space s-aries \"aries\" Some(\"/home/dev/aries\")".to_string(),
            "  tab tab-build \"build\"".into(),
            format!("    pane p-alpha={alpha}"),
            format!("    pane p-beta={beta}"),
            format!("    pane f-gamma={gamma}"),
            "  tab tab-empty \"\"".into(),
            "space s-notes \"notes\" None".into(),
            "  tab tab-gone \"old\"".into(),
            "    pane p-gone=t999!".into(),
        ]
    );
    let alpha_pane = &s.spaces[0].tabs[0].panes[0];
    assert_eq!(
        alpha_pane.terminal.as_ref().unwrap().name.as_deref(),
        Some("alpha")
    );
    assert_eq!(unplaced(&s), vec![outside]);
    assert_eq!(
        doc_version(&hc),
        version,
        "the core must never write the document"
    );
    conn.disconnect().await;
}

/// Terminals labelled the way herdr-app labels the ones it starts, plus one
/// started some other way.
fn labelled_terminals(hc: &HcSession) -> (String, String, String, String) {
    let a = hc.run_labeled(
        "a",
        &[("herdr.space", "s1"), ("herdr.tab", "tab-x")],
        &["sleep", "300"],
    );
    let b = hc.run_labeled(
        "b",
        &[("herdr.space", "s1"), ("herdr.tab", "tab-y")],
        &["sleep", "300"],
    );
    let c = hc.run_labeled(
        "c",
        &[("herdr.space", "s2"), ("herdr.tab", "tab-x")],
        &["sleep", "300"],
    );
    let plain = hc.run_bg("plain", &["sleep", "300"]);
    (a, b, c, plain)
}

async fn assert_label_fallback(hc: &HcSession, problem: StructureProblem) {
    let (a, b, c, plain) = labelled_terminals(hc);
    let (_data, conn) = connect(hc).await;
    let s = conn.structure().await.unwrap();
    assert_eq!(s.problem, Some(problem));
    assert_eq!(
        outline(&s),
        vec![
            "space s1 \"\" None".to_string(),
            "  tab tab-x \"\"".into(),
            format!("    pane {a}={a}"),
            "  tab tab-y \"\"".into(),
            format!("    pane {b}={b}"),
            "space s2 \"\" None".into(),
            "  tab tab-x \"\"".into(),
            format!("    pane {c}={c}"),
        ]
    );
    assert_eq!(unplaced(&s), vec![plain]);
    conn.disconnect().await;
}

#[tokio::test]
async fn without_a_document_groups_terminals_by_label_and_warns() {
    let hc = HcSession::start();
    assert_label_fallback(&hc, StructureProblem::NoDocument).await;
    assert_eq!(
        doc_version(&hc),
        json!(0),
        "the core must never write the document"
    );
}

#[tokio::test]
async fn a_broken_document_falls_back_to_labels_and_warns() {
    let hc = HcSession::start();
    hc.set_doc("herdr-app", fixture("broken.json", &[]));
    let version = doc_version(&hc);
    let detail = {
        let (_data, conn) = connect(&hc).await;
        let s = conn.structure().await.unwrap();
        conn.disconnect().await;
        match s.problem {
            Some(StructureProblem::Unreadable { detail }) => detail,
            other => panic!("{other:?}"),
        }
    };
    assert!(detail.contains("tabs"), "{detail}");
    assert_label_fallback(&hc, StructureProblem::Unreadable { detail }).await;
    assert_eq!(doc_version(&hc), version);
}

#[tokio::test]
async fn an_unknown_document_format_falls_back_to_labels_and_warns() {
    let hc = HcSession::start();
    hc.set_doc("herdr-app", fixture("unknown-format.json", &[]));
    let version = doc_version(&hc);
    assert_label_fallback(&hc, StructureProblem::UnknownFormat).await;
    assert_eq!(doc_version(&hc), version);
}

/// Records every structure the watch delivers.
#[derive(Default)]
struct Recorder {
    seen: Mutex<Vec<HostStructure>>,
}

impl StructureListener for Recorder {
    fn on_structure(&self, structure: HostStructure) {
        self.seen.lock().unwrap().push(structure);
    }
    fn on_closed(&self) {}
}

impl Recorder {
    /// Waits until the latest structure satisfies `ok`.
    async fn wait_for(&self, what: &str, ok: impl Fn(&HostStructure) -> bool) -> HostStructure {
        for _ in 0..200 {
            if let Some(last) = self.seen.lock().unwrap().last()
                && ok(last)
            {
                return last.clone();
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        panic!(
            "timed out waiting for {what}; last: {:?}",
            self.seen.lock().unwrap().last()
        );
    }
}

#[tokio::test]
async fn desktop_changes_to_the_structure_show_up_right_away() {
    let hc = HcSession::start();
    let alpha = hc.run_bg("alpha", &["sleep", "300"]);
    let (_data, conn) = connect(&hc).await;
    let rec = Arc::new(Recorder::default());
    let watch = conn.watch_structure(rec.clone()).await.unwrap();

    rec.wait_for("the first structure", |s| {
        s.problem == Some(StructureProblem::NoDocument) && s.unplaced.len() == 1
    })
    .await;

    // The desktop writes its document: a `doc` event, then a re-read.
    let beta = hc.run_bg("beta", &["sleep", "300"]);
    let gamma = hc.run_bg("gamma", &["sleep", "300"]);
    hc.set_doc(
        "herdr-app",
        fixture(
            "normal.json",
            &[("alpha", &alpha), ("beta", &beta), ("gamma", &gamma)],
        ),
    );
    let version = doc_version(&hc);
    let s = rec
        .wait_for("the document's spaces", |s| s.problem.is_none())
        .await;
    assert_eq!(s.spaces.len(), 2);
    assert!(s.unplaced.is_empty(), "{:?}", s.unplaced);

    // The desktop renames a Space.
    let mut doc = fixture(
        "normal.json",
        &[("alpha", &alpha), ("beta", &beta), ("gamma", &gamma)],
    );
    doc["spaces"][0]["name"] = json!("renamed");
    hc.set_doc("herdr-app", doc);
    rec.wait_for("the renamed space", |s| {
        s.spaces.first().is_some_and(|sp| sp.name == "renamed")
    })
    .await;

    // A Terminal started outside herdr-app appears as unplaced.
    let late = hc.run_bg("late", &["sleep", "300"]);
    rec.wait_for("the new terminal", |s| {
        s.unplaced.iter().any(|t| t.address == late)
    })
    .await;

    watch.stop();
    // Watching never writes the document (one write by the test since `version`).
    assert_eq!(doc_version(&hc), json!(version.as_u64().unwrap() + 1));
    conn.disconnect().await;
}

#[tokio::test]
async fn sub_agents_follow_their_parent_into_its_pane_and_watch_updates() {
    let hc = HcSession::start();
    let parent = hc.run_bg("parent", &["sleep", "300"]);
    hc.set_doc("herdr-app", fixture("normal.json", &[("alpha", &parent)]));
    let (_data, conn) = connect(&hc).await;
    let rec = Arc::new(Recorder::default());
    let watch = conn.watch_structure(rec.clone()).await.unwrap();
    rec.wait_for("parent without children", |s| {
        !s.spaces.is_empty() && s.spaces[0].tabs[0].panes[0].sub_agents.is_empty()
    })
    .await;
    let child: Value = serde_json::from_str(&hc.hc(&[
        "run",
        "--bg",
        "--json",
        "--no-notify",
        "--parent",
        &parent,
        "--name",
        "child",
        "--",
        "sleep",
        "300",
    ]))
    .unwrap();
    let address = child["address"].as_str().unwrap();
    let s = conn.structure().await.unwrap();
    let pane = &s.spaces[0].tabs[0].panes[0];
    rec.wait_for("child under parent", |s| {
        !s.spaces[0].tabs[0].panes[0].sub_agents.is_empty()
    })
    .await;
    assert_eq!(pane.sub_agents.len(), 1);
    assert_eq!(pane.sub_agents[0].address, address);
    assert_eq!(pane.sub_agents[0].parent.as_deref(), Some(parent.as_str()));
    assert!(!s.unplaced.iter().any(|t| t.address == address));
    assert_eq!(
        conn.terminals()
            .await
            .unwrap()
            .iter()
            .find(|t| t.address == address)
            .unwrap()
            .parent
            .as_deref(),
        Some(parent.as_str())
    );
    hc.hc(&["kill", address]);
    hc.hc(&["rm", address]);
    rec.wait_for("child removed", |s| {
        s.spaces[0].tabs[0].panes[0].sub_agents.is_empty()
    })
    .await;
    watch.stop();
    conn.disconnect().await;
}
