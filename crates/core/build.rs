//! Pins the Beacon release into the core.
//!
//! `ARIES_BEACON_MANIFEST` is the absolute path of the `beacon-release.json`
//! that `scripts/beacon-manifest.sh` wrote for the release being built; the
//! release workflow sets it. Without it (development builds) the core pins a
//! release with no artifacts, and installing reports `BeaconUnavailable`.

use std::path::{Path, PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=ARIES_BEACON_MANIFEST");
    let out =
        PathBuf::from(std::env::var_os("OUT_DIR").expect("OUT_DIR")).join("beacon-release.json");
    let version = std::env::var("CARGO_PKG_VERSION").expect("CARGO_PKG_VERSION");
    let json = match std::env::var_os("ARIES_BEACON_MANIFEST").filter(|v| !v.is_empty()) {
        Some(path) => {
            let path = Path::new(&path);
            assert!(
                path.is_absolute(),
                "ARIES_BEACON_MANIFEST must be an absolute path: {}",
                path.display()
            );
            println!("cargo:rerun-if-changed={}", path.display());
            let text = std::fs::read_to_string(path)
                .unwrap_or_else(|e| panic!("ARIES_BEACON_MANIFEST {}: {e}", path.display()));
            if let Err(e) = check(&text, &version) {
                panic!("ARIES_BEACON_MANIFEST {}: {e}", path.display());
            }
            text
        }
        None => format!(r#"{{"version": "{version}", "artifacts": []}}"#),
    };
    std::fs::write(&out, json).expect("write beacon-release.json");
}

/// The manifest pins this crate's version and well-formed artifacts, so
/// `pinned_release()` cannot fail at run time.
fn check(text: &str, version: &str) -> Result<(), String> {
    let v: serde_json::Value = serde_json::from_str(text).map_err(|e| e.to_string())?;
    if v["version"] != version {
        return Err(format!(
            "version {} is not the crate version {version}",
            v["version"]
        ));
    }
    let artifacts = v["artifacts"].as_array().ok_or("no artifacts array")?;
    let mut seen = Vec::new();
    for a in artifacts {
        let field = |k: &str| a[k].as_str().ok_or(format!("artifact without {k}: {a}"));
        let (os, arch, url, sha) = (
            field("os")?,
            field("arch")?,
            field("url")?,
            field("sha256")?,
        );
        if !matches!(os, "linux" | "macos") || !matches!(arch, "x86_64" | "aarch64") {
            return Err(format!("unknown target {os}/{arch}"));
        }
        if seen.contains(&(os, arch)) {
            return Err(format!("{os}/{arch} listed twice"));
        }
        seen.push((os, arch));
        if !(url.starts_with("https://") || url.starts_with("http://")) {
            return Err(format!("{os}/{arch}: url {url} is not http(s)"));
        }
        if sha.len() != 64
            || !sha
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(format!(
                "{os}/{arch}: sha256 {sha} is not lowercase hex SHA-256"
            ));
        }
    }
    Ok(())
}
