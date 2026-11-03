//! Short-lived Host onboarding, separate from Relay/push pairing.
//!
//! The QR pins both the normal sshd key and the ephemeral pairing SSH key.
//! The bearer token is only sent inside that pinned SSH connection. Offers
//! must never be logged or persisted by the phone.
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};

pub const LIFETIME_SECS: u64 = 300;
pub const INSTALL_COMMAND: &[u8] = b"aries-install-key-v1";

// Deliberately no Debug: this contains a bearer credential.
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairingOffer {
    pub version: u32,
    pub address: String,
    pub port: u16,
    pub user: String,
    pub host_key: String,
    pub pair_port: u16,
    pub pair_key: String,
    pub token: String,
    pub expires_at: u64,
}

#[derive(Debug, thiserror::Error)]
#[error("invalid or expired Host pairing QR")]
pub struct InvalidOffer;

impl PairingOffer {
    pub fn to_qr(&self) -> Result<String, InvalidOffer> {
        let body = serde_json::to_vec(self).map_err(|_| InvalidOffer)?;
        Ok(format!("aries-pair:{}", URL_SAFE_NO_PAD.encode(body)))
    }

    pub fn from_qr(qr: &str, now: u64) -> Result<Self, InvalidOffer> {
        if qr.len() > 4096 {
            return Err(InvalidOffer);
        }
        let encoded = qr.strip_prefix("aries-pair:").ok_or(InvalidOffer)?;
        let body = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| InvalidOffer)?;
        let offer: Self = serde_json::from_slice(&body).map_err(|_| InvalidOffer)?;
        offer.validate(now)?;
        Ok(offer)
    }

    pub fn validate(&self, now: u64) -> Result<(), InvalidOffer> {
        let token = |s: &str| {
            !s.is_empty()
                && s.len() <= 253
                && !s.chars().any(|c| c.is_whitespace() || c.is_control())
        };
        let fingerprint = |s: &str| {
            s.strip_prefix("SHA256:").is_some_and(|s| {
                use base64::engine::general_purpose::STANDARD_NO_PAD;
                STANDARD_NO_PAD
                    .decode(s)
                    .is_ok_and(|bytes| bytes.len() == 32)
            })
        };
        if self.version != 1
            || !token(&self.address)
            || !token(&self.user)
            || self.port == 0
            || self.pair_port == 0
            || !fingerprint(&self.host_key)
            || !fingerprint(&self.pair_key)
            || !URL_SAFE_NO_PAD
                .decode(&self.token)
                .is_ok_and(|b| b.len() == 32)
            || self.expires_at <= now
            || self.expires_at - now > LIFETIME_SECS
        {
            return Err(InvalidOffer);
        }
        Ok(())
    }
}
