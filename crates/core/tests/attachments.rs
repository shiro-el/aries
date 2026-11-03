//! Seam 1: attachment bytes reach a Terminal's Host files folder; upload never submits input.
mod common;

use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

#[tokio::test]
async fn an_attachment_round_trips_binary_bytes_without_typing_into_the_terminal() {
    let hc = HcSession::start();
    let agent = hc.run_fake_agent("agent", "status idle\nwait\n");
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let connection = core.connect(host.id).await.unwrap();
    let bytes = vec![0, 255, 10, 13, 128];
    let path = connection
        .upload_attachment(agent.address.clone(), "photo.png".into(), bytes.clone())
        .await
        .unwrap();
    assert_eq!(std::fs::read(&path).unwrap(), bytes);
    assert!(std::path::Path::new(&path).is_absolute());
    assert!(agent.inputs().is_empty());
    let next = connection
        .upload_attachment(agent.address, "photo.png".into(), vec![42])
        .await
        .unwrap();
    assert_ne!(next, path, "a second attachment preserves the first");
    assert_eq!(std::fs::read(path).unwrap(), bytes);
}

#[tokio::test]
async fn raw_eight_mib_is_accepted_and_one_byte_more_is_rejected_before_rpc() {
    let hc = HcSession::start();
    let terminal = hc.run_bg("attachments", &["sleep", "60"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("dev".into(), local_endpoint(&hc)).unwrap();
    let connection = core.connect(host.id).await.unwrap();
    let path = connection
        .upload_attachment(terminal, "limit.bin".into(), vec![7; 8 * 1024 * 1024])
        .await
        .unwrap();
    assert_eq!(std::fs::metadata(path).unwrap().len(), 8 * 1024 * 1024);
    // An invalid Terminal distinguishes local validation from a server refusal.
    let error = connection
        .upload_attachment(
            "missing".into(),
            "large.bin".into(),
            vec![0; 8 * 1024 * 1024 + 1],
        )
        .await
        .unwrap_err();
    assert!(
        matches!(error, aries_core::CoreError::Rpc { ref kind, .. } if kind == "attachment_too_large"),
        "{error}"
    );
}
