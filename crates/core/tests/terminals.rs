//! Seam 1: the core API against a real herdr-core over the local transport.

mod common;

use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

#[tokio::test]
async fn lists_the_terminals_of_a_host() {
    let hc = HcSession::start();
    let first = hc.run_bg("first", &["sleep", "300"]);
    let second = hc.run_bg("second", &["sleep", "300"]);

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();

    let conn = core.connect(host.id.clone()).await.unwrap();
    let terminals = conn.terminals().await.unwrap();

    let mut seen: Vec<(String, Option<String>)> = terminals
        .iter()
        .map(|t| (t.address.clone(), t.name.clone()))
        .collect();
    seen.sort();
    assert_eq!(
        seen,
        vec![
            (first, Some("first".into())),
            (second, Some("second".into()))
        ]
    );
    assert_eq!(conn.core_version(), "0.1.17");
    conn.close().await;
}
