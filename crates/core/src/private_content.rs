//! Local user-authored content, sealed through the platform vault before any
//! filesystem write. PLAIN permits offline/background access; it does not mean
//! plaintext storage. Temporary files always contain ciphertext too.
use std::path::Path;

use crate::{
    KeyProtection, KeyVault,
    error::{CoreError, storage},
};

const MAGIC: &[u8] = b"aries-private-v1\0";

pub(crate) fn read(path: &Path, vault: &dyn KeyVault) -> Result<Option<Vec<u8>>, CoreError> {
    match std::fs::read(path) {
        Ok(bytes) => {
            let sealed = bytes
                .strip_prefix(MAGIC)
                .ok_or_else(|| storage("unsealed private content"))?;
            Ok(Some(vault.open(sealed.to_vec(), KeyProtection::Plain)?))
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(storage(e)),
    }
}

pub(crate) fn write(path: &Path, plain: Vec<u8>, vault: &dyn KeyVault) -> Result<(), CoreError> {
    let mut bytes = MAGIC.to_vec();
    bytes.extend(vault.seal(plain, KeyProtection::Plain)?);
    std::fs::create_dir_all(path.parent().expect("private content parent")).map_err(storage)?;
    let tmp = path.with_extension("sealed.tmp");
    std::fs::write(&tmp, bytes).map_err(storage)?;
    std::fs::rename(tmp, path).map_err(storage)
}

/// Runs for every saved Host at core startup, including unopened drafts and
/// interrupted legacy writes. A failed seal leaves the original intact and
/// aborts startup so we never resume writing plaintext.
pub(crate) fn upgrade(host: &Path, vault: &dyn KeyVault) -> Result<(), CoreError> {
    migrate(&host.join("input-history.json"), vault)?;
    remove_if_present(&host.join("input-history.json.tmp"))?;
    let drafts = host.join("github-drafts");
    let entries = match std::fs::read_dir(drafts) {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(storage(e)),
    };
    for entry in entries {
        let path = entry.map_err(storage)?.path();
        match path.extension().and_then(|e| e.to_str()) {
            Some("txt") => migrate(&path, vault)?,
            Some("tmp") => remove_if_present(&path)?,
            _ => {}
        }
    }
    Ok(())
}

fn migrate(path: &Path, vault: &dyn KeyVault) -> Result<(), CoreError> {
    match std::fs::read(path) {
        Ok(bytes) if !bytes.starts_with(MAGIC) => write(path, bytes, vault),
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(storage(e)),
    }
}

fn remove_if_present(path: &Path) -> Result<(), CoreError> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(storage(e)),
    }
}
