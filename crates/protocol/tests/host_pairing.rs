use aries_protocol::host_pairing::PairingOffer;

fn offer() -> PairingOffer {
    serde_json::from_value(serde_json::json!({
        "version": 1, "address": "dev.tailnet", "port": 22, "user": "dev",
        "host_key": "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "pair_port": 4851, "pair_key": "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA",
        "token": "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA", "expires_at": 1300
    }))
    .unwrap()
}

#[test]
fn qr_round_trips_endpoint_pins_and_expiry_and_rejects_expired_or_future_versions() {
    let offer = offer();
    let qr = offer.to_qr().unwrap();
    assert!(qr.starts_with("aries-pair:"));
    let decoded = PairingOffer::from_qr(&qr, 1000).unwrap();
    assert_eq!(decoded.address, "dev.tailnet");
    assert_eq!(decoded.pair_port, 4851);
    assert_eq!(decoded.host_key, offer.host_key);
    assert_eq!(decoded.token, offer.token);
    assert!(PairingOffer::from_qr(&qr, 1300).is_err());
    assert!(PairingOffer::from_qr(&qr, 999).is_err());
    let mut next = offer;
    next.version = 2;
    assert!(PairingOffer::from_qr(&next.to_qr().unwrap(), 1000).is_err());
}

#[test]
fn qr_rejects_malformed_credentials_and_incomplete_or_unsafe_endpoints() {
    let valid = offer();
    for field in ["address", "user", "host_key", "pair_key", "token"] {
        let mut value = serde_json::to_value(&valid).unwrap();
        value[field] = serde_json::json!("");
        let bad: PairingOffer = serde_json::from_value(value).unwrap();
        assert!(PairingOffer::from_qr(&bad.to_qr().unwrap(), 1000).is_err());
    }
    let mut bad = valid.clone();
    bad.address = "dev\nother".into();
    assert!(PairingOffer::from_qr(&bad.to_qr().unwrap(), 1000).is_err());
    bad = valid.clone();
    bad.port = 0;
    assert!(PairingOffer::from_qr(&bad.to_qr().unwrap(), 1000).is_err());
    bad = valid;
    bad.pair_port = 0;
    assert!(PairingOffer::from_qr(&bad.to_qr().unwrap(), 1000).is_err());
    for qr in ["https://example.com/", "aries-pair:!!!", &"x".repeat(4097)] {
        assert!(PairingOffer::from_qr(qr, 1000).is_err());
    }
}
