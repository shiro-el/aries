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
            let mut child = tokio::process::Command::new(program)
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
            })
        })
    }
}
