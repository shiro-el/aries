//! Push: pairing a Host's Beacon with this phone through the Relay, and
//! opening the envelopes that arrive.
//!
//! Turning push on ([`crate::AriesCore::enable_push`]) runs three steps:
//!
//! 1. [`PushStep::Register`]: a fresh X25519 key pair for this Host;
//!    `POST {relay}/v1/pairings` with the device push token returns the
//!    pairing token.
//! 2. [`PushStep::Pair`]: over SSH, `aries-beacon pair` gets the Relay URL,
//!    the token and the phone's public key, and answers with the Beacon's
//!    public key. A failure here deletes the Relay pairing again.
//! 3. [`PushStep::Test`]: `aries-beacon push-test` seals a test message and
//!    posts it to the Relay, which forwards it to the phone.
//!
//! Stored per Host in `hosts/<id>/push.json`: Relay URL, the Beacon's public
//! key and, sealed by the [`KeyVault`] under [`KeyProtection::Plain`]
//! whatever the SSH key policy is, the phone's secret key and the pairing
//! token. Push is opened in the background on a locked phone, which only a
//! key without user-authentication binding allows (see
//! `docs/research/background-choice-reply.md`).
//!
//! Unpairing deletes the Relay's data from the phone (`DELETE
//! /v1/pairing`) and from the Host (`aries-beacon unpair`). Host removal and
//! Beacon removal cannot reach the network synchronously, so they queue the
//! token in `push-revocations.json`; the app sends the queue with
//! [`crate::AriesCore::flush_push_revocations`] right after, and again on
//! start.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use aries_protocol::push::{self as envelope, EnvelopeError, KeyPair, PairReply, PairRequest};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::{Deserialize, Serialize};

use crate::beacon::{BEACON_BIN, FetchError, sh_c};
use crate::error::{CoreError, storage};
use crate::store::Store;
use crate::transport::Ssh;
use crate::{KeyProtection, KeyVault};

/// The app's HTTP client (the Relay is reached from the phone).
#[uniffi::export(with_foreign)]
pub trait HttpClient: Send + Sync {
    fn send(&self, request: HttpRequest) -> Result<HttpResponse, FetchError>;
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HttpRequest {
    pub method: String,
    pub url: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

/// One step of turning push on (or off).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PushStep {
    /// Registering this phone on the Relay.
    Register,
    /// Exchanging keys with the Beacon over SSH.
    Pair,
    /// The Beacon sending the test notification.
    Test,
    /// Deleting the pairing on the Relay.
    Unpair,
}

/// Told when each step of [`crate::AriesCore::enable_push`] starts.
#[uniffi::export(with_foreign)]
pub trait PushProgress: Send + Sync {
    fn on_step(&self, step: PushStep);
}

/// Where the Relay delivers to this phone.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PushDevice {
    /// `fcm` (APNs later).
    pub platform: String,
    /// The FCM registration token.
    pub token: String,
}

/// A Host's push pairing, as the app shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PushPairing {
    pub relay_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PushDisable {
    /// The Beacon forgot the pairing too. `false` when the Host could not
    /// be reached; the Relay's data is deleted either way.
    pub host_cleared: bool,
}

/// What an opened push says.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum PushContent {
    /// The test notification sent when push was turned on.
    Test { sent_at: u64 },
}

/// An opened push, from a paired Host.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PushNotice {
    pub host_id: String,
    pub host_name: String,
    pub content: PushContent,
}

#[derive(Serialize, Deserialize)]
struct Stored {
    relay_url: String,
    beacon_key: String,
    /// base64 of the vault-sealed [`Secrets`] JSON.
    sealed: String,
}

#[derive(Serialize, Deserialize)]
struct Secrets {
    phone_secret: String,
    token: String,
}

/// A pairing with its secrets opened.
pub(crate) struct Pairing {
    pub relay_url: String,
    pub token: String,
    pub phone: KeyPair,
    pub beacon_key: envelope::PublicKey,
}

fn pairing_path(store: &Store, host_id: &str) -> PathBuf {
    store.host_dir(host_id).join("push.json")
}

fn read_stored(store: &Store, host_id: &str) -> Result<Option<Stored>, CoreError> {
    match std::fs::read(pairing_path(store, host_id)) {
        Ok(b) => serde_json::from_slice(&b).map(Some).map_err(storage),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(storage(e)),
    }
}

fn open_secrets(vault: &dyn KeyVault, sealed: &str) -> Result<Secrets, CoreError> {
    let bytes = STANDARD.decode(sealed).map_err(storage)?;
    let plain = vault.open(bytes, KeyProtection::Plain)?;
    serde_json::from_slice(&plain).map_err(storage)
}

fn seal_secrets(vault: &dyn KeyVault, secrets: &Secrets) -> Result<String, CoreError> {
    let plain = serde_json::to_vec(secrets).map_err(storage)?;
    Ok(STANDARD.encode(vault.seal(plain, KeyProtection::Plain)?))
}

pub(crate) fn pairing_of(
    store: &Store,
    vault: &dyn KeyVault,
    host_id: &str,
) -> Result<Option<Pairing>, CoreError> {
    let Some(s) = read_stored(store, host_id)? else {
        return Ok(None);
    };
    let secrets = open_secrets(vault, &s.sealed)?;
    let bad = |e: EnvelopeError| storage(format!("push pairing: {e}"));
    Ok(Some(Pairing {
        phone: KeyPair::from_secret_text(&secrets.phone_secret).map_err(bad)?,
        beacon_key: s.beacon_key.parse().map_err(bad)?,
        relay_url: s.relay_url,
        token: secrets.token,
    }))
}

pub(crate) fn relay_url_of(store: &Store, host_id: &str) -> Result<Option<String>, CoreError> {
    Ok(read_stored(store, host_id)?.map(|s| s.relay_url))
}

fn save(store: &Store, vault: &dyn KeyVault, host_id: &str, p: &Pairing) -> Result<(), CoreError> {
    let dir = store.host_dir(host_id);
    std::fs::create_dir_all(&dir).map_err(storage)?;
    let stored = Stored {
        relay_url: p.relay_url.clone(),
        beacon_key: p.beacon_key.to_text(),
        sealed: seal_secrets(
            vault,
            &Secrets {
                phone_secret: p.phone.secret_text(),
                token: p.token.clone(),
            },
        )?,
    };
    let tmp = dir.join(".push.json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(&stored).map_err(storage)?).map_err(storage)?;
    std::fs::rename(&tmp, pairing_path(store, host_id)).map_err(storage)
}

fn forget(store: &Store, host_id: &str) -> Result<(), CoreError> {
    match std::fs::remove_file(pairing_path(store, host_id)) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(storage(e)),
    }
}

// ---- Relay -------------------------------------------------------------------

async fn http(
    client: &Arc<dyn HttpClient>,
    method: &str,
    url: String,
    token: Option<&str>,
    body: Option<serde_json::Value>,
) -> Result<HttpResponse, String> {
    let mut headers = HashMap::new();
    if let Some(t) = token {
        headers.insert("Authorization".into(), format!("Bearer {t}"));
    }
    let body = match body {
        Some(v) => {
            headers.insert("Content-Type".into(), "application/json".into());
            v.to_string().into_bytes()
        }
        None => Vec::new(),
    };
    let request = HttpRequest {
        method: method.into(),
        url,
        headers,
        body,
    };
    let client = client.clone();
    tokio::task::spawn_blocking(move || client.send(request))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())
}

fn status_error(r: &HttpResponse) -> String {
    let body = String::from_utf8_lossy(&r.body);
    format!("the Relay answered HTTP {}: {}", r.status, body.trim())
}

async fn register(
    client: &Arc<dyn HttpClient>,
    relay_url: &str,
    device: &PushDevice,
) -> Result<String, String> {
    let r = http(
        client,
        "POST",
        format!("{relay_url}/v1/pairings"),
        None,
        Some(serde_json::json!({"platform": device.platform, "device_token": device.token})),
    )
    .await?;
    if r.status != 201 && r.status != 200 {
        return Err(status_error(&r));
    }
    let v: serde_json::Value =
        serde_json::from_slice(&r.body).map_err(|e| format!("Relay reply: {e}"))?;
    v["token"]
        .as_str()
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
        .ok_or_else(|| "the Relay reply has no token".into())
}

/// `DELETE /v1/pairing`. The Relay answers 204 also for an unknown token.
async fn revoke(client: &Arc<dyn HttpClient>, relay_url: &str, token: &str) -> Result<(), String> {
    let r = http(
        client,
        "DELETE",
        format!("{relay_url}/v1/pairing"),
        Some(token),
        None,
    )
    .await?;
    if (200..300).contains(&r.status) || r.status == 404 {
        Ok(())
    } else {
        Err(status_error(&r))
    }
}

async fn update(
    client: &Arc<dyn HttpClient>,
    relay_url: &str,
    token: &str,
    device: &PushDevice,
) -> Result<(), String> {
    let r = http(
        client,
        "PUT",
        format!("{relay_url}/v1/pairing"),
        Some(token),
        Some(serde_json::json!({"platform": device.platform, "device_token": device.token})),
    )
    .await?;
    if (200..300).contains(&r.status) {
        Ok(())
    } else {
        Err(status_error(&r))
    }
}

fn failed(step: PushStep, detail: impl Into<String>) -> CoreError {
    CoreError::PushFailed {
        step,
        detail: detail.into(),
    }
}

// ---- Beacon over SSH -----------------------------------------------------------

/// Runs `aries-beacon <sub>` on the Host; stdout's last line on success.
async fn beacon(ssh: &Ssh, sub: &str, stdin: &[u8]) -> Result<String, String> {
    let script = format!(
        r#"b="{BEACON_BIN}"; if [ ! -x "$b" ]; then echo "the Beacon is not installed" >&2; exit 127; fi; exec "$b" {sub}"#
    );
    let out = ssh
        .exec(&sh_c(&script), stdin)
        .await
        .map_err(|e| e.to_string())?;
    if !out.success() {
        let err = String::from_utf8_lossy(&out.stderr).trim().to_owned();
        let err = err
            .strip_prefix("aries-beacon: ")
            .unwrap_or(&err)
            .to_owned();
        return Err(if err.is_empty() {
            format!("aries-beacon {sub} exited with {:?}", out.status)
        } else {
            err
        });
    }
    Ok(String::from_utf8_lossy(&out.stdout)
        .lines()
        .map(str::trim)
        .rfind(|l| !l.is_empty())
        .unwrap_or_default()
        .to_owned())
}

pub(crate) struct Enable {
    pub host_id: String,
    pub relay_url: String,
    pub device: PushDevice,
    pub ssh: Ssh,
    pub store: Store,
    pub vault: Arc<dyn KeyVault>,
    pub http: Arc<dyn HttpClient>,
    pub progress: Arc<dyn PushProgress>,
}

pub(crate) async fn enable(e: Enable) -> Result<PushPairing, CoreError> {
    let relay_url = e.relay_url.trim().trim_end_matches('/').to_owned();
    // An earlier pairing of this Host: revoke it (the Beacon also does when
    // it is re-paired).
    if let Ok(Some(old)) = pairing_of(&e.store, e.vault.as_ref(), &e.host_id) {
        let _ = revoke(&e.http, &old.relay_url, &old.token).await;
        forget(&e.store, &e.host_id)?;
    }

    e.progress.on_step(PushStep::Register);
    let phone = KeyPair::generate();
    let token = register(&e.http, &relay_url, &e.device)
        .await
        .map_err(|d| failed(PushStep::Register, d))?;

    e.progress.on_step(PushStep::Pair);
    let request = PairRequest {
        relay_url: relay_url.clone(),
        token: token.clone(),
        phone_key: phone.public.to_text(),
    };
    let paired = async {
        let line = beacon(
            &e.ssh,
            "pair",
            serde_json::to_string(&request).expect("json").as_bytes(),
        )
        .await?;
        let reply: PairReply = serde_json::from_str(&line)
            .map_err(|err| format!("aries-beacon pair: {err}: {line}"))?;
        reply
            .beacon_key
            .parse::<envelope::PublicKey>()
            .map_err(|err| format!("aries-beacon pair: {err}"))
    }
    .await;
    let beacon_key = match paired {
        Ok(k) => k,
        Err(d) => {
            let _ = revoke(&e.http, &relay_url, &token).await;
            return Err(failed(PushStep::Pair, d));
        }
    };
    save(
        &e.store,
        e.vault.as_ref(),
        &e.host_id,
        &Pairing {
            relay_url: relay_url.clone(),
            token,
            phone,
            beacon_key,
        },
    )?;

    e.progress.on_step(PushStep::Test);
    test(&e.ssh).await?;
    Ok(PushPairing { relay_url })
}

pub(crate) async fn test(ssh: &Ssh) -> Result<(), CoreError> {
    beacon(ssh, "push-test", &[])
        .await
        .map(|_| ())
        .map_err(|d| failed(PushStep::Test, d))
}

pub(crate) async fn disable(
    host_id: String,
    ssh: Option<Ssh>,
    store: Store,
    vault: Arc<dyn KeyVault>,
    client: Arc<dyn HttpClient>,
) -> Result<PushDisable, CoreError> {
    if let Some(p) = pairing_of(&store, vault.as_ref(), &host_id)? {
        revoke(&client, &p.relay_url, &p.token)
            .await
            .map_err(|d| failed(PushStep::Unpair, d))?;
    }
    forget(&store, &host_id)?;
    let host_cleared = match ssh {
        Some(ssh) => beacon(&ssh, "unpair", &[]).await.is_ok(),
        None => false,
    };
    Ok(PushDisable { host_cleared })
}

pub(crate) async fn update_token(
    paired: Vec<Pairing>,
    device: PushDevice,
    client: Arc<dyn HttpClient>,
) -> Result<(), CoreError> {
    let mut first = None;
    for p in paired {
        if let Err(d) = update(&client, &p.relay_url, &p.token, &device).await {
            first.get_or_insert(failed(PushStep::Register, d));
        }
    }
    first.map_or(Ok(()), Err)
}

/// Opens `envelope` with whichever paired Host sealed it.
pub(crate) fn open(
    envelope_bytes: &[u8],
    paired: impl IntoIterator<Item = (String, String, Pairing)>,
) -> Result<PushNotice, CoreError> {
    let rejected = |reason: String| CoreError::PushRejected { reason };
    for (host_id, host_name, p) in paired {
        match envelope::open(envelope_bytes, &p.phone, &p.beacon_key) {
            Ok(message) => {
                let content = match message {
                    envelope::PushMessage::Test { sent_at } => PushContent::Test { sent_at },
                };
                return Ok(PushNotice {
                    host_id,
                    host_name,
                    content,
                });
            }
            Err(EnvelopeError::Rejected) => continue,
            Err(e) => return Err(rejected(e.to_string())),
        }
    }
    // Check the structure even without a pairing, for a precise reason.
    match envelope_bytes.first() {
        Some(&v) if v != envelope::ENVELOPE_VERSION => {
            Err(rejected(EnvelopeError::UnsupportedVersion(v).to_string()))
        }
        _ => Err(rejected("not from a paired Host".into())),
    }
}

// ---- Revocation queue ----------------------------------------------------------

#[derive(Serialize, Deserialize)]
struct Revocation {
    relay_url: String,
    /// The pairing's sealed secrets ([`Stored::sealed`]).
    sealed: String,
}

const QUEUE: &str = "push-revocations.json";

fn read_queue(store: &Store) -> Result<Vec<Revocation>, CoreError> {
    match std::fs::read(store.root().join(QUEUE)) {
        Ok(b) => serde_json::from_slice(&b).map_err(storage),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(storage(e)),
    }
}

fn write_queue(store: &Store, queue: &[Revocation]) -> Result<(), CoreError> {
    let tmp = store.root().join(format!(".{QUEUE}.tmp"));
    std::fs::write(&tmp, serde_json::to_vec(queue).map_err(storage)?).map_err(storage)?;
    std::fs::rename(&tmp, store.root().join(QUEUE)).map_err(storage)
}

/// Moves `host_id`'s pairing (if any) to the revocation queue and forgets
/// it locally. No network and no vault: Host and Beacon removal call this.
pub(crate) fn queue_revocation(store: &Store, host_id: &str) -> Result<(), CoreError> {
    let Some(s) = read_stored(store, host_id)? else {
        return Ok(());
    };
    let mut queue = read_queue(store)?;
    queue.push(Revocation {
        relay_url: s.relay_url,
        sealed: s.sealed,
    });
    write_queue(store, &queue)?;
    forget(store, host_id)
}

/// Sends every queued revocation; returns how many are still queued.
pub(crate) async fn flush(
    store: Store,
    vault: Arc<dyn KeyVault>,
    client: Arc<dyn HttpClient>,
) -> u32 {
    let Ok(queue) = read_queue(&store) else {
        return 0;
    };
    if queue.is_empty() {
        return 0;
    }
    let mut left = Vec::new();
    for r in queue {
        match open_secrets(vault.as_ref(), &r.sealed) {
            Ok(s) if revoke(&client, &r.relay_url, &s.token).await.is_err() => left.push(r),
            // Revoked, or unreadable (nothing could ever revoke it).
            _ => {}
        }
    }
    let n = left.len() as u32;
    let _ = write_queue(&store, &left);
    n
}
