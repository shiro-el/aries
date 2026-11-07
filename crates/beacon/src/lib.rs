//! The Beacon's library half: everything `main.rs` subcommands share and
//! the tests drive directly.

pub mod chat;
pub mod claude_plugin;
pub mod core_link;
pub mod host_pairing;
pub mod opencode_plugin;
pub mod pi_extension;
pub mod progress;
pub mod skill;
pub mod status_watch;

use std::path::PathBuf;

/// The user's home directory (`/` if `HOME` is unset).
pub fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
}

/// Same lookup the phone uses over SSH: `PATH`, then `~/.local/bin`.
pub fn herdr_core() -> Option<PathBuf> {
    let path = std::env::var_os("PATH").unwrap_or_default();
    std::env::split_paths(&path)
        .map(|d| d.join("herdr-core"))
        .chain(std::iter::once(home().join(".local/bin/herdr-core")))
        .find(|p| p.is_file())
}

/// Private state shared by the CLI and transient Chat sources.
pub fn state_dir() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(".local/state"))
        .join("aries-beacon")
}
