//! Settings transactions through the core API, including independent handles.
mod common;

use std::sync::Barrier;

use aries_core::{AgentView, AttachMode};
use common::core_in;

#[test]
fn concurrent_settings_changes_survive_reopening() {
    let data = tempfile::tempdir().unwrap();
    let first = core_in(data.path());
    let second = core_in(data.path());
    let start = Barrier::new(4);
    for _ in 0..32 {
        first.set_attach_mode(AttachMode::Observe).unwrap();
        first.set_agent_view(AgentView::Terminal).unwrap();
        first.set_beacon_auto_update(true).unwrap();
        first.set_chat_cache_retention_days(30).unwrap();
        std::thread::scope(|threads| {
            let jobs: Vec<_> = [(&first, 0), (&second, 1), (&first, 2), (&second, 3)]
                .into_iter()
                .map(|(core, field)| {
                    let start = &start;
                    threads.spawn(move || {
                        start.wait();
                        match field {
                            0 => core.set_attach_mode(AttachMode::Input),
                            1 => core.set_agent_view(AgentView::Chat),
                            2 => core.set_beacon_auto_update(false),
                            _ => core.set_chat_cache_retention_days(7),
                        }
                    })
                })
                .collect();
            for job in jobs {
                job.join().unwrap().unwrap();
            }
        });
        let reopened = core_in(data.path());
        assert_eq!(reopened.attach_mode().unwrap(), AttachMode::Input);
        assert_eq!(reopened.agent_view().unwrap(), AgentView::Chat);
        assert!(!reopened.beacon_auto_update());
        assert_eq!(reopened.chat_cache_retention_days(), 7);
    }
}

#[test]
fn failed_settings_updates_preserve_the_active_policy_and_saved_data() {
    use aries_core::CoreError;

    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    core.set_chat_cache_retention_days(7).unwrap();
    let path = data.path().join("settings.json");
    let valid = std::fs::read(&path).unwrap();

    // A malformed file must not become an all-default settings document.
    let damaged = b"{broken settings";
    std::fs::write(&path, damaged).unwrap();
    assert!(matches!(
        core.set_chat_cache_retention_days(90),
        Err(CoreError::Storage { .. })
    ));
    assert!(matches!(
        core.set_attach_mode(AttachMode::Input),
        Err(CoreError::Storage { .. })
    ));
    assert!(matches!(
        core.set_agent_view(AgentView::Chat),
        Err(CoreError::Storage { .. })
    ));
    assert!(matches!(
        core.set_beacon_auto_update(false),
        Err(CoreError::Storage { .. })
    ));
    assert_eq!(core.chat_cache_retention_days(), 7);
    assert_eq!(std::fs::read(&path).unwrap(), damaged);

    // A read failure is not a missing file either.
    std::fs::remove_file(&path).unwrap();
    std::fs::create_dir(&path).unwrap();
    assert!(matches!(
        core.set_chat_cache_retention_days(90),
        Err(CoreError::Storage { .. })
    ));
    assert_eq!(core.chat_cache_retention_days(), 7);
    std::fs::remove_dir(&path).unwrap();
    std::fs::write(&path, valid).unwrap();

    // Failure to acquire the repository lock must not publish memory changes.
    let lock = data.path().join("settings.lock");
    std::fs::remove_file(&lock).unwrap();
    std::fs::create_dir(&lock).unwrap();
    assert!(matches!(
        core.set_chat_cache_retention_days(90),
        Err(CoreError::Storage { .. })
    ));
    assert_eq!(core.chat_cache_retention_days(), 7);
    assert_eq!(core_in(data.path()).chat_cache_retention_days(), 7);
}
