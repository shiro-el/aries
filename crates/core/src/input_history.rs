//! Phone-local composer input, shared by Terminal and Chat. Terminal addresses
//! are JSON keys, never paths. The enclosing Host directory owns cleanup.
use std::{collections::BTreeMap, path::PathBuf};

use crate::{
    KeyVault,
    error::{CoreError, storage},
    private_content,
};

pub(crate) struct InputHistory<'a> {
    vault: &'a dyn KeyVault,
    path: PathBuf,
}

impl<'a> InputHistory<'a> {
    pub fn new(host_dir: PathBuf, vault: &'a dyn KeyVault) -> Self {
        Self {
            vault,
            path: host_dir.join("input-history.json"),
        }
    }

    fn read(&self) -> Result<BTreeMap<String, Vec<String>>, CoreError> {
        match private_content::read(&self.path, self.vault)? {
            Some(bytes) => serde_json::from_slice(&bytes).map_err(storage),
            None => Ok(BTreeMap::new()),
        }
    }

    pub fn list(&self, terminal: &str) -> Result<Vec<String>, CoreError> {
        Ok(self.read()?.remove(terminal).unwrap_or_default())
    }

    pub fn remember(&self, terminal: String, text: String) -> Result<(), CoreError> {
        let mut all = self.read()?;
        let entries = all.entry(terminal).or_default();
        entries.insert(0, text);
        entries.truncate(50);
        self.write(&all)
    }

    pub fn clear(&self, terminal: &str) -> Result<(), CoreError> {
        let mut all = self.read()?;
        if all.remove(terminal).is_none() {
            return Ok(());
        }
        self.write(&all)
    }

    fn write(&self, all: &BTreeMap<String, Vec<String>>) -> Result<(), CoreError> {
        private_content::write(
            &self.path,
            serde_json::to_vec(all).map_err(storage)?,
            self.vault,
        )
    }
}
