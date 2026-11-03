//! The phone answers only locally initiated Relay token challenges. FCM never
//! supplies a callback URL: the Relay URL comes from this private per-Host file.
//! No bearer or proof is persisted here. Host removal deletes this state too.

use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, storage};
use crate::push::{HttpClient, http, status_error};
use crate::store::Store;

const LIFETIME: u64 = 120;
static FILES: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum PushVerificationState {
    Pending,
    Verified,
    Failed,
}

#[derive(Serialize, Deserialize)]
struct Verification {
    id: String,
    relay_url: String,
    expires_at: u64,
    state: PushVerificationState,
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn read(store: &Store, host: &str) -> Result<Option<Verification>, CoreError> {
    match std::fs::read(store.host_dir(host).join("push-verification.json")) {
        Ok(bytes) => serde_json::from_slice(&bytes).map(Some).map_err(storage),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(storage(e)),
    }
}

fn write(store: &Store, host: &str, v: &Verification) -> Result<(), CoreError> {
    let dir = store.host_dir(host);
    std::fs::create_dir_all(&dir).map_err(storage)?;
    let tmp = dir.join(".push-verification.tmp");
    std::fs::write(&tmp, serde_json::to_vec(v).map_err(storage)?).map_err(storage)?;
    std::fs::rename(tmp, dir.join("push-verification.json")).map_err(storage)
}

pub(crate) fn begin(store: &Store, host: &str, relay_url: &str) -> Result<String, CoreError> {
    let _lock = FILES.lock().unwrap();
    let id = URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>());
    write(
        store,
        host,
        &Verification {
            id: id.clone(),
            relay_url: relay_url.to_owned(),
            expires_at: now() + LIFETIME,
            state: PushVerificationState::Pending,
        },
    )?;
    Ok(id)
}

pub(crate) fn finish(store: &Store, host: &str, id: &str, success: bool) -> Result<(), CoreError> {
    let _lock = FILES.lock().unwrap();
    if let Some(mut v) = read(store, host)?
        && v.id == id
    {
        v.state = if success {
            PushVerificationState::Verified
        } else {
            PushVerificationState::Failed
        };
        write(store, host, &v)?;
    }
    Ok(())
}

pub(crate) fn state(store: &Store, host: &str) -> Result<Option<PushVerificationState>, CoreError> {
    let _lock = FILES.lock().unwrap();
    Ok(read(store, host)?.map(|v| {
        if v.state == PushVerificationState::Pending && v.expires_at <= now() {
            PushVerificationState::Failed
        } else {
            v.state
        }
    }))
}

fn valid_id(id: &str) -> bool {
    id.len() == 43
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

pub(crate) async fn confirm(
    store: Store,
    hosts: Vec<String>,
    id: String,
    proof: String,
    client: Arc<dyn HttpClient>,
) -> Result<bool, String> {
    if !valid_id(&id) || !valid_id(&proof) {
        return Ok(false);
    }
    let target = {
        let _lock = FILES.lock().unwrap();
        hosts.into_iter().find_map(|host| {
            let v = read(&store, &host).ok().flatten()?;
            (v.id == id && v.state == PushVerificationState::Pending && v.expires_at > now())
                .then_some((host, v.relay_url))
        })
    };
    let Some((_host, relay)) = target else {
        return Ok(false);
    };
    let r = http(
        &client,
        "POST",
        format!("{relay}/v1/pairing/confirm"),
        None,
        Some(serde_json::json!({"verification_id": id, "challenge": proof})),
    )
    .await?;
    // The initiating request verifies activation via GET before the Host is
    // paired. A lost confirm response can therefore still complete safely.
    if r.status == 204 {
        Ok(true)
    } else {
        Err(status_error(&r))
    }
}

pub(crate) async fn wait(
    store: &Store,
    host: &str,
    id: &str,
    relay: &str,
    token: &str,
    client: &Arc<dyn HttpClient>,
) -> Result<(), String> {
    let result = tokio::time::timeout(Duration::from_secs(LIFETIME), async {
        loop {
            if state(store, host).map_err(|e| e.to_string())?
                != Some(PushVerificationState::Pending)
            {
                return Err("device token verification expired; retry push setup".into());
            }
            let r = http(
                client,
                "GET",
                format!("{relay}/v1/pairing"),
                Some(token),
                None,
            )
            .await?;
            if r.status != 200 {
                return Err(status_error(&r));
            }
            let v: serde_json::Value =
                serde_json::from_slice(&r.body).map_err(|_| "invalid Relay verification status")?;
            if v["status"] == "active" {
                return if v["verification_id"].as_str() == Some(id) {
                    Ok(())
                } else {
                    Err(
                        "device token verification expired or was superseded; retry push setup"
                            .into(),
                    )
                };
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    })
    .await
    .unwrap_or_else(|_| Err("device token verification timed out; retry push setup".into()));
    finish(store, host, id, result.is_ok()).map_err(|e| e.to_string())?;
    result
}
