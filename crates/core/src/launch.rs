//! Launching a Terminal from a command line the user typed
//! ([`crate::HostConnection::launch`]).
//!
//! The Terminal is placed with the documented `herdr.space` / `herdr.tab`
//! labels; the `herdr-app` document is never written (herdr-app adopts
//! labelled Terminals into that Space/Tab itself). Until it does,
//! `structure::build` shows the Terminal in the Tab its labels name.
//!
//! Recently used working directories are kept per Host in
//! `hosts/<id>/recent-cwds.json` under the data dir, which Host removal
//! deletes with the rest of [`crate::store::Store::host_dir`].

use std::path::PathBuf;

use serde_json::{Map, Value, json};

use crate::error::{CoreError, storage};
use crate::structure::{SPACE_LABEL, TAB_LABEL};

/// How many recent working directories are kept per Host.
pub const RECENT_CWDS_KEPT: u32 = 8;

/// What to start, where. Blank fields count as unset.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LaunchRequest {
    /// A command line. Plain words run as argv, so a missing program is
    /// reported as `spawn_failed`; anything with shell syntax (quotes, pipes,
    /// `;`, `$`, ...) runs through the Host's `sh -c`. Blank: the Host's shell.
    pub command: String,
    /// Working directory on the Host. `None`: herdr-core's default.
    pub cwd: Option<String>,
    /// Space to put the Terminal in (`herdr.space` label).
    pub space_id: Option<String>,
    /// Tab inside that Space (`herdr.tab` label); ignored without a Space.
    pub tab_id: Option<String>,
}

fn non_blank(s: &Option<String>) -> Option<&str> {
    s.as_deref().map(str::trim).filter(|s| !s.is_empty())
}

/// `terminal.run` params for `req`.
pub(crate) fn run_params(req: &LaunchRequest) -> Value {
    let mut params = Map::new();
    let command = req.command.trim();
    if needs_shell(command) {
        params.insert("line".into(), json!(command));
    } else if !command.is_empty() {
        params.insert(
            "argv".into(),
            json!(command.split_whitespace().collect::<Vec<_>>()),
        );
    }
    if let Some(cwd) = non_blank(&req.cwd) {
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

/// The cwd to remember after a successful launch of `req`.
pub(crate) fn cwd_to_remember(req: &LaunchRequest) -> Option<&str> {
    non_blank(&req.cwd)
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
