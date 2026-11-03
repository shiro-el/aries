//! Beacon event-to-push seam, with a clock supplied at its boundary.
use aries_beacon::{progress::ProgressWatch, status_watch::StatusWatch};
use aries_protocol::{agent_status::AgentStatus, push::PushMessage};
use serde_json::json;

#[test]
fn watched_work_is_throttled_and_finishing_cleans_up_even_without_done_policy() {
    let mut status = StatusWatch::new();
    let mut progress = ProgressWatch::default();
    status.snapshot(&json!({"seq": 1, "terminals": [
        {"address":"t1", "created_ms":1,"status_ms":1000000,"status":{"state":"working"},"labels":{"aries.watch":"1"}},
        {"address":"t2", "parent":"t1", "status":{"state":"working"}},
        {"address":"t3", "parent":"t2", "status":{"state":"working"}},
        {"address":"t4", "status":{"state":"working"}}
    ]}));
    let messages = progress.update(status.watched(), 1_125_000);
    assert!(
        matches!(&messages[..], [PushMessage::Progress {terminal, elapsed_secs:125,child_agents:2,cancelled:false,..}] if terminal == "t1")
    );
    assert!(progress.update(status.watched(), 1_184_999).is_empty());
    assert!(matches!(
        &progress.update(status.watched(), 1_185_000)[..],
        [PushMessage::Progress {
            elapsed_secs: 185,
            ..
        }]
    ));
    status.replace("t1", Some(&json!({"address":"t1", "created_ms":1,"turn":1,"status":{"state":"done"},"labels":{"aries.watch":"1"}})));
    assert!(matches!(
        &progress.update(status.watched(), 1_186_000)[..],
        [PushMessage::Status {
            status: AgentStatus::Done,
            ..
        }]
    ));
    assert!(progress.update(status.watched(), 1_246_000).is_empty());
}

#[test]
fn unwatching_or_removing_a_terminal_cancels_progress_once() {
    let mut status = StatusWatch::new();
    let mut progress = ProgressWatch::default();
    let terminal = json!({"address":"t1", "created_ms":1,"status":{"state":"working"},"labels":{"aries.watch":"1"}});
    status.snapshot(&json!({"seq":1,"terminals":[terminal]}));
    assert_eq!(progress.update(status.watched(), 1_000_000).len(), 1);
    status.replace("t1", None);
    assert!(matches!(
        &progress.update(status.watched(), 1_001_000)[..],
        [PushMessage::Progress {
            cancelled: true,
            ..
        }]
    ));
    assert!(progress.update(status.watched(), 1_002_000).is_empty());
}
