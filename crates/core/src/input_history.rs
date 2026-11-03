//! Phone-local composer input, shared by Terminal and Chat. Terminal addresses
//! are JSON keys, never paths. The enclosing Host directory owns cleanup.
use std::{collections::BTreeMap, path::PathBuf};

use crate::error::{CoreError, storage};

pub(crate) struct InputHistory {
    path: PathBuf,
}

impl InputHistory {
    pub fn new(host_dir: PathBuf) -> Self {
        Self {
            path: host_dir.join("input-history.json"),
        }
    }

    fn read(&self) -> Result<BTreeMap<String, Vec<String>>, CoreError> {
        match std::fs::read(&self.path) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(storage),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(BTreeMap::new()),
            Err(e) => Err(storage(e)),
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
        let dir = self.path.parent().expect("host directory");
        std::fs::create_dir_all(dir).map_err(storage)?;
        let tmp = self.path.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec(all).map_err(storage)?).map_err(storage)?;
        std::fs::rename(tmp, &self.path).map_err(storage)
    }
}
