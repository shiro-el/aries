use std::time::{Duration, SystemTime, UNIX_EPOCH};

use aries_protocol::host_pairing::{INSTALL_COMMAND, PairingOffer};
use russh::ChannelMsg;

use crate::{AriesCore, CoreError, Endpoint, Host, host_edit, on_runtime, store::SavedHost};

/// Safe display fields only; the QR's bearer token stays in foreground memory.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HostPairingDetails {
    pub address: String,
    pub port: u16,
    pub user: String,
    pub host_key: String,
    pub expires_at: u64,
}

fn parse(qr: &str) -> Result<PairingOffer, CoreError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| CoreError::InvalidPairingQr)?
        .as_secs();
    PairingOffer::from_qr(qr, now).map_err(|_| CoreError::InvalidPairingQr)
}

#[uniffi::export]
impl AriesCore {
    /// Validates a scanned QR without connecting, persisting it, or registering a Host.
    pub fn inspect_pairing_qr(&self, qr: String) -> Result<HostPairingDetails, CoreError> {
        let offer = parse(qr.trim())?;
        Ok(HostPairingDetails {
            address: offer.address,
            port: offer.port,
            user: offer.user,
            host_key: offer.host_key,
            expires_at: offer.expires_at,
        })
    }

    /// User-confirmed onboarding. Only a successful key install creates a Host;
    /// its normal sshd key is pinned to the fingerprint read from the QR.
    pub async fn pair_host_qr(&self, name: String, qr: String) -> Result<Host, CoreError> {
        let offer = parse(qr.trim())?;
        let endpoint = Endpoint::Ssh {
            address: offer.address.clone(),
            port: offer.port,
            user: offer.user.clone(),
        };
        let name = name.trim().to_owned();
        host_edit::validate(
            &self.hosts.lock().expect("hosts lock"),
            None,
            &name,
            &endpoint,
        )?;
        let public = self.public_key()?.ok_or(CoreError::NoKey)?.openssh;
        let host_key = offer.host_key.clone();
        on_runtime(async move {
            tokio::time::timeout(Duration::from_secs(30), install(offer, public))
                .await
                .map_err(|_| CoreError::PairingRefused)?
        })
        .await?;
        let mut hosts = self.hosts.lock().expect("hosts lock");
        host_edit::validate(&hosts, None, &name, &endpoint)?;
        let saved = SavedHost {
            id: crate::new_id(),
            name,
            endpoint,
            host_key: Some(host_key),
            jumps: Vec::new(),
            key_id: None,
        };
        let mut next = hosts.clone();
        next.push(saved.clone());
        self.store.save(&crate::store::Saved {
            hosts: next.clone(),
        })?;
        *hosts = next;
        Ok(crate::to_host(&saved))
    }
}

async fn install(offer: PairingOffer, public: String) -> Result<(), CoreError> {
    use crate::transport::handshake;
    let (_, normal) = handshake(
        &[],
        &offer.address,
        offer.port,
        Some(offer.host_key.clone()),
    )
    .await?;
    let _ = normal
        .disconnect(russh::Disconnect::ByApplication, "", "")
        .await;
    let (_, mut session) = handshake(
        &[],
        &offer.address,
        offer.pair_port,
        Some(offer.pair_key.clone()),
    )
    .await?;
    // Recheck expiry after DNS/handshakes, before disclosing the token.
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| CoreError::InvalidPairingQr)?
        .as_secs();
    offer
        .validate(now)
        .map_err(|_| CoreError::InvalidPairingQr)?;
    let result = async {
        let auth = session
            .authenticate_password(&offer.user, &offer.token)
            .await
            .map_err(|_| CoreError::PairingRefused)?;
        if !auth.success() {
            return Err(CoreError::PairingRefused);
        }
        let mut channel = session
            .channel_open_session()
            .await
            .map_err(|_| CoreError::PairingRefused)?;
        channel
            .exec(true, INSTALL_COMMAND)
            .await
            .map_err(|_| CoreError::PairingRefused)?;
        channel
            .data(public.as_bytes())
            .await
            .map_err(|_| CoreError::PairingRefused)?;
        channel.eof().await.map_err(|_| CoreError::PairingRefused)?;
        let mut output = Vec::new();
        let mut status = None;
        while let Some(message) = channel.wait().await {
            match message {
                ChannelMsg::Data { data } if output.len() + data.len() <= 64 => {
                    output.extend_from_slice(&data)
                }
                ChannelMsg::ExitStatus { exit_status } => status = Some(exit_status),
                ChannelMsg::Close => break,
                ChannelMsg::Data { .. } | ChannelMsg::Failure => {
                    return Err(CoreError::PairingRefused);
                }
                _ => {}
            }
        }
        if status == Some(0) && output == b"ok\n" {
            Ok(())
        } else {
            Err(CoreError::PairingRefused)
        }
    }
    .await;
    let _ = session
        .disconnect(russh::Disconnect::ByApplication, "", "")
        .await;
    result
}
