//! The Beacon's side of push: the pairing with one phone and sending sealed
//! envelopes to the Relay.
//!
//! The pairing lives in `<state>/pairing.json` (mode 0600): Relay URL,
//! pairing token, the phone's public key and the Beacon's own key pair.
//! Later tickets send their notifications with [`send`].
//!
//! Relay API (see `relay/src/index.ts`): `POST /v1/push` with
//! `Authorization: Bearer <token>` and the envelope as the body;
//! `DELETE /v1/pairing` with the same header.

use std::io::Read;
use std::path::PathBuf;
use std::time::Duration;

use aries_protocol::push::{
    KeyPair, PairReply, PairRequest, PublicKey, PushMessage, UnpairReply, seal,
};
use serde::{Deserialize, Serialize};

/// The pairing with one phone, as stored on the Host.
pub struct Pairing {
    pub relay_url: String,
    pub token: String,
    pub phone_key: PublicKey,
    pub beacon: KeyPair,
}

#[derive(Serialize, Deserialize)]
struct Stored {
    relay_url: String,
    token: String,
    phone_key: String,
    beacon_secret: String,
}

/// Why a push did not reach the Relay.
#[derive(Debug, thiserror::Error)]
pub enum SendError {
    #[error("not paired with a phone")]
    NotPaired,
    #[error("pairing file unreadable: {0}")]
    BadPairing(String),
    #[error("{0}")]
    Envelope(#[from] aries_protocol::push::EnvelopeError),
    #[error("relay unreachable: {0}")]
    Unreachable(String),
    /// The Relay answered with an error status (401 unknown pairing,
    /// 429 rate limited, 410 phone gone, 502 FCM failed, ...).
    #[error("relay answered {status}: {body}")]
    Relay { status: u16, body: String },
}

fn pairing_file() -> PathBuf {
    crate::state_dir().join("pairing.json")
}

impl Pairing {
    pub fn load() -> Result<Option<Self>, SendError> {
        let bytes = match std::fs::read(pairing_file()) {
            Ok(b) => b,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(SendError::BadPairing(e.to_string())),
        };
        let s: Stored =
            serde_json::from_slice(&bytes).map_err(|e| SendError::BadPairing(e.to_string()))?;
        Ok(Some(Self {
            phone_key: s.phone_key.parse()?,
            beacon: KeyPair::from_secret_text(&s.beacon_secret)?,
            relay_url: s.relay_url,
            token: s.token,
        }))
    }

    fn save(&self) -> Result<(), String> {
        use std::os::unix::fs::OpenOptionsExt;
        let dir = crate::state_dir();
        std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        let body = serde_json::to_vec(&Stored {
            relay_url: self.relay_url.clone(),
            token: self.token.clone(),
            phone_key: self.phone_key.to_text(),
            beacon_secret: self.beacon.secret_text(),
        })
        .expect("pairing json");
        let tmp = dir.join(".pairing.json.tmp");
        let _ = std::fs::remove_file(&tmp);
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)
            .map_err(|e| format!("{}: {e}", tmp.display()))?;
        std::io::Write::write_all(&mut f, &body).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, pairing_file()).map_err(|e| e.to_string())
    }
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(20)))
        .build()
        .into()
}

fn relay_error(mut resp: ureq::http::Response<ureq::Body>) -> SendError {
    let status = resp.status().as_u16();
    let body = resp
        .body_mut()
        .with_config()
        .limit(4096)
        .read_to_string()
        .unwrap_or_default();
    SendError::Relay { status, body }
}

/// Seals `message` for the paired phone and hands it to the Relay. The
/// envelope is not kept anywhere.
pub fn send(message: &PushMessage) -> Result<(), SendError> {
    let pairing = Pairing::load()?.ok_or(SendError::NotPaired)?;
    let envelope = seal(message, &pairing.phone_key, &pairing.beacon)?;
    let resp = agent()
        .post(format!("{}/v1/push", pairing.relay_url))
        .header("Authorization", format!("Bearer {}", pairing.token))
        .content_type("application/octet-stream")
        .send(&envelope[..])
        .map_err(|e| SendError::Unreachable(e.to_string()))?;
    if resp.status().is_success() {
        Ok(())
    } else {
        Err(relay_error(resp))
    }
}

/// Deletes `token`'s pairing on the Relay; `true` once the Relay confirmed.
fn delete_on_relay(relay_url: &str, token: &str) -> bool {
    agent()
        .delete(format!("{relay_url}/v1/pairing"))
        .header("Authorization", format!("Bearer {token}"))
        .call()
        .map(|r| r.status().is_success())
        .unwrap_or(false)
}

/// `aries-beacon pair`: reads a [`PairRequest`] on stdin, stores the
/// pairing with a fresh Beacon key pair and prints a [`PairReply`]. A
/// previous pairing's token is revoked on its Relay first (best effort).
pub fn pair_command() -> Result<(), String> {
    let mut input = String::new();
    std::io::stdin()
        .read_to_string(&mut input)
        .map_err(|e| format!("read stdin: {e}"))?;
    let req: PairRequest =
        serde_json::from_str(&input).map_err(|e| format!("bad pair request: {e}"))?;
    let phone_key: PublicKey = req
        .phone_key
        .parse()
        .map_err(|_| "bad pair request: phone_key is not a key".to_owned())?;
    if req.token.is_empty() || req.relay_url.is_empty() {
        return Err("bad pair request: empty token or relay_url".into());
    }
    if let Ok(Some(old)) = Pairing::load()
        && old.token != req.token
    {
        delete_on_relay(&old.relay_url, &old.token);
    }
    let pairing = Pairing {
        relay_url: req.relay_url.trim_end_matches('/').to_owned(),
        token: req.token,
        phone_key,
        beacon: KeyPair::generate(),
    };
    pairing.save()?;
    let reply = PairReply {
        beacon_key: pairing.beacon.public.to_text(),
    };
    println!("{}", serde_json::to_string(&reply).expect("reply json"));
    Ok(())
}

/// `aries-beacon push-test`: sends the test notification.
pub fn push_test_command() -> Result<(), String> {
    let sent_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    send(&PushMessage::Test { sent_at }).map_err(|e| e.to_string())?;
    println!(r#"{{"sent":true}}"#);
    Ok(())
}

/// `aries-beacon unpair`: deletes the pairing on the Relay (best effort)
/// and on the Host; prints an [`UnpairReply`].
pub fn unpair_command() -> Result<(), String> {
    let reply = match Pairing::load() {
        Ok(Some(p)) => {
            let relay_deleted = delete_on_relay(&p.relay_url, &p.token);
            remove_file()?;
            UnpairReply {
                unpaired: true,
                relay_deleted,
            }
        }
        Ok(None) => UnpairReply {
            unpaired: false,
            relay_deleted: false,
        },
        // Unreadable: nothing to revoke with, but do not leave it behind.
        Err(_) => {
            remove_file()?;
            UnpairReply {
                unpaired: true,
                relay_deleted: false,
            }
        }
    };
    println!("{}", serde_json::to_string(&reply).expect("reply json"));
    Ok(())
}

fn remove_file() -> Result<(), String> {
    match std::fs::remove_file(pairing_file()) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}
