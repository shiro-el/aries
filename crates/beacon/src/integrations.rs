//! Files the Beacon manages in Agents' own configuration, so the Agent
//! reports to Aries. Today one: the OpenCode plugin
//! (`$XDG_CONFIG_HOME/opencode/plugins/aries-agent-state.js`, OpenCode loads
//! every `.js` there), which reports OSC 7501 status and labels the Terminal
//! with the session id (`docs/research/opencode-integration.md`).
//!
//! The installed file's first line marks it as managed and holds the
//! SHA-256 of the rest. Install and update overwrite only a file whose rest
//! still matches that hash; a file the user edited (or wrote) is kept. The
//! phone runs `aries-beacon integrations install` after installing or
//! updating the Beacon; removing the Beacon deletes an unedited copy (the
//! core's `UNINSTALL_STEPS`, in shell, with the same check).

use std::io::Write;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

/// The plugin as shipped, without its managed-file header.
pub const OPENCODE_PLUGIN: &str = include_str!("../assets/opencode/aries-agent-state.js");
pub const OPENCODE_PLUGIN_FILE: &str = "aries-agent-state.js";
/// Start of the first line of every managed file.
pub const MANAGED: &str = "// aries-managed: ";

/// What [`install_opencode_plugin`] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    Installed,
    Updated,
    Unchanged,
    /// A user-edited or user-written file is there; it was left alone.
    KeptUserCopy,
}

/// `$XDG_CONFIG_HOME`, or `~/.config`.
pub fn config_home() -> PathBuf {
    std::env::var_os("XDG_CONFIG_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| crate::home().join(".config"))
}

pub fn opencode_plugin_path(config_home: &Path) -> PathBuf {
    config_home
        .join("opencode/plugins")
        .join(OPENCODE_PLUGIN_FILE)
}

fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// `body` with its managed-file header.
pub fn managed(name: &str, body: &str) -> String {
    format!(
        "{MANAGED}{name} {} sha256={}\n{body}",
        env!("CARGO_PKG_VERSION"),
        sha256_hex(body.as_bytes())
    )
}

/// A managed file nobody edited: its header's hash matches the rest.
pub fn is_unedited(text: &str) -> bool {
    let Some((header, rest)) = text.split_once('\n') else {
        return false;
    };
    header.starts_with(MANAGED)
        && header
            .rsplit_once(" sha256=")
            .is_some_and(|(_, h)| h == sha256_hex(rest.as_bytes()))
}

/// Installs or updates the OpenCode plugin under `config_home`.
pub fn install_opencode_plugin(config_home: &Path) -> std::io::Result<Outcome> {
    let path = opencode_plugin_path(config_home);
    let want = managed("aries-agent-state", OPENCODE_PLUGIN);
    let outcome = match std::fs::read(&path) {
        Ok(have) if have == want.as_bytes() => return Ok(Outcome::Unchanged),
        Ok(have) if is_unedited(&String::from_utf8_lossy(&have)) => Outcome::Updated,
        Ok(_) => return Ok(Outcome::KeptUserCopy),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Outcome::Installed,
        Err(e) => return Err(e),
    };
    let dir = path.parent().expect("plugin dir");
    std::fs::create_dir_all(dir)?;
    // Whole or not at all: OpenCode may load it any moment.
    let tmp = dir.join(format!(".{OPENCODE_PLUGIN_FILE}.aries-new"));
    let mut f = std::fs::File::create(&tmp)?;
    f.write_all(want.as_bytes())?;
    f.sync_all()?;
    std::fs::rename(&tmp, &path)?;
    Ok(outcome)
}

/// Removes the plugin unless the user edited it. `true` if it was removed.
pub fn remove_opencode_plugin(config_home: &Path) -> std::io::Result<bool> {
    let path = opencode_plugin_path(config_home);
    match std::fs::read(&path) {
        Ok(have) if is_unedited(&String::from_utf8_lossy(&have)) => {
            std::fs::remove_file(&path)?;
            Ok(true)
        }
        Ok(_) => Ok(false),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn installs_updates_and_keeps_edited_copies() {
        let dir = tempfile::tempdir().unwrap();
        let path = opencode_plugin_path(dir.path());
        assert_eq!(
            install_opencode_plugin(dir.path()).unwrap(),
            Outcome::Installed
        );
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.starts_with("// aries-managed: aries-agent-state "));
        assert!(text.ends_with(OPENCODE_PLUGIN));
        assert!(is_unedited(&text));
        assert_eq!(
            install_opencode_plugin(dir.path()).unwrap(),
            Outcome::Unchanged
        );

        // An older Beacon's unedited copy is replaced.
        std::fs::write(&path, managed("aries-agent-state", "// old\n")).unwrap();
        assert_eq!(
            install_opencode_plugin(dir.path()).unwrap(),
            Outcome::Updated
        );
        assert_eq!(std::fs::read_to_string(&path).unwrap(), text);

        // The user's edits stay.
        let edited = text.replace("report(\"idle\")", "report(\"done\")");
        std::fs::write(&path, &edited).unwrap();
        assert_eq!(
            install_opencode_plugin(dir.path()).unwrap(),
            Outcome::KeptUserCopy
        );
        assert!(!remove_opencode_plugin(dir.path()).unwrap());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), edited);

        // So does a file of the user's own under that name.
        std::fs::write(&path, "export const Mine = async () => ({});\n").unwrap();
        assert_eq!(
            install_opencode_plugin(dir.path()).unwrap(),
            Outcome::KeptUserCopy
        );

        std::fs::remove_file(&path).unwrap();
        install_opencode_plugin(dir.path()).unwrap();
        assert!(remove_opencode_plugin(dir.path()).unwrap());
        assert!(!path.exists());
        assert!(!remove_opencode_plugin(dir.path()).unwrap());
    }

    #[test]
    fn other_plugins_are_not_touched() {
        let dir = tempfile::tempdir().unwrap();
        let plugins = dir.path().join("opencode/plugins");
        std::fs::create_dir_all(&plugins).unwrap();
        std::fs::write(plugins.join("herdr-agent-state.js"), "x").unwrap();
        install_opencode_plugin(dir.path()).unwrap();
        remove_opencode_plugin(dir.path()).unwrap();
        assert_eq!(
            std::fs::read_to_string(plugins.join("herdr-agent-state.js")).unwrap(),
            "x"
        );
    }
}
