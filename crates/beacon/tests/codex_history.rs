//! The public Codex Source on real scrubbed backtrack rollouts. No process
//! discovery is faked here; the CLI regression covers pairing separately.

use std::io::Write;
use std::path::{Path, PathBuf};

use aries_beacon::chat::codex::Codex;
use aries_beacon::chat::{Agent, Batch, Located, Source};
use aries_protocol::chat::{ChatBody, ChatEvent};
use serde_json::{Value, json};

const THREAD: &str = "00000000-0000-4000-8000-000000000100";

struct History {
    _home: tempfile::TempDir,
    base: PathBuf,
    active: Located,
}

impl History {
    fn new() -> Self {
        let home = tempfile::tempdir().unwrap();
        let day = home.path().join("sessions/2026/10/29");
        std::fs::create_dir_all(&day).unwrap();
        let fixtures = aries_test_support::repo_root().join("fixtures/agents/codex/history-base");
        let mut base = None;
        let mut active = None;
        for entry in std::fs::read_dir(fixtures).unwrap().flatten() {
            let name = entry.file_name();
            if name.to_string_lossy().ends_with(".jsonl") {
                let path = day.join(&name);
                std::fs::copy(entry.path(), &path).unwrap();
                if name.to_string_lossy().contains('_') {
                    active = Some(path);
                } else {
                    base = Some(path);
                }
            }
        }
        Self {
            _home: home,
            base: base.unwrap(),
            active: Located {
                session: THREAD.into(),
                path: active.unwrap(),
            },
        }
    }

    fn edit_header(&self, edit: impl FnOnce(&mut Value)) {
        edit_header(&self.active.path, edit);
    }
}

fn edit_header(path: &Path, edit: impl FnOnce(&mut Value)) {
    let text = std::fs::read_to_string(path).unwrap();
    let (head, tail) = text.split_once('\n').unwrap();
    let mut head: Value = serde_json::from_str(head).unwrap();
    edit(&mut head);
    std::fs::write(path, format!("{head}\n{tail}")).unwrap();
}

type Events = Vec<(ChatEvent, Option<String>)>;

fn drain(source: &mut dyn Source) -> Events {
    let mut all = Vec::new();
    loop {
        match source.poll().unwrap() {
            Batch::Events { events, more } => {
                all.extend(events);
                if !more {
                    return all;
                }
            }
            _ => panic!("expected events"),
        }
    }
}

fn prompts(events: &Events) -> Vec<&str> {
    events
        .iter()
        .filter_map(|(event, _)| match event {
            ChatEvent::Item { item } => match &item.body {
                ChatBody::User { text, .. } => Some(text.as_str()),
                _ => None,
            },
            _ => None,
        })
        .collect()
}

#[test]
fn an_incomplete_utf8_header_waits_then_restores_history() {
    let history = History::new();
    history.edit_header(|head| head["payload"]["cwd"] = json!("/home/user/한글"));
    let full = std::fs::read(&history.active.path).unwrap();
    let cut = full
        .windows(3)
        .position(|bytes| bytes == "한".as_bytes())
        .unwrap()
        + 1;
    std::fs::write(&history.active.path, &full[..cut]).unwrap();
    let mut opened = Codex.open(&history.active, None);
    assert!(matches!(opened.source.poll().unwrap(), Batch::Missing));
    std::fs::write(&history.active.path, &full).unwrap();
    assert_eq!(
        prompts(&drain(&mut *opened.source)),
        [
            "Retained prompt 1",
            "Retained prompt 2",
            "Retained prompt 3",
            "Retained prompt 4",
            "Replacement prompt 1",
        ]
    );
}

#[test]
fn repeated_backtracks_resolve_rollout_ids_in_active_and_archived_storage() {
    let mut history = History::new();
    let archived = history._home.path().join("archived_sessions");
    std::fs::create_dir(&archived).unwrap();
    std::fs::rename(
        &history.base,
        archived.join(history.base.file_name().unwrap()),
    )
    .unwrap();
    let previous = std::fs::read(&history.active.path).unwrap();
    std::fs::rename(
        &history.active.path,
        archived.join(history.active.path.file_name().unwrap()),
    )
    .unwrap();
    history.active.path = history.active.path.with_file_name(format!(
        "rollout-2026-10-29T11-00-00-{THREAD}_00000000-0000-4000-8000-000000000102.jsonl"
    ));
    let header = json!({"type":"session_meta", "ordinal":642, "payload":{
        "id":THREAD, "history_base":{
            "thread_id":"00000000-0000-4000-8000-000000000101",
            "end_byte_offset":previous.len(), "end_ordinal_exclusive":642,
        }
    }});
    let prompt = json!({"type":"event_msg", "ordinal":643,"payload":{
        "type":"item_completed", "item":{"id":"latest-user", "type":"UserMessage", "content":[{"type":"text","text":"Latest prompt"}]}
    }});
    std::fs::write(&history.active.path, format!("{header}\n{prompt}\n")).unwrap();
    let mut opened = Codex.open(&history.active, None);
    assert_eq!(
        prompts(&drain(&mut *opened.source)),
        [
            "Retained prompt 1",
            "Retained prompt 2",
            "Retained prompt 3",
            "Retained prompt 4",
            "Replacement prompt 1",
            "Latest prompt",
        ]
    );
}

#[test]
fn compressed_archived_bases_use_decompressed_byte_boundaries() {
    let history = History::new();
    let archive = history._home.path().join("archived_sessions");
    std::fs::create_dir(&archive).unwrap();
    let compressed =
        zstd::stream::encode_all(std::fs::File::open(&history.base).unwrap(), 1).unwrap();
    let archived = archive.join(format!(
        "{}.zst",
        history.base.file_name().unwrap().to_str().unwrap()
    ));
    std::fs::write(&archived, &compressed).unwrap();
    std::fs::remove_file(&history.base).unwrap();
    let mut opened = Codex.open(&history.active, None);
    assert_eq!(
        prompts(&drain(&mut *opened.source)),
        [
            "Retained prompt 1",
            "Retained prompt 2",
            "Retained prompt 3",
            "Retained prompt 4",
            "Replacement prompt 1",
        ]
    );
    let cursor = opened.source.position();
    let mut resumed = Codex.open(&history.active, Some(&cursor));
    assert!(resumed.resumed);
    assert!(drain(&mut *resumed.source).is_empty());
    std::fs::write(&archived, &compressed[..compressed.len() / 2]).unwrap();
    let mut truncated = Codex.open(&history.active, Some(&cursor));
    assert!(!truncated.resumed);
    assert!(truncated.source.poll().is_err());
}

#[test]
fn every_active_resume_point_rebuilds_state_without_repeating_inherited_items() {
    let history = History::new();
    let mut opened = Codex.open(&history.active, None);
    let events = drain(&mut *opened.source);
    let mut prefix = Vec::new();
    let mut checked = 0;
    for (event, position) in &events {
        prefix.push(event.clone());
        if let Some(position) = position {
            let mut resumed = Codex.open(&history.active, Some(position));
            assert!(resumed.resumed);
            let mut joined = prefix.clone();
            joined.extend(
                drain(&mut *resumed.source)
                    .into_iter()
                    .map(|(event, _)| event),
            );
            assert_eq!(
                joined,
                events
                    .iter()
                    .map(|(event, _)| event.clone())
                    .collect::<Vec<_>>()
            );
            checked += 1;
        } else {
            assert!(matches!(event, ChatEvent::Item { item } if item.id.starts_with("retained-")));
        }
    }
    assert!(checked >= 3);
}

#[test]
fn reconnect_rebuilds_a_tool_started_in_the_base_and_completed_in_the_active_file() {
    let history = History::new();
    let header: Value = serde_json::from_str(
        std::fs::read_to_string(&history.active.path)
            .unwrap()
            .lines()
            .next()
            .unwrap(),
    )
    .unwrap();
    let boundary = header["payload"]["history_base"]["end_byte_offset"]
        .as_u64()
        .unwrap() as usize;
    let mut prefix = std::fs::read(&history.base).unwrap()[..boundary].to_vec();
    // A raw tool call is valid Chat state even without an item_completed.
    let call = json!({"type":"response_item", "ordinal":543,"payload":{
        "type":"function_call","call_id":"inherited-call","name":"shell_command", "arguments":"{\"command\":\"pwd\"}"
    }});
    prefix.extend_from_slice(format!("{call}\n").as_bytes());
    std::fs::write(&history.base, &prefix).unwrap();
    history.edit_header(|head| {
        head["ordinal"] = json!(544);
        head["payload"]["history_base"]["end_ordinal_exclusive"] = json!(544);
        head["payload"]["history_base"]["end_byte_offset"] = json!(prefix.len());
    });
    let mut opened = Codex.open(&history.active, None);
    drain(&mut *opened.source);
    let cursor = opened.source.position();
    let output = json!({"type":"response_item", "ordinal":642, "payload":{
        "type":"function_call_output","call_id":"inherited-call","output":"/home/user/work"
    }});
    let output = format!("{output}\n");
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(&history.active.path)
        .unwrap();
    file.write_all(&output.as_bytes()[..output.len() - 1])
        .unwrap();
    let mut resumed = Codex.open(&history.active, Some(&cursor));
    assert!(resumed.resumed);
    assert!(drain(&mut *resumed.source).is_empty());
    file.write_all(b"\n").unwrap();
    let events = drain(&mut *resumed.source);
    assert_eq!(events.len(), 1);
    assert!(matches!(&events[0].0, ChatEvent::Item { item }
        if item.id == "inherited-call" && matches!(&item.body,
            ChatBody::Tool { status: aries_protocol::chat::ToolStatus::Done, output: Some(output), .. }
                if output == "/home/user/work")));
}

#[test]
fn cursors_from_before_history_support_and_changed_bases_require_a_full_replay() {
    let history = History::new();
    let mut opened = Codex.open(&history.active, None);
    let expected = drain(&mut *opened.source);
    let cursor = opened.source.position();
    // Older releases only bound the bytes of the active file.
    let old_cursor = cursor.splitn(3, '.').nth(2).unwrap();
    let mut migrated = Codex.open(&history.active, Some(old_cursor));
    assert!(!migrated.resumed);
    assert_eq!(drain(&mut *migrated.source), expected);
    // A changed immutable prefix cannot reuse a cursor for its former contents.
    let base = std::fs::read_to_string(&history.base)
        .unwrap()
        .replace("Retained prompt 1", "Modified prompt 1");
    std::fs::write(&history.base, base).unwrap();
    let mut changed = Codex.open(&history.active, Some(&cursor));
    assert!(!changed.resumed);
    assert_eq!(
        prompts(&drain(&mut *changed.source))[0],
        "Modified prompt 1"
    );
}

#[test]
fn invalid_references_fail_before_emitting_a_partial_conversation() {
    for case in [
        "missing",
        "cycle",
        "byte",
        "ordinal",
        "mismatch",
        "truncated",
        "malformed",
    ] {
        let history = History::new();
        history.edit_header(|head| {
            let base = &mut head["payload"]["history_base"];
            match case {
                "missing" => base["thread_id"] = json!("00000000-0000-4000-8000-000000000999"),
                "cycle" => base["thread_id"] = json!("00000000-0000-4000-8000-000000000101"),
                "byte" => {
                    base["end_byte_offset"] = json!(base["end_byte_offset"].as_u64().unwrap() - 1)
                }
                "ordinal" => base["end_ordinal_exclusive"] = json!(544),
                "mismatch" => {
                    base["end_ordinal_exclusive"] = json!(544);
                    head["ordinal"] = json!(544);
                }
                "malformed" => base["thread_id"] = json!("../../outside"),
                _ => {}
            }
        });
        if case == "truncated" {
            std::fs::write(&history.base, "{}\n").unwrap();
        }
        let mut opened = Codex.open(&history.active, None);
        assert!(!opened.resumed, "{case}");
        assert!(opened.source.poll().is_err(), "{case}");
    }
}

#[test]
fn a_large_utf8_prefix_streams_in_chunks_without_reading_discarded_turns() {
    let history = History::new();
    let mut head: Value = serde_json::from_str(
        std::fs::read_to_string(&history.active.path)
            .unwrap()
            .lines()
            .next()
            .unwrap(),
    )
    .unwrap();
    let boundary = head["payload"]["history_base"]["end_byte_offset"]
        .as_u64()
        .unwrap() as usize;
    let mut prefix = std::fs::read(&history.base).unwrap()[..boundary].to_vec();
    let text = "한글🙂".repeat(500_000);
    let message = json!({"type":"event_msg", "ordinal":543,"payload":{
        "type":"item_completed","item":{"type":"AgentMessage","id":"large", "content":[{"type":"text","text":text}]}
    }});
    prefix.extend_from_slice(format!("{message}\n").as_bytes());
    std::fs::write(&history.base, &prefix).unwrap();
    head["ordinal"] = json!(544);
    head["payload"]["history_base"]["end_ordinal_exclusive"] = json!(544);
    head["payload"]["history_base"]["end_byte_offset"] = json!(prefix.len());
    let old = std::fs::read_to_string(&history.active.path).unwrap();
    std::fs::write(
        &history.active.path,
        format!("{head}\n{}", old.split_once('\n').unwrap().1),
    )
    .unwrap();
    let mut opened = Codex.open(&history.active, None);
    let events = drain(&mut *opened.source);
    assert!(events.iter().any(|(event, position)| matches!(event,
        ChatEvent::Item { item } if item.id == "large" && position.is_none()
            && matches!(&item.body, ChatBody::Agent { text: actual, .. } if actual == &text))));
    assert_eq!(prompts(&events).last(), Some(&"Replacement prompt 1"));
}
