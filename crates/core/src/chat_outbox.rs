//! Phone-owned recovery state. The UI owns its versioned format; the core
//! seals it at rest and never executes or retries anything from this file.
use std::{collections::BTreeMap, path::PathBuf};

use crate::{
    KeyVault,
    error::{CoreError, storage},
    private_content,
};

pub(crate) struct ChatOutbox<'a> {
    vault: &'a dyn KeyVault,
    path: PathBuf,
}

impl<'a> ChatOutbox<'a> {
    pub fn new(host_dir: PathBuf, vault: &'a dyn KeyVault) -> Self {
        Self {
            vault,
            path: host_dir.join("chat-outbox.json"),
        }
    }

    fn read(&self) -> Result<BTreeMap<String, serde_json::Value>, CoreError> {
        match private_content::read(&self.path, self.vault)? {
            Some(bytes) => serde_json::from_slice(&bytes).map_err(storage),
            None => Ok(BTreeMap::new()),
        }
    }

    pub fn get(&self, terminal: &str) -> Result<Option<String>, CoreError> {
        self.read()?
            .remove(terminal)
            .map(serde_json::from_value)
            .transpose()
            .map_err(storage)
    }

    pub fn discard(&self) -> Result<(), CoreError> {
        match std::fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(storage(e)),
        }
    }

    pub fn save(&self, terminal: String, content: Option<String>) -> Result<(), CoreError> {
        let mut all = self.read()?;
        if let Some(content) = content {
            all.insert(terminal, serde_json::Value::String(content));
        } else if all.remove(&terminal).is_none() {
            return Ok(());
        }
        private_content::write(
            &self.path,
            serde_json::to_vec(&all).map_err(storage)?,
            self.vault,
        )
    }
}
