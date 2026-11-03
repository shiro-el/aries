//! Launching a Terminal from a command line the user typed
//! ([`crate::HostConnection::launch`]).
//!
//! The Terminal is placed with the documented `herdr.space` / `herdr.tab`
//! labels; the `herdr-app` document is never written (herdr-app adopts
//! labelled Terminals into that Space/Tab itself). Until it does,
//! `structure::build` shows the Terminal in the Tab its labels name.
//!
//! An [`AgentPreset`] starts one of the four Agents with what its
//! integration needs (see `docs/research/*-integration.md`): Claude Code
//! without any inherited `CLAUDE*` variable (a parent session's marker turns
//! its transcript off), Codex with `--no-daemon` (so its commands run in the
//! Terminal and the Beacon can pair its transcript). OpenCode and Pi report
//! status through the Beacon's globally installed plugin/extension, which
//! only needs the `HC_TERMINAL_ID` herdr-core gives every Terminal. Presets
//! never take a first prompt.
//!
//! Named launch configs ([`SavedLaunch`]) are kept per Host in
//! `hosts/<id>/saved-launches.json`.
//!
//! Recently used working directories are kept per Host in
//! `hosts/<id>/recent-cwds.json` under the data dir, which Host removal
//! deletes with the rest of [`crate::store::Store::host_dir`].

use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::error::{CoreError, storage};
use crate::structure::{SPACE_LABEL, TAB_LABEL};

/// How many recent working directories are kept per Host.
pub const RECENT_CWDS_KEPT: u32 = 8;

/// One of the Agents Aries knows how to start.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "kebab-case")]
pub enum AgentPreset {
    ClaudeCode,
    Codex,
    OpenCode,
    Pi,
}

/// Unsets every inherited `CLAUDE*` variable, then becomes `claude` with the
/// remaining arguments. herdr-core's `env` can only set variables, and which
/// ones a parent Claude Code session exported is not known in advance.
const CLAUDE_CLEAN_ENV: &str = r#"for v in $(env | sed -n 's/^\(CLAUDE[A-Za-z0-9_]*\)=.*/\1/p'); do unset "$v"; done; exec claude "$@""#;

impl AgentPreset {
    /// The argv starting this Agent, before the user's extra arguments.
    pub(crate) fn argv(self) -> Vec<&'static str> {
        match self {
            AgentPreset::ClaudeCode => vec!["sh", "-c", CLAUDE_CLEAN_ENV, "claude"],
            AgentPreset::Codex => vec!["codex", "--no-daemon"],
            AgentPreset::OpenCode => vec!["opencode"],
            AgentPreset::Pi => vec!["pi"],
        }
    }
}

/// What to start, where. Blank fields count as unset.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct LaunchRequest {
    /// Without a preset: a command line. Plain words run as argv, so a
    /// missing program is reported as `spawn_failed`; anything with shell
    /// syntax (quotes, pipes, `;`, `$`, ...) runs through the Host's `sh -c`.
    /// Blank: the Host's shell.
    ///
    /// With a preset: extra arguments for the Agent (`--model haiku`), split
    /// on whitespace, never through a shell.
    pub command: String,
    /// Working directory on the Host, preserved verbatim unless blank.
    /// `None`: herdr-core's default.
    pub cwd: Option<String>,
    /// Space to put the Terminal in (`herdr.space` label).
    pub space_id: Option<String>,
    /// Tab inside that Space (`herdr.tab` label); ignored without a Space.
    pub tab_id: Option<String>,
    /// Start this Agent (with `command` as its extra arguments).
    #[uniffi(default = None)]
    #[serde(default)]
    pub preset: Option<AgentPreset>,
    /// Agent model ID (or alias), passed as one argument. Ignored for custom commands.
    #[uniffi(default = None)]
    #[serde(default)]
    pub model: Option<String>,
    /// Agent reasoning effort. OpenCode's interactive launcher has no effort flag.
    /// Ignored for OpenCode and custom commands; blank means the Agent's default.
    #[uniffi(default = None)]
    #[serde(default)]
    pub effort: Option<String>,
}

/// A named [`LaunchRequest`] the user saved for this Host.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct SavedLaunch {
    /// Empty when saving a new one; the core assigns it.
    pub id: String,
    pub name: String,
    pub request: LaunchRequest,
}

fn non_blank(s: &Option<String>) -> Option<&str> {
    s.as_deref().map(str::trim).filter(|s| !s.is_empty())
}

/// `terminal.run` params for `req`.
pub(crate) fn run_params(req: &LaunchRequest) -> Value {
    let mut params = Map::new();
    let command = req.command.trim();
    if let Some(preset) = req.preset {
        let mut argv = preset.argv();
        argv.extend(command.split_whitespace());
        if let Some(model) = non_blank(&req.model) {
            argv.extend([
                if preset == AgentPreset::Codex {
                    "-m"
                } else {
                    "--model"
                },
                model,
            ]);
        }
        let codex_effort;
        if let Some(effort) = non_blank(&req.effort) {
            match preset {
                AgentPreset::ClaudeCode => argv.extend(["--effort", effort]),
                AgentPreset::Codex => {
                    // A quoted TOML string: never reinterpret the value as config syntax.
                    codex_effort = format!("model_reasoning_effort={}", json!(effort));
                    argv.extend(["-c", &codex_effort]);
                }
                AgentPreset::Pi => argv.extend(["--thinking", effort]),
                AgentPreset::OpenCode => {}
            }
        }
        params.insert("argv".into(), json!(argv));
    } else if needs_shell(command) {
        params.insert("line".into(), json!(command));
    } else if !command.is_empty() {
        params.insert(
            "argv".into(),
            json!(command.split_whitespace().collect::<Vec<_>>()),
        );
    }
    if let Some(cwd) = cwd_to_remember(req) {
        params.insert("cwd".into(), json!(cwd));
    }
    let mut labels = Map::new();
    if let Some(space) = non_blank(&req.space_id) {
        labels.insert(SPACE_LABEL.into(), json!(space));
        if let Some(tab) = non_blank(&req.tab_id) {
            labels.insert(TAB_LABEL.into(), json!(tab));
        }
    }
    if !labels.is_empty() {
        params.insert("labels".into(), Value::Object(labels));
    }
    // The phone is no Terminal's parent; never type notes into one.
    params.insert("no_parent".into(), json!(true));
    Value::Object(params)
}

/// Whether `line` uses anything a plain split on whitespace would get wrong.
fn needs_shell(line: &str) -> bool {
    line.chars().any(|c| {
        matches!(
            c,
            '|' | '&'
                | ';'
                | '<'
                | '>'
                | '('
                | ')'
                | '$'
                | '`'
                | '\\'
                | '"'
                | '\''
                | '*'
                | '?'
                | '['
                | ']'
                | '#'
                | '~'
                | '='
                | '{'
                | '}'
                | '!'
                | '\n'
        )
    })
}

/// The recently used working directories of one Host, newest first.
pub(crate) struct RecentCwds {
    path: PathBuf,
}

impl RecentCwds {
    pub fn in_host_dir(host_dir: PathBuf) -> Self {
        Self {
            path: host_dir.join("recent-cwds.json"),
        }
    }

    pub fn list(&self) -> Vec<String> {
        std::fs::read(&self.path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    pub fn remember(&self, cwd: &str) -> Result<(), CoreError> {
        let mut list = self.list();
        list.retain(|c| c != cwd);
        list.insert(0, cwd.to_owned());
        list.truncate(RECENT_CWDS_KEPT as usize);
        let dir = self.path.parent().expect("host dir");
        std::fs::create_dir_all(dir).map_err(storage)?;
        let tmp = dir.join(".recent-cwds.json.tmp");
        std::fs::write(&tmp, serde_json::to_vec(&list).map_err(storage)?).map_err(storage)?;
        std::fs::rename(&tmp, &self.path).map_err(storage)
    }
}

/// The launch configs saved for one Host, in the order they were added.
pub(crate) struct SavedLaunches {
    path: PathBuf,
}

impl SavedLaunches {
    pub fn in_host_dir(host_dir: PathBuf) -> Self {
        Self {
            path: host_dir.join("saved-launches.json"),
        }
    }

    pub fn list(&self) -> Vec<SavedLaunch> {
        std::fs::read(&self.path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    /// Adds `launch` (empty id) or replaces the one with its id.
    pub fn save(&self, mut launch: SavedLaunch) -> Result<SavedLaunch, CoreError> {
        launch.name = launch.name.trim().to_owned();
        let mut list = self.list();
        if launch.id.is_empty() {
            launch.id = crate::new_id();
        }
        match list.iter_mut().find(|l| l.id == launch.id) {
            Some(slot) => *slot = launch.clone(),
            None => list.push(launch.clone()),
        }
        self.write(&list)?;
        Ok(launch)
    }

    pub fn delete(&self, id: &str) -> Result<(), CoreError> {
        let mut list = self.list();
        let before = list.len();
        list.retain(|l| l.id != id);
        if list.len() == before {
            return Ok(());
        }
        self.write(&list)
    }

    fn write(&self, list: &[SavedLaunch]) -> Result<(), CoreError> {
        let dir = self.path.parent().expect("host dir");
        std::fs::create_dir_all(dir).map_err(storage)?;
        let tmp = dir.join(".saved-launches.json.tmp");
        std::fs::write(&tmp, serde_json::to_vec(list).map_err(storage)?).map_err(storage)?;
        std::fs::rename(&tmp, &self.path).map_err(storage)
    }
}

/// The cwd to remember after a successful launch of `req`.
pub(crate) fn cwd_to_remember(req: &LaunchRequest) -> Option<&str> {
    req.cwd.as_deref().filter(|cwd| !cwd.trim().is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(command: &str) -> LaunchRequest {
        LaunchRequest {
            command: command.into(),
            cwd: Some("  ".into()),
            space_id: Some("".into()),
            tab_id: Some("t".into()),
            preset: None,
            model: None,
            effort: None,
        }
    }

    #[test]
    fn plain_words_are_argv_and_shell_syntax_is_a_line() {
        assert_eq!(
            run_params(&req(" codex  --yolo ")),
            json!({"argv": ["codex", "--yolo"], "no_parent": true})
        );
        assert_eq!(
            run_params(&req("make && ./run")),
            json!({"line": "make && ./run", "no_parent": true})
        );
        assert_eq!(
            run_params(&req("echo 'a b'")),
            json!({"line": "echo 'a b'", "no_parent": true})
        );
        assert_eq!(run_params(&req("   ")), json!({"no_parent": true}));
    }
}
