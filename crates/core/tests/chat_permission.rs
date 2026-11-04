//! Permission cards through the phone core, real Beacon and isolated herdr-core.
mod common;

use aries_core::{AnswerProblem, ChatEntryBody, ChatStatus, CoreError, Endpoint};
use aries_test_support::HcSession;
use std::{collections::HashMap, time::Duration};

#[tokio::test]
async fn permission_cards_send_agent_specific_keys_and_refuse_stale_actions() {
    for (fixture, claude, expected) in [
        ("claude-write", true, &["31", "32", "33"][..]),
        ("claude-once", true, &["31", "32"][..]),
        ("codex-exec", false, &["79", "70", "1b"][..]),
        ("codex-edit", false, &["79", "61", "1b"][..]),
        ("codex-once", false, &["79", "1b"][..]),
    ] {
        let labels = if expected.len() == 3 {
            vec!["Allow", "Allow always", "Deny"]
        } else {
            vec!["Allow", "Deny"]
        };
        for (pick, expected) in expected.iter().enumerate() {
            let dir = tempfile::tempdir().unwrap();
            let data = dir.path().join("data");
            let core = common::core_in(&data);
            let hc = HcSession::start();
            let config = dir.path().join("claude");
            let transcript = if claude {
                config.join("projects/-work-a/S1.jsonl")
            } else {
                dir.path().join("sessions/rollout-2026-11-04T00-00-00-01a1a012-3af0-7c01-97e4-7e2adbc2ae4f.jsonl")
            };
            std::fs::create_dir_all(transcript.parent().unwrap()).unwrap();
            std::fs::write(&transcript, "").unwrap();
            let setup = if claude {
                format!("claude-session {} S1 /work/a\n", config.display())
            } else {
                format!("hold {}\n", transcript.display())
            };
            let screen = std::fs::read_to_string(
                std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join(format!("../../fixtures/agents/permissions/{fixture}.txt")),
            )
            .unwrap();
            let changed_scope = fixture == "claude-write" && pick == 0;
            let render = |screen: &str| {
                screen
                    .lines()
                    .map(|s| format!("say {s}\n"))
                    .collect::<String>()
            };
            let next = if changed_scope {
                // Same turn, different approval scope: an old notification
                // must not approve it, even though status stays permission.
                format!(
                    "{}status blocked permission\nkeys 1\n",
                    render(&screen.replace("/work/a", "/work/b"))
                )
            } else {
                String::new()
            };
            let agent = hc.run_fake_agent(
                "permission",
                &format!(
                    "{setup}{}status blocked permission\nkeys 1\n{next}status working\n",
                    render(&screen)
                ),
            );
            common::wait_state(&hc, &agent.address, "blocked");
            let mut vars: HashMap<String, String> = hc.env().into_iter().collect();
            vars.insert(
                "ARIES_BEACON".into(),
                aries_test_support::beacon_bin().display().to_string(),
            );
            vars.insert("CLAUDE_CONFIG_DIR".into(), config.display().to_string());
            vars.insert(
                "PATH".into(),
                format!("{}:/usr/bin:/bin", hc.bin().parent().unwrap().display()),
            );
            let host = core
                .add_host(
                    "h".into(),
                    Endpoint::Local {
                        argv: vec![],
                        env: vars,
                    },
                )
                .unwrap()
                .id;
            let watch = core
                .watch_chat_retrying(
                    host.clone(),
                    agent.address.clone(),
                    None,
                    Duration::from_millis(50),
                )
                .unwrap();
            let id = tokio::time::timeout(Duration::from_secs(10), async {
                loop {
                    let snapshot = watch.next().await.unwrap();
                    if snapshot.status == ChatStatus::Live
                        && let Some(entry) = snapshot.entries.iter().find(|e| matches!(&e.body, ChatEntryBody::Question { name, .. } if name == "permission")) {
                            if let ChatEntryBody::Question { questions, .. } = &entry.body {
                                assert_eq!(questions[0].options.iter().map(|o| o.label.as_str()).collect::<Vec<_>>(), labels);
                                assert!(questions[0].text.contains("hello"));
                            }
                            break entry.id.clone();
                    }
                }
            }).await.unwrap_or_else(|e| panic!("{e}: {} screen {}", hc.hc(&["get", &agent.address, "--json"]), hc.rpc("terminal.read", serde_json::json!({"terminal":agent.address,"lines":0}))));
            // Both a cold notification worker and a foreground Chat use the same checked answer.
            let cold = common::core_in(&data);
            let stale = cold
                .answer_question_background(
                    host.clone(),
                    agent.address.clone(),
                    format!("{id}-old"),
                    vec![vec![pick as u32]],
                )
                .await;
            assert!(
                matches!(
                    stale,
                    Err(CoreError::AnswerRefused {
                        reason: AnswerProblem::Gone,
                        ..
                    })
                ),
                "{stale:?}"
            );
            assert!(agent.inputs().is_empty());
            cold.answer_question_background(
                host.clone(),
                agent.address.clone(),
                id.clone(),
                vec![vec![pick as u32]],
            )
            .await
            .unwrap_or_else(|e| panic!("{fixture} pick {pick}: {e:?}"));
            for _ in 0..100 {
                if !agent.inputs().is_empty() {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            assert_eq!(
                agent.inputs(),
                [format!("keys {expected}")],
                "{fixture} pick {pick}"
            );
            if changed_scope {
                let next =
                    tokio::time::timeout(Duration::from_secs(10), async {
                        loop {
                            let snapshot = watch.next().await.unwrap();
                            if let Some(entry) = snapshot.entries.iter().find(|entry| {
                            matches!(&entry.body, ChatEntryBody::Question { name, questions, .. }
                                if name == "permission" && questions[0].text.contains("/work/b"))
                        }) { break entry.id.clone(); }
                        }
                    })
                    .await
                    .expect("changed permission scope");
                assert_ne!(id, next);
                let stale = cold
                    .answer_question_background(
                        host.clone(),
                        agent.address.clone(),
                        id.clone(),
                        vec![vec![0]],
                    )
                    .await;
                assert!(
                    matches!(
                        stale,
                        Err(CoreError::AnswerRefused {
                            reason: AnswerProblem::Gone,
                            ..
                        })
                    ),
                    "{stale:?}"
                );
                assert_eq!(
                    agent.inputs().len(),
                    1,
                    "the stale scope sends no second key"
                );
                cold.answer_question_background(
                    host.clone(),
                    agent.address.clone(),
                    next,
                    vec![vec![2]],
                )
                .await
                .unwrap();
            }
            common::wait_state(&hc, &agent.address, "working");
            assert!(
                cold.answer_question_background(host, agent.address.clone(), id, vec![vec![0]])
                    .await
                    .is_err()
            );
            tokio::time::timeout(Duration::from_secs(10), async {
                loop {
                    let snapshot = watch.next().await.unwrap();
                    if !snapshot
                        .entries
                        .iter()
                        .any(|entry| entry.id.starts_with("permission:"))
                    {
                        break;
                    }
                }
            })
            .await
            .expect("resolved permission leaves Chat");
            watch.stop();
        }
    }
}
