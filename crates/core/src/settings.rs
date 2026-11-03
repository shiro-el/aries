//! App-wide options the user picks, kept in `settings.json` under the data
//! dir. Unknown or missing fields fall back to their defaults, so later
//! options only add fields.

use serde::{Deserialize, Serialize};

use crate::AriesCore;
use crate::error::{CoreError, storage};

/// How opening a Terminal attaches.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum AttachMode {
    /// Watch only (`observe: true`): the desktop keeps its size. Focusing
    /// the input re-attaches for typing.
    #[default]
    Observe,
    /// Attach ready to type; the phone sets the Terminal's size.
    Input,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub(crate) struct Settings {
    #[serde(default)]
    pub attach_mode: AttachMode,
}

const FILE: &str = "settings.json";

impl AriesCore {
    fn settings(&self) -> Result<Settings, CoreError> {
        match std::fs::read(self.store.path(FILE)) {
            Ok(bytes) => Ok(serde_json::from_slice(&bytes).unwrap_or_default()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Settings::default()),
            Err(e) => Err(storage(e)),
        }
    }

    fn update_settings(&self, f: impl FnOnce(&mut Settings)) -> Result<(), CoreError> {
        let mut s = self.settings()?;
        f(&mut s);
        let bytes = serde_json::to_vec_pretty(&s).map_err(storage)?;
        self.store.write_file(FILE, &bytes)
    }
}

#[uniffi::export]
impl AriesCore {
    /// How opening a Terminal attaches; [`AttachMode::Observe`] until changed.
    pub fn attach_mode(&self) -> Result<AttachMode, CoreError> {
        Ok(self.settings()?.attach_mode)
    }

    pub fn set_attach_mode(&self, mode: AttachMode) -> Result<(), CoreError> {
        self.update_settings(|s| s.attach_mode = mode)
    }
}
