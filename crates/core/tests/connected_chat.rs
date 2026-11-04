//! Reuses one established Host connection for live Chat inspection and answer.
mod common;

use aries_core::{AnswerProblem, ChatEntryBody, ChatStatus, CoreError};
use aries_test_support::HcSession;
use std::os::unix::fs::PermissionsExt;

// Catches reconnecting to SSH (or following forever) between a live request
// check and its answer, and treating a lost/malformed answer as success.
#[tokio::test]
async fn reads_and_answers_on_the_existing_connection_with_typed_uncertain_results() {
    let tools = tempfile::tempdir().unwrap();
    let beacon = tools.path().join("beacon");
    std::fs::write(&beacon, r##"#!/bin/sh
case "$1" in
chat)
    [ "$#" = 2 ] || exit 2
    printf '%s\n' '{"v":1,"type":"item","item":{"id":"a1","kind":"agent","text":"Ready"}}' '{"v":1,"type":"state","state":"live"}'
    ;;
answer)
    case "$3" in
    lost) printf 'not a reply\n' ;;
    gone) printf '%s\n' '{"v":1,"outcome":"gone"}' ;;
    *) printf '%s\n' '{"v":1,"outcome":"sent"}' ;;
    esac
    ;;
esac
"##).unwrap();
    std::fs::set_permissions(&beacon, std::fs::Permissions::from_mode(0o755)).unwrap();
    let hc = HcSession::start_with_env(&[("ARIES_BEACON", beacon.to_str().unwrap())]);
    let data = tempfile::tempdir().unwrap();
    let core = common::core_in(data.path());
    let host = core
        .add_host("Host".into(), common::local_endpoint(&hc))
        .unwrap();
    let connection = core.connect(host.id).await.unwrap();
    let chat = connection.read_chat("t1".into()).await.unwrap();
    assert_eq!(chat.status, ChatStatus::Live);
    assert!(matches!(&chat.entries[0].body, ChatEntryBody::Agent { text, .. } if text == "Ready"));
    connection
        .answer_question("t1".into(), "request".into(), vec![vec![1]])
        .await
        .unwrap();
    for (id, expected) in [
        ("lost", AnswerProblem::Failed),
        ("gone", AnswerProblem::Gone),
    ] {
        assert!(
            matches!(connection.answer_question("t1".into(), id.into(), vec![vec![0]]).await,
            Err(CoreError::AnswerRefused { reason, .. }) if reason == expected)
        );
    }
    connection.disconnect().await;
}
