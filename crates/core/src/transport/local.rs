use std::collections::HashMap;
use std::process::Stdio;

use super::{OpenFuture, Stream, Transport};
use crate::error::CoreError;

/// Runs `herdr-core connect` (or anything speaking its protocol) as a child
/// process and talks over its stdio.
pub(crate) struct LocalProcess {
    pub argv: Vec<String>,
    pub env: HashMap<String, String>,
}

impl Transport for LocalProcess {
    fn open(&self) -> OpenFuture<'_> {
        Box::pin(async move {
            let (program, args) =
                self.argv
                    .split_first()
                    .ok_or_else(|| CoreError::Unreachable {
                        detail: "empty local command".into(),
                    })?;
            let mut command = tokio::process::Command::new(program);
            // An endpoint owns its routing. Inherited socket overrides can
            // otherwise bypass its HC_SESSION and reach the app's own core.
            for key in [
                "HC_SESSION",
                "HC_SOCKET",
                "HC_SCOPE",
                "HC_TERMINAL_ID",
                "HC_TERMINAL_SOCKET",
            ] {
                command.env_remove(key);
            }
            let mut child = command
                .args(args)
                .envs(&self.env)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .kill_on_drop(true)
                .spawn()
                .map_err(|e| CoreError::Unreachable {
                    detail: format!("{program}: {e}"),
                })?;
            let stdin = child.stdin.take().expect("piped stdin");
            let stdout = child.stdout.take().expect("piped stdout");
            let mut stderr = child.stderr.take().expect("piped stderr");
            let (tx, rx) = tokio::sync::oneshot::channel();
            let task = tokio::spawn(async move {
                use tokio::io::AsyncReadExt;
                let mut out = super::CommandExit::default();
                let status = async {
                    child
                        .wait()
                        .await
                        .ok()
                        .and_then(|s| s.code())
                        .map(|s| s as u32)
                };
                let diagnostics = async {
                    let mut bytes = [0; 4096];
                    while let Ok(n) = stderr.read(&mut bytes).await {
                        if n == 0 {
                            break;
                        }
                        out.stderr(&bytes[..n]);
                    }
                };
                let (status, _) = tokio::join!(status, diagnostics);
                out.status = status;
                let _ = tx.send(out);
            });
            Ok(Stream {
                reader: Box::new(stdout),
                writer: Box::new(stdin),
                guard: Box::new(super::CommandTask(task.abort_handle())),
                command_exit: Some(rx),
                ssh: None,
            })
        })
    }
}
