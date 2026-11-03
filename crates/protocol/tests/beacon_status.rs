use aries_protocol::beacon::{BeaconStatus, VersionRange};

#[test]
fn status_line_round_trips_as_json() {
    let line =
        r#"{"version":"0.1.0","contract":1,"running":true,"core_version":"0.1.17","linger":false}"#;
    let status: BeaconStatus = serde_json::from_str(line).unwrap();
    assert_eq!(
        status,
        BeaconStatus {
            version: "0.1.0".into(),
            contract: 1,
            running: true,
            core_version: Some("0.1.17".into()),
            linger: false,
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
