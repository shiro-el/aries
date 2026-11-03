//! Seam 1: Host compatibility checks and protocol robustness.
//!
//! Hosts older than the pinned herdr-core cannot be run for real, so a tiny
//! shell script stands in for `herdr-core connect` and answers `hello` with a
//! canned reply. Everything else talks to the real pinned herdr-core.

mod common;

use aries_core::{CoreError, Incompatibility};
use aries_test_support::HcSession;
use common::{core_in, local_endpoint, script_endpoint};

const ALL_METHODS: &str = r#""hello","terminal.run","terminal.list","terminal.get","terminal.kill","terminal.label","terminal.read","terminal.attach","terminal.send","terminal.keys","terminal.interrupt","doc.get","doc.set","events.subscribe""#;

#[tokio::test]
async fn local_connections_ignore_inherited_core_routes() {
    const CHILD: &str = "ARIES_TEST_INHERITED_ROUTES";
    if std::env::var_os(CHILD).is_none() {
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "local_connections_ignore_inherited_core_routes",
                "--nocapture",
            ])
            .env(CHILD, "1")
            .env("HC_SOCKET", "/nonexistent/aries-review-socket")
            .env("HC_TERMINAL_SOCKET", "/nonexistent/aries-review-terminal")
            .status()
            .unwrap();
        assert!(status.success());
        return;
    }
    let hc = HcSession::start();
    hc.run_bg("isolated", &["sleep", "300"]);
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("test".into(), local_endpoint(&hc)).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    assert_eq!(
        conn.terminals().await.unwrap()[0].name.as_deref(),
        Some("isolated")
    );
    conn.disconnect().await;
}

/// A fake `herdr-core connect` that answers hello with `result` and stays open.
fn fake_core(result: &str) -> String {
    let reply = format!(r#"{{"jsonrpc":"2.0","id":0,"result":{result}}}"#);
    format!("read -r _; printf '%s\\n' '{reply}'; cat >/dev/null")
}

async fn connect_err(endpoint: aries_core::Endpoint) -> CoreError {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("h".into(), endpoint).unwrap();
    match core.connect(host.id).await {
        Ok(_) => panic!("connect should fail"),
        Err(e) => e,
    }
}

#[tokio::test]
async fn refuses_a_core_older_than_0_1_17() {
    let hello =
        format!(r#"{{"generation":1,"version":"0.1.16","framed":true,"methods":[{ALL_METHODS}]}}"#);
    let err = connect_err(script_endpoint(&fake_core(&hello))).await;
    assert!(
        matches!(
            &err,
            CoreError::Incompatible { reason: Incompatibility::CoreTooOld { version, minimum } }
                if version == "0.1.16" && minimum == "0.1.17"
        ),
        "{err:?}"
    );
}

#[tokio::test]
async fn asks_for_an_app_update_when_the_host_speaks_a_newer_generation() {
    let hello =
        format!(r#"{{"generation":2,"version":"1.0.0","framed":true,"methods":[{ALL_METHODS}]}}"#);
    let err = connect_err(script_endpoint(&fake_core(&hello))).await;
    assert!(
        matches!(
            err,
            CoreError::Incompatible {
                reason: Incompatibility::AppTooOld { host_generation: 2 }
            }
        ),
        "{err:?}"
    );
}

#[tokio::test]
async fn names_the_methods_the_host_lacks() {
    let methods = ALL_METHODS
        .replace(r#","doc.get""#, "")
        .replace(r#","terminal.keys""#, "");
    let hello =
        format!(r#"{{"generation":1,"version":"0.1.17","framed":true,"methods":[{methods}]}}"#);
    let err = connect_err(script_endpoint(&fake_core(&hello))).await;
    match err {
        CoreError::Incompatible {
            reason: Incompatibility::MissingMethods { mut methods },
        } => {
            methods.sort();
            assert_eq!(
                methods,
                vec!["doc.get".to_owned(), "terminal.keys".to_owned()]
            );
        }
        other => panic!("{other:?}"),
    }
}

#[tokio::test]
async fn tolerates_a_login_shell_that_prints_a_banner() {
    let hc = HcSession::start();
    hc.run_bg("work", &["sleep", "300"]);
    let mut endpoint = local_endpoint(&hc);
    if let aries_core::Endpoint::Local { argv, .. } = &mut endpoint {
        let core = argv[0].clone();
        *argv = vec![
            "/bin/sh".into(),
            "-c".into(),
            format!(
                "echo 'Welcome to the Host!'; printf 'motd without newline '; exec '{core}' connect"
            ),
        ];
    }
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core.add_host("noisy".into(), endpoint).unwrap();
    let conn = core.connect(host.id).await.unwrap();
    let names: Vec<_> = conn
        .terminals()
        .await
        .unwrap()
        .into_iter()
        .map(|t| t.name)
        .collect();
    assert_eq!(names, vec![Some("work".to_owned())]);
    conn.disconnect().await;
}

#[tokio::test]
async fn fails_clearly_when_the_host_floods_stdout() {
    let script = "read -r _; yes 'this is not the protocol' | head -c 200000; cat >/dev/null";
    let err = connect_err(script_endpoint(script)).await;
    assert!(matches!(err, CoreError::Protocol { .. }), "{err:?}");
}

#[tokio::test]
async fn fails_clearly_when_herdr_core_is_missing() {
    let err = connect_err(script_endpoint(
        "echo 'sh: herdr-core: not found' >&2; exit 127",
    ))
    .await;
    assert!(matches!(err, CoreError::Protocol { .. }), "{err:?}");
}
