//! Short-lived notification actions. No reconnect or resend queue.

use std::sync::Arc;
use std::time::Duration;

use crate::hc::HcClient;
use crate::transport::{LocalProcess, Transport};
use crate::{AriesCore, CoreError, Endpoint, SendListener, SendOutcome, TerminalInfo};

const SIGN_IN: Duration = Duration::from_secs(15);

#[uniffi::export]
impl AriesCore {
    /// Sends once from a notification without a foreground HostConnection.
    /// Sign-in (all jumps and the protocol handshake) is limited to 15 s.
    /// A lost result is an error, never an invitation to automatically retry:
    /// the message may already have reached the Terminal.
    pub async fn send_message_background(
        &self,
        host_id: String,
        terminal: String,
        text: String,
        listener: Arc<dyn SendListener>,
    ) -> Result<SendOutcome, CoreError> {
        let transport = self.notification_transport(&host_id)?;
        crate::on_runtime(async move {
            let client = connect(transport).await?;
            let result = crate::send::send(&client, &terminal, &text, || listener.on_held()).await;
            client.close().await;
            result
        })
        .await
    }

    /// Marks only the notification's turn seen, never a newer turn. Reads
    /// the shared label first so an old phone notification cannot lower it.
    pub async fn mark_seen_background(
        &self,
        host_id: String,
        terminal: String,
        turn: u64,
    ) -> Result<TerminalInfo, CoreError> {
        let transport = self.notification_transport(&host_id)?;
        crate::on_runtime(async move {
            let client = connect(transport).await?;
            let result = mark_seen(&client, &terminal, turn).await;
            client.close().await;
            result
        })
        .await
    }
}

impl AriesCore {
    fn notification_transport(&self, host_id: &str) -> Result<Box<dyn Transport>, CoreError> {
        // Never open a biometric prompt from background work, including on a jump Host.
        if !self.can_answer_in_background(host_id.to_owned())? {
            return Err(CoreError::KeyLocked);
        }
        match self.saved_host(host_id)?.endpoint {
            Endpoint::Local { argv, env } => Ok(Box::new(LocalProcess { argv, env })),
            Endpoint::Ssh { .. } => Ok(Box::new(self.beacon_ssh(host_id)?)),
        }
    }
}

async fn connect(transport: Box<dyn Transport>) -> Result<HcClient, CoreError> {
    tokio::time::timeout(SIGN_IN, async move {
        HcClient::handshake(transport.open().await?).await
    })
    .await
    .map_err(|_| CoreError::Unreachable {
        detail: "notification sign-in timed out".into(),
    })?
}

pub(crate) async fn mark_seen(
    client: &HcClient,
    address: &str,
    turn: u64,
) -> Result<TerminalInfo, CoreError> {
    let now = client
        .call("terminal.get", serde_json::json!({ "terminal": address }))
        .await?;
    if crate::host_model::seen_turn(&now).is_some_and(|seen| seen >= turn) {
        return Ok(crate::terminal_info(&now));
    }
    crate::set_labels(
        client,
        address,
        serde_json::json!({
            aries_protocol::agent_status::SEEN_LABEL: turn.to_string()
        }),
    )
    .await
}
