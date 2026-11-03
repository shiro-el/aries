//! Read-only worktree discovery. NUL-delimited porcelain preserves paths
//! containing newlines, quotes and Git's quoting metacharacters.
use crate::{AgentPreset, CoreError, HostConnection, LaunchRequest, LayoutEdit, error::protocol};

#[derive(Debug, Clone, PartialEq, Eq, Default, uniffi::Record)]
pub struct WorktreeInfo {
    pub path: String,
    pub head: Option<String>,
    pub branch: Option<String>,
    pub detached: bool,
    pub bare: bool,
    /// An empty reason still means locked/prunable.
    pub locked: Option<String>,
    pub prunable: Option<String>,
}

fn parse_porcelain(bytes: &[u8]) -> Result<Vec<WorktreeInfo>, CoreError> {
    let text = std::str::from_utf8(bytes).map_err(|e| protocol(e.to_string()))?;
    if !text.is_empty() && !text.ends_with("\0\0") {
        return Err(protocol("incomplete worktree porcelain"));
    }
    let mut list = Vec::new();
    let mut current: Option<WorktreeInfo> = None;
    for field in text.split('\0') {
        if field.is_empty() {
            if let Some(entry) = current.take() {
                list.push(entry);
            }
            continue;
        }
        let (key, value) = field.split_once(' ').unwrap_or((field, ""));
        if key == "worktree" {
            if current.is_some() || value.is_empty() {
                return Err(protocol("invalid worktree record"));
            }
            current = Some(WorktreeInfo {
                path: value.into(),
                ..Default::default()
            });
            continue;
        }
        let entry = current
            .as_mut()
            .ok_or_else(|| protocol("missing worktree path"))?;
        match key {
            "HEAD" => entry.head = Some(value.into()),
            "branch" => entry.branch = Some(value.into()),
            "detached" => entry.detached = true,
            "bare" => entry.bare = true,
            "locked" => entry.locked = Some(value.into()),
            "prunable" => entry.prunable = Some(value.into()),
            _ => {} // Future Git metadata is safe to ignore.
        }
    }
    Ok(list)
}

/// Where a worktree opened, so the phone can reveal it without following
/// the desktop's selection.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum WorktreeOpen {
    Tab {
        space_id: String,
        tab_id: String,
        address: String,
    },
    Terminal {
        address: String,
    },
}

#[uniffi::export]
impl HostConnection {
    /// Opens a worktree in an explicitly chosen Space. An unavailable
    /// app or absent Space permits fallback; an edit error may have already created a Tab.
    pub async fn open_worktree(
        &self,
        folder: String,
        space_id: Option<String>,
        preset: Option<AgentPreset>,
    ) -> Result<WorktreeOpen, CoreError> {
        if folder.is_empty() || space_id.as_deref() == Some("") {
            return Err(protocol(
                "worktree folder and any supplied Space must be nonempty",
            ));
        }
        if let Some(space_id) = space_id.as_ref()
            && self.herdr_app_available().await?
        {
            let response = self
                .edit_layout(LayoutEdit::NewTab {
                    space_id: space_id.clone(),
                    name: String::new(),
                    folder: Some(folder),
                    argv: preset.map(|p| p.argv().into_iter().map(str::to_owned).collect()),
                })
                .await?;
            let result: serde_json::Value = serde_json::from_str(&response)
                .map_err(|e| protocol(format!("invalid tab.new result: {e}")))?;
            let field = |key: &str| {
                result[key]
                    .as_str()
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
                    .ok_or_else(|| protocol(format!("missing tab.new {key}")))
            };
            Ok(WorktreeOpen::Tab {
                space_id: field("space")?,
                tab_id: field("tab")?,
                address: field("terminal")?,
            })
        } else {
            let terminal = self
                .launch(LaunchRequest {
                    command: String::new(),
                    cwd: Some(folder),
                    space_id,
                    tab_id: None,
                    preset,
                    model: None,
                    effort: None,
                })
                .await?;
            Ok(WorktreeOpen::Terminal {
                address: terminal.address,
            })
        }
    }

    /// Lists worktrees of the repository containing this Host folder. This
    /// never creates, prunes, unlocks or removes a worktree.
    pub async fn list_worktrees(&self, cwd: String) -> Result<Vec<WorktreeInfo>, CoreError> {
        let out = self
            .machine_exec(
                [
                    "git",
                    "--no-optional-locks",
                    "worktree",
                    "list",
                    "--porcelain",
                    "-z",
                ]
                .into_iter()
                .map(str::to_owned)
                .collect(),
                Some(cwd),
                10,
            )
            .await?;
        if out.code != Some(0) || out.timed_out || out.truncated {
            return Err(CoreError::Rpc {
                kind: "worktree_list_failed".into(),
                detail: format!(
                    "git worktree list (exit {:?}, timeout {}, truncated {}): {}",
                    out.code,
                    out.timed_out,
                    out.truncated,
                    String::from_utf8_lossy(&out.stderr)
                ),
            });
        }
        parse_porcelain(&out.stdout)
    }
}
