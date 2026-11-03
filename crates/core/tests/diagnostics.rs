//! Seam 1: bounded, local diagnostic history never stores protocol/error payloads.
mod common;

use aries_core::{DiagnosticSource, Endpoint};
use common::core_in;

#[tokio::test]
async fn connection_outcomes_survive_relaunch_without_host_credentials() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "private-label".into(),
            Endpoint::Ssh {
                address: "secret-address".into(),
                port: 22,
                user: "secret-login".into(),
            },
        )
        .unwrap();
    assert!(core.connect(host.id.clone()).await.is_err());
    let entries = core
        .diagnostics(Some(DiagnosticSource::Ssh), Some(host.id.clone()), true)
        .unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].host_id.as_deref(), Some(host.id.as_str()));
    assert!(entries[0].timestamp_ms > 0);
    assert!(entries[0].error.is_some());
    drop(core);
    let reopened = core_in(data.path());
    assert_eq!(reopened.diagnostics(None, None, false).unwrap(), entries);
    let stored = std::fs::read_to_string(data.path().join("diagnostics.json")).unwrap();
    for secret in ["private-label", "secret-address", "secret-login"] {
        assert!(!stored.contains(secret));
    }
}

#[tokio::test]
async fn beacon_and_relay_failures_keep_categories_but_never_error_details() {
    use aries_core::{
        DiagnosticError, DiagnosticEvent, FetchError, HttpClient, HttpRequest, HttpResponse,
        PushDevice, PushProgress, PushStep,
    };
    use std::sync::Arc;
    struct RejectRelay;
    impl HttpClient for RejectRelay {
        fn send(&self, _request: HttpRequest) -> Result<HttpResponse, FetchError> {
            Err(FetchError::Failed {
                detail: "secret-error-token private-body".into(),
            })
        }
    }
    struct Progress;
    impl PushProgress for Progress {
        fn on_step(&self, _: PushStep) {}
    }
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let host = core
        .add_host(
            "dev".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port: 1,
                user: "u".into(),
            },
        )
        .unwrap();
    assert!(core.beacon_status(host.id.clone()).await.is_err());
    core.ensure_key().unwrap();
    assert!(
        core.enable_push(
            host.id.clone(),
            "https://private-relay.invalid".into(),
            PushDevice {
                platform: "fcm".into(),
                token: "secret-fcm-token".into()
            },
            Arc::new(RejectRelay),
            Arc::new(Progress)
        )
        .await
        .is_err()
    );
    let beacon = core
        .diagnostics(Some(DiagnosticSource::Beacon), Some(host.id.clone()), true)
        .unwrap();
    assert_eq!(beacon[0].event, DiagnosticEvent::PushEnable);
    assert_eq!(beacon[1].error, Some(DiagnosticError::KeyUnavailable));
    let relay = core
        .diagnostics(Some(DiagnosticSource::Relay), Some(host.id), true)
        .unwrap();
    assert_eq!(relay.len(), 1);
    assert_eq!(relay[0].error, Some(DiagnosticError::Unreachable));
    let json = serde_json::to_string(&core.diagnostics(None, None, false).unwrap()).unwrap();
    let stored = std::fs::read_to_string(data.path().join("diagnostics.json")).unwrap();
    for secret in [
        "secret-error-token",
        "private-body",
        "private-relay",
        "secret-fcm-token",
        "PRIVATE KEY",
    ] {
        assert!(!json.contains(secret));
        assert!(!stored.contains(secret));
    }
}

#[test]
fn rejects_unknown_pushes_without_storing_envelopes_and_bounds_history() {
    use aries_core::{DIAGNOSTICS_KEPT, DiagnosticError};
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    for _ in 0..520 {
        assert!(
            core.open_push(b"private-envelope-content".to_vec())
                .is_err()
        );
    }
    let records = core
        .diagnostics(Some(DiagnosticSource::Push), None, true)
        .unwrap();
    assert_eq!(records.len(), DIAGNOSTICS_KEPT);
    assert!(
        records
            .iter()
            .all(|entry| entry.host_id.is_none()
                && entry.error == Some(DiagnosticError::PushRejected))
    );
    let stored = std::fs::read_to_string(data.path().join("diagnostics.json")).unwrap();
    assert!(!stored.contains("private-envelope-content"));
    assert_eq!(
        serde_json::from_str::<Vec<serde_json::Value>>(&stored)
            .unwrap()
            .len(),
        500
    );
    drop(core);
    assert_eq!(
        core_in(data.path())
            .diagnostics(None, None, false)
            .unwrap()
            .len(),
        500
    );
}

#[tokio::test]
async fn host_removal_erases_its_history_without_erasing_other_hosts() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let first = core
        .add_host("first".into(), common::script_endpoint("true"))
        .unwrap();
    let second = core
        .add_host("second".into(), common::script_endpoint("true"))
        .unwrap();
    assert!(core.connect(first.id.clone()).await.is_err());
    assert!(core.connect(second.id.clone()).await.is_err());
    core.remove_host(first.id.clone()).unwrap();
    assert!(
        core.diagnostics(None, Some(first.id), false)
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        core.diagnostics(None, Some(second.id), false)
            .unwrap()
            .len(),
        1
    );
}

#[test]
fn independent_core_handles_preserve_concurrent_events() {
    let data = tempfile::tempdir().unwrap();
    let first = core_in(data.path());
    let second = core_in(data.path());
    std::thread::scope(|scope| {
        for core in [first.clone(), second] {
            scope.spawn(move || {
                for _ in 0..20 {
                    assert!(core.open_push(vec![0]).is_err());
                }
            });
        }
    });
    assert_eq!(first.diagnostics(None, None, false).unwrap().len(), 40);
}
