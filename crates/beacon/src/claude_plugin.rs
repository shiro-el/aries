//! Managed local Claude marketplace. Claude alone owns its settings and registry.
use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde_json::{Value, json};

pub const ID: &str = "aries@aries-beacon";
pub const MARKER: &str = ".aries-managed.json";
const FILES: [&str; 3] = [
    ".claude-plugin/marketplace.json",
    "plugins/aries/.claude-plugin/plugin.json",
    "plugins/aries/hooks/hooks.json",
];

pub fn marketplace() -> PathBuf {
    std::env::var_os("XDG_DATA_HOME")
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| crate::home().join(".local/share"))
        .join("aries-beacon/claude-marketplace")
}

pub fn files(binary: &Path) -> BTreeMap<String, String> {
    let quote = |s: &str| format!("'{}'", s.replace('\'', "'\\''"));
    let version = env!("CARGO_PKG_VERSION");
    let values = [
        json!({"name":"aries-beacon", "owner":{"name":"Aries"}, "plugins":[{"name":"aries", "source":"./plugins/aries", "version":version}]}),
        json!({"name":"aries", "version":version, "description":"Beacon permission and question answers"}),
        json!({"hooks":{"PermissionRequest":[{"matcher":"*", "hooks":[{"type":"command", "command":format!("{} claude-hook permission-request", quote(&binary.to_string_lossy())), "timeout":1800}]}]}}),
    ];
    FILES
        .into_iter()
        .zip(values)
        .map(|(p, v)| (p.into(), format!("{v}\n")))
        .collect()
}

fn regular(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|m| m.is_file())
}

/// Every ancestor must be a directory, never a symlink out of the managed tree.
fn safe_path(root: &Path, name: &str) -> bool {
    let mut p = root.to_path_buf();
    if !std::fs::symlink_metadata(&p).is_ok_and(|m| m.is_dir()) {
        return false;
    }
    let parts: Vec<_> = name.split('/').collect();
    for part in &parts[..parts.len() - 1] {
        p.push(part);
        if !std::fs::symlink_metadata(&p).is_ok_and(|m| m.is_dir()) {
            return false;
        }
    }
    true
}

pub fn intact(root: &Path) -> bool {
    if !regular(&root.join(MARKER)) {
        return false;
    }
    let Ok(bytes) = std::fs::read(root.join(MARKER)) else {
        return false;
    };
    let Ok(record) = serde_json::from_slice::<BTreeMap<String, String>>(&bytes) else {
        return false;
    };
    record.len() == FILES.len()
        && FILES.iter().all(|name| {
            safe_path(root, name)
                && regular(&root.join(name))
                && record.get(*name).is_some_and(|s| {
                    std::fs::read(root.join(name)).is_ok_and(|b| b == s.as_bytes())
                })
        })
}

pub fn write_files(root: &Path, files: &BTreeMap<String, String>) -> io::Result<&'static str> {
    let exists = std::fs::symlink_metadata(root).is_ok();
    if exists && !intact(root) {
        return Ok("kept_user_copy");
    }
    let unchanged = exists
        && files
            .iter()
            .all(|(p, s)| std::fs::read(root.join(p)).is_ok_and(|b| b == s.as_bytes()));
    if unchanged {
        return Ok("unchanged");
    }
    for (p, s) in files {
        let path = root.join(p);
        std::fs::create_dir_all(path.parent().unwrap())?;
        std::fs::write(path, s)?;
    }
    std::fs::write(root.join(MARKER), serde_json::to_vec(files)?)?;
    Ok(if exists { "updated" } else { "installed" })
}

pub fn remove_files(root: &Path) -> io::Result<&'static str> {
    if !root.try_exists()? {
        return Ok("absent");
    }
    if !intact(root) {
        return Ok("kept_user_copy");
    }
    // Delete only recorded files; preserve user additions, including subdirectories.
    for name in FILES {
        std::fs::remove_file(root.join(name))?;
    }
    std::fs::remove_file(root.join(MARKER))?;
    for dir in [
        "plugins/aries/.claude-plugin",
        "plugins/aries/hooks",
        "plugins/aries",
        "plugins",
        ".claude-plugin",
        "",
    ] {
        let _ = std::fs::remove_dir(root.join(dir));
    }
    Ok("removed")
}

fn cli(args: &[&str]) -> io::Result<()> {
    let output = Command::new("claude").args(args).output()?;
    if output.status.success() {
        Ok(())
    } else {
        Err(io::Error::other(format!(
            "claude {}: {}{}",
            args.join(" "),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )))
    }
}

pub fn run(action: &str, binary: &Path) -> io::Result<Value> {
    let root = marketplace();
    if action == "status" {
        return Ok(
            json!({"claude": if intact(&root) {"managed"} else if root.exists() {"kept_user_copy"} else {"absent"}, "path":root}),
        );
    }
    if !matches!(action, "install" | "remove") {
        return Err(io::Error::other(
            "usage: claude-plugin install|remove|status",
        ));
    }
    match Command::new("claude").arg("--version").output() {
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            return Ok(json!({"claude":"skipped", "detail":"claude not on PATH"}));
        }
        Err(e) => return Err(e),
        Ok(_) => {}
    }
    let outcome = if action == "install" {
        let outcome = write_files(&root, &files(binary))?;
        if outcome == "kept_user_copy" {
            return Ok(json!({"claude":outcome}));
        }
        cli(&["plugin", "marketplace", "add", &root.to_string_lossy()])?;
        cli(&["plugin", "marketplace", "update", "aries-beacon"])?;
        cli(&["plugin", "install", ID, "--scope", "user"])?;
        cli(&["plugin", "update", ID])?;
        outcome
    } else {
        // Keep files when a CLI operation fails, so uninstall can be retried.
        cli(&["plugin", "uninstall", ID])?;
        cli(&["plugin", "marketplace", "remove", "aries-beacon"])?;
        remove_files(&root)?
    };
    Ok(json!({"claude":outcome, "path":root}))
}
