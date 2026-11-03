use aries_protocol::beacon::{BeaconStatus, VersionRange};

#[test]
fn status_line_round_trips_as_json() {
    let line =
        r#"{"version":"0.1.0","contract":1,"running":true,"core_version":"0.1.17","linger":false}"#;
    let status: BeaconStatus = serde_json::from_str(line).unwrap();
    assert_eq!(serde_json::to_string(&status).unwrap(), line);
    assert_eq!(
        status,
        BeaconStatus {
            version: "0.1.0".into(),
            contract: 1,
            running: true,
            core_version: Some("0.1.17".into()),
            linger: false,
            skills: None,
        }
    );
}

#[test]
fn a_range_accepts_versions_from_its_minimum_up_to_its_limit() {
    let range = VersionRange::new("0.1.0", "0.2.0");
    assert!(range.contains("0.1.0"));
    assert!(range.contains("0.1.9"));
    assert!(range.contains("0.1.10"));
    assert!(!range.contains("0.0.9"));
    assert!(!range.contains("0.2.0"));
    assert!(!range.contains("1.0.0"));
    assert!(!range.contains("garbage"));
    assert_eq!(range.to_string(), ">=0.1.0, <0.2.0");
}

#[test]
fn status_with_skill_conflicts_round_trips_without_a_contract_bump() {
    let line = r#"{"version":"0.1.0","contract":1,"running":true,"core_version":"0.1.17","linger":false,"skills":{"roots":[{"path":"/home/u/.claude/skills/call-human","outcome":"kept_foreign"},{"path":"/home/u/.agents/skills/call-human","outcome":"kept_symlink"}]}}"#;
    let status: BeaconStatus = serde_json::from_str(line).unwrap();
    assert_eq!(status.contract, 1);
    let encoded = serde_json::to_value(&status).unwrap();
    assert_eq!(
        encoded,
        serde_json::from_str::<serde_json::Value>(line).unwrap()
    );
    assert_eq!(status.skills.unwrap().roots.len(), 2);
}

#[test]
fn report_keeps_legacy_cleanup_separate_and_accepts_older_reports() {
    use aries_protocol::beacon::SkillReport;
    let value = serde_json::json!({
        "roots": [{"path": "/home/u/.agents/skills/aries-call-human", "outcome": "installed"}],
        "legacy_roots": [{"path": "/home/u/.agents/skills/call-human", "outcome": "kept_foreign"}]
    });
    let report: SkillReport = serde_json::from_value(value.clone()).unwrap();
    assert_eq!(serde_json::to_value(report).unwrap(), value);
    let old = serde_json::json!({"roots": []});
    let report: SkillReport = serde_json::from_value(old.clone()).unwrap();
    assert_eq!(serde_json::to_value(report).unwrap(), old);
}
