//! The push envelope the Beacon sends through the Relay to one paired phone.
//!
//! Layout ([`ENVELOPE_LEN`] bytes, always the same size):
//!
//! ```text
//! [version: 1 byte = ENVELOPE_VERSION][HPKE encapsulated key: 32][ciphertext + tag]
//! ```
//!
//! The ciphertext is HPKE (RFC 9180) in **auth mode**, suite
//! DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / ChaCha20-Poly1305: only the
//! phone's key opens it and only the paired Beacon's key can have sealed it.
//! The version byte is the AEAD's associated data, so it cannot be swapped.
//! The plaintext is a [`PushMessage`] as JSON, prefixed with its length
//! (u16 big-endian) and zero-padded to [`PADDED_LEN`], so the Relay learns
//! nothing from the size.
//!
//! Keys are exchanged at pairing, over SSH: the phone's public key goes to
//! the Beacon, the Beacon's public key comes back. Their text form is
//! unpadded base64url ([`PublicKey::to_text`]).

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use hpke::kem::X25519HkdfSha256;
use hpke::{Deserializable, Kem as _, OpModeR, OpModeS, Serializable};
use serde::{Deserialize, Serialize};

type Kem = X25519HkdfSha256;
type Aead = hpke::aead::ChaCha20Poly1305;
type Kdf = hpke::kdf::HkdfSha256;

/// First byte of every envelope.
pub const ENVELOPE_VERSION: u8 = 1;
/// Size of the padded plaintext.
pub const PADDED_LEN: usize = 1999;
/// Size of every envelope: 2048 bytes (2732 in base64, well under FCM's
/// 4 KiB data limit).
pub const ENVELOPE_LEN: usize = 1 + 32 + PADDED_LEN + 16;

const INFO: &[u8] = b"aries push v1";

/// A compact question for notification buttons. `id` is the Chat tool item's
/// ID passed to `answer_question`; option indexes are unchanged from Chat.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PushQuestion {
    pub id: String,
    pub text: String,
    /// One to three single-choice labels. Larger / multi-part questions are
    /// omitted by the Beacon, never shortened to a misleading subset.
    pub options: Vec<String>,
}

/// What a push says. Kinds are added by the tickets that send them (Agent
/// status, Call); the phone ignores none silently: an unknown kind fails to
/// parse and is reported.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PushMessage {
    /// The test notification sent right after pairing.
    Test {
        /// Unix seconds on the Host.
        sent_at: u64,
    },
    /// An Agent's status changed to one the push policy notifies about
    /// ([`crate::push_policy`]); sent by the Beacon's `serve`.
    Status {
        /// Terminal address on the Host (`t7`, `box/t7`).
        terminal: String,
        /// The Terminal's name, when it has one.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        name: Option<String>,
        /// The Terminal's title, when not empty.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        title: Option<String>,
        /// No parent Terminal: not a subagent.
        top_level: bool,
        /// The new status ([`crate::agent_status::agent_status`]).
        status: crate::agent_status::AgentStatus,
        /// The Host's turn for this status, for answering / marking seen.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        turn: Option<u64>,
        /// One open, single-choice question with at most three options.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        question: Option<PushQuestion>,
        /// First ~200 characters of the last assistant reply, on Done.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        excerpt: Option<String>,
        /// Unix seconds on the Host, retained for older phones.
        at: u64,
        /// Subsecond generation on the Host. Compare only with other Host pushes,
        /// never with a phone's fetch timestamp. Absent on older Beacons.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        at_ms: Option<u64>,
    },
    /// A user-selected Watched Agent is Working. Older phones reject this
    /// new kind as UnknownMessage without changing the v1 envelope.
    Progress {
        terminal: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        name: Option<String>,
        elapsed_secs: u64,
        child_agents: u32,
        /// Unix seconds on the Host, for rejecting delayed progress after Done.
        at: u64,
        /// Optional cleanup when watching is turned off or the Terminal disappears.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        cancelled: bool,
    },
    /// An Agent sent a Call the push policy notifies about
    /// ([`crate::push_policy::PushPolicy::allows_call`]); sent by
    /// `aries-beacon call` right after it recorded the Call.
    Call {
        /// The Call's ID in the Host's Call history ([`crate::calls::Call::id`]).
        id: String,
        /// Address of the calling Terminal (only confirmed Calls are pushed).
        terminal: String,
        /// No parent Terminal: not a subagent.
        top_level: bool,
        title: String,
        /// May be cut short (ending in `…`) to fit the envelope; Call
        /// history has it in full.
        body: String,
        urgency: crate::calls::Urgency,
        /// The Choices, in order; an answer names one by its index. Cut
        /// short only if they would not fit otherwise.
        choices: Vec<String>,
        /// When the Beacon recorded it, Unix milliseconds.
        at_ms: u64,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum EnvelopeError {
    /// Wrong size or structure.
    #[error("not a push envelope")]
    Malformed,
    #[error("unsupported push envelope version {0}")]
    UnsupportedVersion(u8),
    /// Tampered with, not for this phone, or not from the paired Beacon.
    #[error("the push envelope failed authentication")]
    Rejected,
    /// The message does not fit [`PADDED_LEN`].
    #[error("push message too large ({0} bytes)")]
    TooLarge(usize),
    /// It opened, but the content is not a [`PushMessage`] this build knows.
    #[error("unknown push message: {0}")]
    UnknownMessage(String),
}

/// An X25519 public key (32 bytes).
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct PublicKey(pub [u8; 32]);

impl PublicKey {
    /// Unpadded base64url, 43 characters.
    pub fn to_text(&self) -> String {
        URL_SAFE_NO_PAD.encode(self.0)
    }

    fn hpke(&self) -> <Kem as hpke::Kem>::PublicKey {
        <Kem as hpke::Kem>::PublicKey::from_bytes(&self.0).expect("32-byte X25519 key")
    }
}

impl std::fmt::Debug for PublicKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "PublicKey({})", self.to_text())
    }
}

impl std::str::FromStr for PublicKey {
    type Err = EnvelopeError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let bytes = URL_SAFE_NO_PAD
            .decode(s.trim())
            .map_err(|_| EnvelopeError::Malformed)?;
        Ok(Self(
            bytes.try_into().map_err(|_| EnvelopeError::Malformed)?,
        ))
    }
}

/// An X25519 key pair: the phone's (opens) or the Beacon's (seals).
#[derive(Clone)]
pub struct KeyPair {
    secret: [u8; 32],
    pub public: PublicKey,
}

impl KeyPair {
    pub fn generate() -> Self {
        let (sk, pk) = Kem::gen_keypair();
        Self {
            secret: sk.to_bytes().into(),
            public: PublicKey(pk.to_bytes().into()),
        }
    }

    /// The secret half as unpadded base64url, for storage (sealed on the
    /// phone, a private file on the Host).
    pub fn secret_text(&self) -> String {
        URL_SAFE_NO_PAD.encode(self.secret)
    }

    pub fn from_secret_text(s: &str) -> Result<Self, EnvelopeError> {
        let bytes: [u8; 32] = URL_SAFE_NO_PAD
            .decode(s.trim())
            .map_err(|_| EnvelopeError::Malformed)?
            .try_into()
            .map_err(|_| EnvelopeError::Malformed)?;
        let sk = <Kem as hpke::Kem>::PrivateKey::from_bytes(&bytes)
            .map_err(|_| EnvelopeError::Malformed)?;
        let pk = Kem::sk_to_pk(&sk);
        Ok(Self {
            secret: bytes,
            public: PublicKey(pk.to_bytes().into()),
        })
    }

    fn hpke_secret(&self) -> <Kem as hpke::Kem>::PrivateKey {
        <Kem as hpke::Kem>::PrivateKey::from_bytes(&self.secret).expect("32-byte X25519 key")
    }
}

impl std::fmt::Debug for KeyPair {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("KeyPair")
            .field("public", &self.public)
            .finish_non_exhaustive()
    }
}

/// stdin of `aries-beacon pair` (one JSON object), sent by the phone over SSH.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairRequest {
    /// Relay base URL, e.g. `https://relay.example` (no trailing slash).
    pub relay_url: String,
    /// The pairing token the Relay issued to the phone.
    pub token: String,
    /// The phone's [`PublicKey`], text form.
    pub phone_key: String,
}

/// stdout of `aries-beacon pair`: the Beacon's [`PublicKey`], text form.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairReply {
    pub beacon_key: String,
}

/// stdout of `aries-beacon unpair`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UnpairReply {
    /// A pairing existed (its file is now gone).
    pub unpaired: bool,
    /// The Relay confirmed the pairing's data is deleted (`false` when it
    /// could not be reached; the phone deletes it too).
    pub relay_deleted: bool,
}

/// Seals `message` for the phone `to`, authenticated as `from` (the Beacon).
pub fn seal(
    message: &PushMessage,
    to: &PublicKey,
    from: &KeyPair,
) -> Result<Vec<u8>, EnvelopeError> {
    let json = serde_json::to_vec(message).expect("push message json");
    if json.len() > PADDED_LEN - 2 {
        return Err(EnvelopeError::TooLarge(json.len()));
    }
    let mut padded = vec![0u8; PADDED_LEN];
    padded[..2].copy_from_slice(&(json.len() as u16).to_be_bytes());
    padded[2..2 + json.len()].copy_from_slice(&json);

    let header = [ENVELOPE_VERSION];
    let mode = OpModeS::<Kem>::Auth((from.hpke_secret(), from.public.hpke()));
    let (enc, ciphertext) =
        hpke::single_shot_seal::<Aead, Kdf, Kem>(&mode, &to.hpke(), INFO, &padded, &header)
            .map_err(|_| EnvelopeError::Rejected)?;
    let mut out = Vec::with_capacity(ENVELOPE_LEN);
    out.extend_from_slice(&header);
    out.extend_from_slice(&enc.to_bytes());
    out.extend_from_slice(&ciphertext);
    debug_assert_eq!(out.len(), ENVELOPE_LEN);
    Ok(out)
}

/// Opens an envelope on the phone `me`, accepting it only if the Beacon
/// `from` sealed it.
pub fn open(envelope: &[u8], me: &KeyPair, from: &PublicKey) -> Result<PushMessage, EnvelopeError> {
    let (&version, rest) = envelope.split_first().ok_or(EnvelopeError::Malformed)?;
    if version != ENVELOPE_VERSION {
        return Err(EnvelopeError::UnsupportedVersion(version));
    }
    if envelope.len() != ENVELOPE_LEN {
        return Err(EnvelopeError::Malformed);
    }
    let (enc, ciphertext) = rest.split_at(32);
    let enc =
        <Kem as hpke::Kem>::EncappedKey::from_bytes(enc).map_err(|_| EnvelopeError::Rejected)?;
    let mode = OpModeR::<Kem>::Auth(from.hpke());
    let padded = hpke::single_shot_open::<Aead, Kdf, Kem>(
        &mode,
        &me.hpke_secret(),
        &enc,
        INFO,
        ciphertext,
        &[version],
    )
    .map_err(|_| EnvelopeError::Rejected)?;
    let len = u16::from_be_bytes([padded[0], padded[1]]) as usize;
    let json = padded.get(2..2 + len).ok_or(EnvelopeError::Malformed)?;
    serde_json::from_slice(json).map_err(|e| EnvelopeError::UnknownMessage(e.to_string()))
}
