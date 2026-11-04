//! Read-only Git inspection over the open Host connection. No shell or index writes.
use crate::{CoreError, ExecOutput, HostConnection, error::protocol, on_runtime};
use serde_json::json;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GitFile {
    pub path: String,
    pub original_path: Option<String>,
    pub status: String,
    pub staged: bool,
    pub unstaged: bool,
    pub untracked: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GitChanges {
    pub cwd: String,
    pub files: Vec<GitFile>,
    pub truncated: bool,
    pub not_repository: bool,
}

/// Totals for tracked staged and unstaged changes; untracked files have no index entry.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct GitStats {
    pub added: u32,
    pub removed: u32,
    pub binary: bool,
    pub truncated: bool,
}

fn add_numstat(stats: &mut GitStats, out: &ExecOutput) -> Result<(), CoreError> {
    check_output(out, false)?;
    stats.truncated |= out.truncated;
    // --no-renames makes each NUL-delimited record exactly added TAB removed TAB path.
    for record in out.stdout.split_inclusive(|b| *b == 0) {
        if !record.ends_with(&[0]) {
            if out.truncated {
                break;
            }
            return Err(protocol("incomplete Git stats"));
        }
        let mut fields = record[..record.len() - 1].splitn(3, |b| *b == b'\t');
        let added = fields.next().unwrap_or_default();
        let removed = fields.next().ok_or_else(|| protocol("invalid Git stats"))?;
        if fields.next().is_none() {
            return Err(protocol("missing Git stats path"));
        }
        if added == b"-" && removed == b"-" {
            stats.binary = true;
            continue;
        }
        for (total, field) in [(&mut stats.added, added), (&mut stats.removed, removed)] {
            let count = std::str::from_utf8(field)
                .ok()
                .and_then(|s| s.parse::<u32>().ok())
                .ok_or_else(|| protocol("invalid Git stats count"))?;
            *total = total
                .checked_add(count)
                .filter(|n| *n <= i32::MAX as u32)
                .ok_or_else(|| protocol("Git stats overflow"))?;
        }
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum GitDiffKind {
    Staged,
    Unstaged,
    Untracked,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GitDiffLine {
    pub text: String,
    pub old_line: Option<u32>,
    pub new_line: Option<u32>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GitDiff {
    pub lines: Vec<GitDiffLine>,
    pub binary: bool,
    pub truncated: bool,
}

/// Porcelain v1 -z uses raw paths, with destination then source for renames/copies.
/// Reject malformed/invalid UTF-8 paths rather than executing against a different name.
pub fn parse_git_status(bytes: &[u8]) -> Result<Vec<GitFile>, CoreError> {
    if !bytes.is_empty() && !bytes.ends_with(&[0]) {
        return Err(protocol("incomplete Git status"));
    }
    let mut records = bytes.split(|b| *b == 0).peekable();
    let mut files = Vec::new();
    while let Some(record) = records.next() {
        if record.is_empty() {
            continue;
        }
        if record.len() < 4 || record[2] != b' ' {
            return Err(protocol("invalid Git status"));
        }
        let status =
            std::str::from_utf8(&record[..2]).map_err(|_| protocol("invalid Git status"))?;
        let path = std::str::from_utf8(&record[3..])
            .map_err(|_| protocol("Git path is not UTF-8"))?
            .to_owned();
        let original_path = if record[..2].iter().any(|b| *b == b'R' || *b == b'C') {
            let source = records
                .next()
                .filter(|s| !s.is_empty())
                .ok_or_else(|| protocol("missing Git rename source"))?;
            Some(
                std::str::from_utf8(source)
                    .map_err(|_| protocol("Git path is not UTF-8"))?
                    .to_owned(),
            )
        } else {
            None
        };
        let untracked = status == "??";
        files.push(GitFile {
            path,
            original_path,
            status: status.into(),
            untracked,
            staged: !untracked && record[0] != b' ' && record[0] != b'!',
            unstaged: !untracked && record[1] != b' ' && record[1] != b'!',
        });
    }
    Ok(files)
}

pub fn parse_git_diff(text: &str, truncated: bool) -> GitDiff {
    let mut old = None;
    let mut new = None;
    let lines = text
        .lines()
        .map(|text| {
            let (mut old_line, mut new_line) = (None, None);
            if text.starts_with("@@ ") {
                let mut parts = text.split_whitespace();
                parts.next();
                old = parts
                    .next()
                    .and_then(|p| p.strip_prefix('-'))
                    .and_then(|p| p.split(',').next())
                    .and_then(|p| p.parse::<u32>().ok());
                new = parts
                    .next()
                    .and_then(|p| p.strip_prefix('+'))
                    .and_then(|p| p.split(',').next())
                    .and_then(|p| p.parse::<u32>().ok());
            } else if old.is_some() && new.is_some() {
                match text.as_bytes().first() {
                    Some(b' ') => {
                        old_line = old;
                        new_line = new;
                        old = old.and_then(|n| n.checked_add(1));
                        new = new.and_then(|n| n.checked_add(1));
                    }
                    Some(b'-') => {
                        old_line = old;
                        old = old.and_then(|n| n.checked_add(1));
                    }
                    Some(b'+') => {
                        new_line = new;
                        new = new.and_then(|n| n.checked_add(1));
                    }
                    Some(b'\\') => {}
                    _ => {
                        old = None;
                        new = None;
                    }
                }
            }
            GitDiffLine {
                text: text.into(),
                old_line,
                new_line,
            }
        })
        .collect();
    GitDiff {
        lines,
        binary: text
            .lines()
            .any(|l| l.starts_with("Binary files ") || l == "GIT binary patch"),
        truncated,
    }
}

fn check_output(out: &ExecOutput, diff: bool) -> Result<(), CoreError> {
    if out.timed_out
        || out.signal.is_some()
        || !(out.code == Some(0) || diff && out.code == Some(1))
    {
        return Err(CoreError::Rpc {
            kind: "git_failed".into(),
            detail: format!(
                "Git exit {:?}, timeout {}: {}",
                out.code,
                out.timed_out,
                String::from_utf8_lossy(&out.stderr)
            ),
        });
    }
    Ok(())
}

fn machine(address: &str) -> Option<String> {
    address.rsplit_once('/').map(|(m, _)| m.to_owned())
}

#[uniffi::export]
impl HostConnection {
    /// One aggregate request with no per-file patches or per-file remote round trips.
    pub async fn git_stats(&self, address: String, cwd: String) -> Result<GitStats, CoreError> {
        let read = |staged: bool| {
            let mut argv = [
                "git",
                "--no-optional-locks",
                "diff",
                "--no-ext-diff",
                "--no-textconv",
                "--numstat",
                "-z",
                "--no-renames",
            ]
            .map(str::to_owned)
            .to_vec();
            if staged {
                argv.push("--cached".into());
            }
            argv.push("--".into());
            self.machine_exec_on(argv, Some(cwd.clone()), 20, machine(&address))
        };
        let (staged, unstaged) = tokio::try_join!(read(true), read(false))?;
        let mut stats = GitStats::default();
        add_numstat(&mut stats, &staged)?;
        add_numstat(&mut stats, &unstaged)?;
        Ok(stats)
    }

    /// Resolve foreground.cwd at opening time, falling back to the launch folder.
    pub async fn git_changes(&self, address: String) -> Result<GitChanges, CoreError> {
        self.git_changes_in(address, None).await
    }

    pub async fn git_diff(
        &self,
        address: String,
        cwd: String,
        path: String,
        kind: GitDiffKind,
    ) -> Result<GitDiff, CoreError> {
        // A caller may hold an old selection: only repository-relative paths are accepted.
        if path.is_empty()
            || std::path::Path::new(&path).is_absolute()
            || path.split('/').any(|p| p == "..")
        {
            return Err(protocol("expected repository-relative Git path"));
        }
        let mut argv: Vec<String> = [
            "git",
            "--no-optional-locks",
            "diff",
            "--no-ext-diff",
            "--no-textconv",
            "--no-color",
            "--src-prefix=a/",
            "--dst-prefix=b/",
        ]
        .map(str::to_owned)
        .to_vec();
        match kind {
            GitDiffKind::Staged => {
                argv.extend(["--cached".into(), "--".into(), format!(":(literal){path}")])
            }
            GitDiffKind::Unstaged => argv.extend(["--".into(), format!(":(literal){path}")]),
            GitDiffKind::Untracked => argv.extend([
                "--no-index".into(),
                "--".into(),
                "/dev/null".into(),
                format!("./{path}"),
            ]),
        }
        let out = self
            .machine_exec_on(argv, Some(cwd), 20, machine(&address))
            .await?;
        check_output(&out, kind == GitDiffKind::Untracked)?;
        // A clipped final line must not masquerade as a complete selectable line.
        let mut bytes = out.stdout.as_slice();
        if out.truncated {
            bytes = &bytes[..bytes.iter().rposition(|b| *b == b'\n').map_or(0, |n| n + 1)];
        }
        Ok(parse_git_diff(
            &String::from_utf8_lossy(bytes),
            out.truncated,
        ))
    }
}

impl HostConnection {
    /// Also usable by Rust readers that already have a folder, without another terminal.get.
    pub async fn git_changes_in(
        &self,
        address: String,
        folder: Option<String>,
    ) -> Result<GitChanges, CoreError> {
        let cwd = match folder {
            Some(folder) => folder,
            None => {
                let client = self.client.clone();
                let address = address.clone();
                let v = on_runtime(async move {
                    client
                        .call("terminal.get", json!({"terminal": address}))
                        .await
                })
                .await?;
                v["foreground"]["cwd"]
                    .as_str()
                    .filter(|s| !s.is_empty())
                    .or_else(|| v["cwd"].as_str().filter(|s| !s.is_empty()))
                    .ok_or_else(|| protocol("Terminal has no working folder"))?
                    .to_owned()
            }
        };
        let on = machine(&address);
        let root = self
            .machine_exec_on(
                vec![
                    "git".into(),
                    "--no-optional-locks".into(),
                    "rev-parse".into(),
                    "--show-toplevel".into(),
                ],
                Some(cwd.clone()),
                20,
                on.clone(),
            )
            .await?;
        if root.code == Some(128) && !root.timed_out && !root.truncated {
            return Ok(GitChanges {
                cwd,
                files: vec![],
                truncated: false,
                not_repository: true,
            });
        }
        check_output(&root, false)?;
        if root.truncated {
            return Err(protocol("truncated Git root"));
        }
        let cwd = String::from_utf8(root.stdout)
            .map_err(|_| protocol("Git path is not UTF-8"))?
            .strip_suffix('\n')
            .unwrap_or("")
            .to_owned();
        if cwd.is_empty() {
            return Err(protocol("missing Git root"));
        }
        let out = self
            .machine_exec_on(
                [
                    "git",
                    "--no-optional-locks",
                    "status",
                    "--porcelain=v1",
                    "-z",
                    "--untracked-files=all",
                ]
                .map(str::to_owned)
                .to_vec(),
                Some(cwd.clone()),
                20,
                on,
            )
            .await?;
        check_output(&out, false)?;
        let mut bytes = out.stdout.as_slice();
        if out.truncated {
            // A clipped rename may end after its destination. Drop incomplete records below.
            bytes = &bytes[..bytes.iter().rposition(|b| *b == 0).map_or(0, |n| n + 1)];
            if parse_git_status(bytes).is_err() {
                bytes = &bytes[..bytes[..bytes.len().saturating_sub(1)]
                    .iter()
                    .rposition(|b| *b == 0)
                    .map_or(0, |n| n + 1)];
            }
        }
        Ok(GitChanges {
            cwd,
            files: parse_git_status(bytes)?,
            truncated: out.truncated,
            not_repository: false,
        })
    }
}
