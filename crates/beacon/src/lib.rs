//! The Beacon's library half: everything `main.rs` subcommands share and
//! the tests drive directly.

pub mod chat;
pub mod core_link;
pub mod pi_extension;
pub mod skill;

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
