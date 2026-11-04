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
                .stderr(Stdio::null())
                .kill_on_drop(true)
                .spawn()
                .map_err(|e| CoreError::Unreachable {
                    detail: format!("{program}: {e}"),
                })?;
            let stdin = child.stdin.take().expect("piped stdin");
            let stdout = child.stdout.take().expect("piped stdout");
            Ok(Stream {
                reader: Box::new(stdout),
                writer: Box::new(stdin),
                guard: Box::new(std::sync::Mutex::new(child)),
                ssh: None,
            })
        })
    }
}
