//! Public core API seam against an isolated, real herdr-core.
mod common;
use aries_test_support::HcSession;
use common::{core_in, local_endpoint};

#[tokio::test]
async fn browses_terminal_folder_and_reads_whole_line_pages() {
    let hc = HcSession::start();
    let folder = tempfile::tempdir().unwrap();
    std::fs::write(folder.path().join("한글 'file\n.txt"), "one\n둘\nthree").unwrap();
    std::fs::create_dir(folder.path().join(".hidden folder")).unwrap();
    let address = hc.run_bg(
        "files",
        &[
            "/bin/sh",
            "-c",
            &format!("cd '{}' && exec sleep 300", folder.path().display()),
        ],
    );
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let listing = conn
        .list_files(address.clone(), Some(folder.path().display().to_string()))
        .await
        .unwrap();
    assert_eq!(listing.path, folder.path().display().to_string());
    assert_eq!(listing.entries.len(), 2);
    assert_eq!(listing.entries[0].name, ".hidden folder");
    assert!(listing.entries[0].directory);
    let path = format!("{}/한글 'file\n.txt", listing.path);
    let page = conn
        .read_file_page(address.clone(), path.clone(), 1, 2)
        .await
        .unwrap();
    assert_eq!(
        (page.text.as_str(), page.first, page.last, page.lines),
        ("one\n둘\n", 1, 2, 3)
    );
    let page = conn
        .read_file_page(address, path, page.last + 1, 2)
        .await
        .unwrap();
    assert_eq!(
        (page.text.as_str(), page.first, page.last, page.lines),
        ("three", 3, 3, 3)
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn starts_in_the_foreground_folder_and_reports_non_text_without_writing() {
    let hc = HcSession::start();
    let folder = tempfile::tempdir().unwrap();
    let address = hc.run_bg(
        "files",
        &[
            "/bin/sh",
            "-c",
            &format!("cd '{}' && exec sleep 300", folder.path().display()),
        ],
    );
    // Foreground is sampled asynchronously by herdr-core, not synchronously
    // by terminal.get. Wait until the fixture's cd is visible before browsing.
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            let terminal = hc.rpc("terminal.get", serde_json::json!({"terminal": address}));
            if terminal["foreground"]["cwd"].as_str() == folder.path().to_str() {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
    })
    .await
    .unwrap();
    std::fs::write(folder.path().join("bytes"), [0xff, 0xfe, 0]).unwrap();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let listing = conn.list_files(address.clone(), None).await.unwrap();
    assert_eq!(listing.path, folder.path().display().to_string());
    let path = folder.path().join("bytes").display().to_string();
    let error = conn
        .read_file_page(address, path.clone(), 1, 200)
        .await
        .unwrap_err();
    assert!(matches!(error, aries_core::CoreError::Rpc { kind, .. } if kind == "not_text"));
    assert_eq!(std::fs::read(path).unwrap(), [0xff, 0xfe, 0]);
    conn.disconnect().await;
}

#[tokio::test]
async fn response_budget_can_end_a_page_before_the_requested_line_limit() {
    let hc = HcSession::start();
    let folder = tempfile::tempdir().unwrap();
    let path = folder.path().join("large.txt");
    // Each 1 MiB raw line costs about 6 MiB after JSON escaping. A 2-line
    // request must stop at one line, then continue from the returned last.
    let line = "\u{1}".repeat(1024 * 1024) + "\n";
    std::fs::write(&path, format!("{line}{line}tail")).unwrap();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let page = conn
        .read_file_page("t1".into(), path.display().to_string(), 1, 200)
        .await
        .unwrap();
    assert_eq!((page.first, page.last, page.lines), (1, 1, 3));
    assert_eq!(page.text, line);
    let page = conn
        .read_file_page("t1".into(), path.display().to_string(), page.last + 1, 200)
        .await
        .unwrap();
    assert_eq!((page.first, page.last, page.lines), (2, 3, 3));
    assert_eq!(page.text, line + "tail");
    conn.disconnect().await;
}

#[tokio::test]
async fn forwards_listing_and_reads_to_the_terminals_machine() {
    let hc = HcSession::start();
    let remote = HcSession::start();
    let address = remote.run_bg("files", &["/bin/sleep", "300"]);
    hc.add_machine("box", &remote);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let address = format!("box/{address}");
    let directory = conn.list_files(address.clone(), None).await.unwrap();
    // Each core has its own working directory. machine.exec must run on box.
    let expected = remote.rpc(
        "terminal.get",
        serde_json::json!({"terminal": address.rsplit('/').next().unwrap()}),
    );
    assert_eq!(directory.path, expected["cwd"].as_str().unwrap());
    let read = conn
        .read_file_page(address, format!("{}/does-not-exist", directory.path), 1, 1)
        .await
        .unwrap_err();
    assert!(matches!(read, aries_core::CoreError::Rpc { kind, .. } if kind == "not_found"));
    conn.disconnect().await;
}
