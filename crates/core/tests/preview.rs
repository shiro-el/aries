use aries_core::preview_ports;

#[test]
fn extracts_dev_server_ports_without_partial_or_lookalike_matches() {
    assert_eq!(
        preview_ports("Local: http://localhost:5173/\nhttp://127.0.0.1:3000 http://0.0.0.0:8080 localhost:5173".into()),
        vec![5173, 3000, 8080]
    );
    assert_eq!(
        preview_ports("localhost:0 localhost:65536 localhost:999999 localhost:123abc mylocalhost:80 localhost.evil:80 127.0.0.1:65535 https://localhost:443/".into()),
        vec![65535, 443]
    );
}

mod common;
use aries_core::Endpoint;
use aries_test_support::SshdHost;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[tokio::test]
#[ignore = "needs docker"]
async fn forwards_only_on_loopback_and_closes_streams_with_its_owner() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = common::core_in(data.path());
    sshd.authorize(&core.ensure_key().unwrap().openssh);
    let host = core
        .add_host(
            "preview".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: sshd.port(),
                user: SshdHost::USER.into(),
            },
        )
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();
    let connection = core.connect(host.id).await.unwrap();
    // Forward sshd's own banner. Revoking keys after connect proves this reuses
    // that authenticated session rather than logging in for the preview.
    // (The fixture is private to this test; no default session is accessed.)
    let revoke = connection
        .machine_exec(
            vec![
                "sh".into(),
                "-c".into(),
                "cat /dev/null > ~/.ssh/authorized_keys".into(),
            ],
            None,
            3,
        )
        .await
        .unwrap();
    assert_eq!(revoke.code, Some(0));
    assert!(connection.open_preview(0).await.is_err());
    let forward = connection.open_preview(22).await.unwrap();
    let address: std::net::SocketAddr = forward
        .url()
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .parse()
        .unwrap();
    assert_eq!(address.ip(), std::net::Ipv4Addr::LOCALHOST);
    // Wildcard binding would prevent a second listener on another loopback IP.
    let other =
        std::net::TcpListener::bind((std::net::Ipv4Addr::new(127, 0, 0, 2), address.port()))
            .unwrap();
    let mut stream = tokio::net::TcpStream::connect(address).await.unwrap();
    let mut banner = [0; 4];
    tokio::time::timeout(Duration::from_secs(5), stream.read_exact(&mut banner))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(&banner, b"SSH-");
    stream.write_all(b"SSH-2.0-preview-test\r\n").await.unwrap();
    forward.stop().await;
    forward.stop().await; // idempotent
    assert!(tokio::net::TcpStream::connect(address).await.is_err());
    let mut drain = Vec::new();
    tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut drain))
        .await
        .unwrap()
        .unwrap();
    drop(other);
    // Closing a preview leaves the Host connection useful.
    connection.terminals().await.unwrap();
    let forward = connection.open_preview(22).await.unwrap();
    let address = forward
        .url()
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .to_owned();
    connection.disconnect().await;
    tokio::time::timeout(Duration::from_secs(5), async {
        while tokio::net::TcpStream::connect(&address).await.is_ok() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    forward.stop().await;
}
