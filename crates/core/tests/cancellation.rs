//! Dropping a core API future owns and cancels its SSH work, including
//! a stalled hello against a real herdr-core in an isolated container.
mod common;

use std::process::Command;
use std::time::Duration;

use aries_core::Endpoint;
use aries_test_support::SshdHost;
use common::core_in;

fn remote(host: &SshdHost, script: &str) -> String {
    let out = Command::new("docker")
        .args(["exec", host.network_name(), "sh", "-c", script])
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8(out.stdout).unwrap()
}

#[tokio::test]
#[ignore = "needs docker"]
async fn dropping_connect_ends_the_ssh_work_while_the_real_core_hello_is_withheld() {
    let sshd = SshdHost::start();
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let public = core.ensure_key().unwrap().openssh;
    // A forced command runs the actual pinned core under an explicit isolated
    // session, but withholds its hello reply from the phone.
    remote(&sshd, &format!(
        "printf '%s\\n' '{}' > /home/aries/.ssh/authorized_keys; chown aries:aries /home/aries/.ssh/authorized_keys",
        format!(r#"command="env HC_SESSION=aries177-cancel /usr/local/bin/herdr-core connect > /tmp/withheld-hello" {public}"#).trim()
    ));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let upstream = sshd.port();
    let proxy = tokio::spawn(async move {
        let (mut phone, _) = listener.accept().await.unwrap();
        let mut host = tokio::net::TcpStream::connect(("127.0.0.1", upstream))
            .await
            .unwrap();
        tokio::io::copy_bidirectional(&mut phone, &mut host).await
    });
    let host = core
        .add_host(
            "isolated".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port,
                user: SshdHost::USER.into(),
            },
        )
        .unwrap();
    core.trust_host_key(host.id.clone(), sshd.host_key_fingerprint())
        .unwrap();
    let mut connecting = Box::pin(core.connect(host.id));
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            tokio::select! {
                result = &mut connecting => panic!("hello should be withheld: {:?}", result.err()),
                _ = tokio::time::sleep(Duration::from_millis(50)) => {}
            }
            if remote(&sshd, "cat /tmp/withheld-hello 2>/dev/null || true").contains("generation") {
                break;
            }
        }
    })
    .await
    .expect("real herdr-core answered inside the isolated SSH Host");
    drop(connecting);
    tokio::time::timeout(Duration::from_secs(3), proxy)
        .await
        .expect("cancelled connect must end its SSH connection promptly")
        .unwrap()
        .unwrap();
}

/// A transparent bridge performs hello against the isolated real core,
/// then stops draining the phone after the next frame header. Large writes
/// block in the OS pipe instead of depending on a fake protocol server.
fn stalled_endpoint(hc: &aries_test_support::HcSession, marker: &std::path::Path) -> Endpoint {
    let bridge = r#"
import os, subprocess, sys, time
p = subprocess.Popen(sys.argv[2:], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
try:
    p.stdin.write(sys.stdin.buffer.readline()); p.stdin.flush()
    sys.stdout.buffer.write(p.stdout.readline()); sys.stdout.buffer.flush()
    sys.stdin.buffer.read(9)
    open(sys.argv[1], 'w').close()
    time.sleep(120)
finally:
    p.kill(); p.wait()
"#;
    let mut argv = vec![
        "python3".into(),
        "-c".into(),
        bridge.into(),
        marker.to_string_lossy().into_owned(),
    ];
    argv.extend(hc.connect_argv());
    Endpoint::Local {
        argv,
        env: hc.env().into_iter().collect(),
    }
}

async fn started(marker: &std::path::Path) {
    tokio::time::timeout(Duration::from_secs(5), async {
        while !marker.exists() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("the upload sent its frame header");
}

#[tokio::test]
async fn cancelling_a_partial_frame_retires_the_connection_instead_of_corrupting_the_next_rpc() {
    let hc = aries_test_support::HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let marker = data.path().join("writing");
    let core = core_in(data.path());
    let host = core
        .add_host("stalled".into(), stalled_endpoint(&hc, &marker))
        .unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let mut upload =
        Box::pin(conn.upload_attachment("t1".into(), "large.bin".into(), vec![7; 4 * 1024 * 1024]));
    tokio::select! {
        result = &mut upload => panic!("must block while writing: {result:?}"),
        _ = started(&marker) => {}
    }
    drop(upload);
    let result = tokio::time::timeout(Duration::from_secs(3), conn.terminals())
        .await
        .expect("the cancelled writer must release every queued RPC");
    assert!(
        matches!(result, Err(aries_core::CoreError::Closed)),
        "{result:?}"
    );
    conn.disconnect().await;
}

#[tokio::test]
async fn an_rpc_deadline_includes_a_blocked_writer_and_the_wait_for_its_lock() {
    let hc = aries_test_support::HcSession::start();
    let data = tempfile::tempdir().unwrap();
    let marker = data.path().join("writing");
    let core = core_in(data.path());
    let host = core
        .add_host("stalled".into(), stalled_endpoint(&hc, &marker))
        .unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let mut upload =
        Box::pin(conn.upload_attachment("t1".into(), "large.bin".into(), vec![7; 4 * 1024 * 1024]));
    tokio::select! {
        result = &mut upload => panic!("must block while writing: {result:?}"),
        _ = started(&marker) => {}
    }
    tokio::time::timeout(Duration::from_secs(33), async {
        let (upload, queued) = tokio::join!(upload, conn.terminals());
        assert!(upload.is_err());
        assert!(queued.is_err());
    })
    .await
    .expect("the 30 s RPC budget covers write and lock contention");
    conn.disconnect().await;
}
