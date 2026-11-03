//! ADR 0003: herdr-app owns layout edits; Aries never writes its document.
use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::{Value, json};

use crate::{CoreError, HostConnection, TerminalInfo, error::protocol, on_runtime, terminal_info};

/// Output of a bounded, non-terminal Host program. Bytes are decoded from
/// the protocol's base64 without assuming UTF-8 (Git can contain any bytes).
#[derive(Debug, Clone, uniffi::Record)]
pub struct ExecOutput {
    pub code: Option<i32>,
    pub signal: Option<i32>,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub truncated: bool,
    pub timed_out: bool,
}

/// Explicit IDs avoid accidentally editing the desktop's current selection.
/// Empty rename names restore herdr-app's automatic names.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum LayoutEdit {
    NewSpace {
        name: String,
        folder: Option<String>,
    },
    RenameSpace {
        id: String,
        name: String,
    },
    CloseSpace {
        id: String,
    },
    NewTab {
        space_id: String,
        name: String,
        folder: Option<String>,
        argv: Option<Vec<String>>,
    },
    RenameTab {
        id: String,
        name: String,
    },
    CloseTab {
        id: String,
    },
}

#[uniffi::export]
impl HostConnection {
    /// Ends exactly this Terminal (including kept programs); does not remove
    /// its record or depend on herdr-app. The UI obtains confirmation first.
    pub async fn kill_terminal(&self, address: String) -> Result<TerminalInfo, CoreError> {
        let client = self.client.clone();
        let result = on_runtime(async move {
            client
                .call("terminal.kill", json!({"terminal": address}))
                .await
        })
        .await?;
        Ok(terminal_info(&result))
    }

    /// Runs argv directly, with no shell interpretation. Kept shorter than
    /// the RPC timeout so the Host always has time to sweep the subprocess.
    pub async fn machine_exec(
        &self,
        argv: Vec<String>,
        cwd: Option<String>,
        timeout_seconds: u32,
    ) -> Result<ExecOutput, CoreError> {
        if !(1..=20).contains(&timeout_seconds) {
            return Err(protocol("exec timeout must be between 1 and 20 seconds"));
        }
        let client = self.client.clone();
        let v = on_runtime(async move {
            client
                .call(
                    "machine.exec",
                    json!({
                        "argv": argv, "cwd": cwd, "timeout": timeout_seconds,
                    }),
                )
                .await
        })
        .await?;
        let decode = |key: &str| {
            STANDARD
                .decode(
                    v[key]
                        .as_str()
                        .ok_or_else(|| protocol("missing exec output"))?,
                )
                .map_err(|e| protocol(format!("invalid exec output: {e}")))
        };
        Ok(ExecOutput {
            code: v["code"].as_i64().map(|n| n as i32),
            signal: v["signal"].as_i64().map(|n| n as i32),
            stdout: decode("stdout")?,
            stderr: decode("stderr")?,
            truncated: v["truncated"].as_bool().unwrap_or(false),
            timed_out: v["timed_out"].as_bool().unwrap_or(false),
        })
    }

    /// A read-only control-socket probe, not just an executable/PID check.
    /// Missing binary, closed window or a hung app all disable layout edits.
    pub async fn herdr_app_available(&self) -> Result<bool, CoreError> {
        match self
            .machine_exec(vec!["herdr-app".into(), "status".into()], None, 3)
            .await
        {
            Ok(out) => Ok(out.code == Some(0) && !out.timed_out && !out.truncated),
            Err(CoreError::Rpc { kind, .. })
                if kind == "spawn_failed" || kind == "unknown_method" =>
            {
                Ok(false)
            }
            Err(e) => Err(e),
        }
    }

    /// Calls herdr-app once; never retries a mutation whose result is unknown.
    /// The UI must obtain confirmation before supplying a Close variant.
    pub async fn edit_layout(&self, edit: LayoutEdit) -> Result<String, CoreError> {
        let (command, args): (&str, Value) = match edit {
            LayoutEdit::NewSpace { name, folder } => {
                ("space.new", json!({"name":name, "folder":folder}))
            }
            LayoutEdit::RenameSpace { id, name } => ("space.rename", json!({"id":id, "name":name})),
            LayoutEdit::CloseSpace { id } => ("space.close", json!({"id":id, "confirmed":true})),
            LayoutEdit::NewTab {
                space_id,
                name,
                folder,
                argv,
            } => (
                "tab.new",
                json!({"space":space_id, "name":name, "folder":folder, "argv":argv}),
            ),
            LayoutEdit::RenameTab { id, name } => ("tab.rename", json!({"id":id, "name":name})),
            LayoutEdit::CloseTab { id } => ("tab.close", json!({"id":id, "confirmed":true})),
        };
        let out = self
            .machine_exec(
                vec![
                    "herdr-app".into(),
                    "do".into(),
                    command.into(),
                    args.to_string(),
                ],
                None,
                20,
            )
            .await?;
        if out.code != Some(0) || out.timed_out || out.truncated {
            return Err(CoreError::Rpc {
                kind: "herdr_app_failed".into(),
                detail: format!(
                    "{command} (exit {:?}, timeout {}, truncated {}): {} {}",
                    out.code,
                    out.timed_out,
                    out.truncated,
                    String::from_utf8_lossy(&out.stderr),
                    String::from_utf8_lossy(&out.stdout)
                ),
            });
        }
        String::from_utf8(out.stdout).map_err(|e| protocol(e.to_string()))
    }
}
