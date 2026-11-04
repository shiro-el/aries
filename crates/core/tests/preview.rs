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

// D50: unavailable discovery tools must fail, and fixing them must allow an
// immediate Retry rather than reusing a cached empty result.
#[tokio::test]
async fn discovery_failure_is_distinct_from_no_servers_and_can_be_retried() {
    use std::os::unix::fs::PermissionsExt;
    let tools = tempfile::tempdir().unwrap();
    for name in ["ss", "lsof"] {
        let path = tools.path().join(name);
        std::fs::write(&path, "#!/bin/sh\nexit 2\n").unwrap();
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    let hc =
        aries_test_support::HcSession::start_with_env(&[("PATH", tools.path().to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = common::core_in(data.path());
    let host = core
        .add_host("preview".into(), common::local_endpoint(&hc))
        .unwrap();
    let connection = core.connect(host.id).await.unwrap();
    assert!(connection.preview_listening_ports().await.is_err());
    // macOS has no ss; lsof reports no matching listeners with exit 1.
    std::fs::remove_file(tools.path().join("ss")).unwrap();
    std::fs::write(tools.path().join("lsof"), "#!/bin/sh\nexit 1\n").unwrap();
    assert!(
        connection
            .preview_listening_ports()
            .await
            .unwrap()
            .is_empty()
    );
    connection.disconnect().await;
    assert!(matches!(
        connection.preview_listening_ports().await,
        Err(aries_core::CoreError::Closed)
    ));
}

// Regression: a shared Host's sshd/database listeners must not advertise previews.
#[tokio::test]
#[ignore = "needs docker"]
async fn discovery_keeps_http_and_filters_non_http_listeners() {
    let sshd = SshdHost::start();
    let setup = std::process::Command::new("docker")
        .args(["exec", sshd.network_name(), "sh", "-c", r#"
(while :; do printf 'HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n' | nc -l -p 5173 -w 1; done) >/tmp/preview-http.log 2>&1 &
printf '#!/bin/sh\nprintf "LISTEN 0 128 127.0.0.1:22 *:*\\nLISTEN 0 128 0.0.0.0:5173 *:*\\n"\n' > /usr/local/bin/ss
chmod +x /usr/local/bin/ss
"#]).status().unwrap();
    assert!(setup.success());
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
    assert_eq!(
        connection.preview_listening_ports().await.unwrap(),
        vec![5173]
    );
    // Discovery is cached for the live connection, including negative probes.
    let remove = std::process::Command::new("docker")
        .args(["exec", sshd.network_name(), "rm", "/usr/local/bin/ss"])
        .status()
        .unwrap();
    assert!(remove.success());
    assert_eq!(
        connection.preview_listening_ports().await.unwrap(),
        vec![5173]
    );
    connection.disconnect().await;
}

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
