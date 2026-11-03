//! The attach-mode option. Kept with the other app-wide options in
//! `settings.json` ([`crate::store::Settings`]).

use serde::{Deserialize, Serialize};

use crate::AriesCore;
use crate::error::CoreError;

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

#[uniffi::export]
impl AriesCore {
    /// How opening a Terminal attaches; [`AttachMode::Observe`] until changed.
    pub fn attach_mode(&self) -> Result<AttachMode, CoreError> {
        Ok(self.store.read_settings().attach_mode)
    }

    pub fn set_attach_mode(&self, mode: AttachMode) -> Result<(), CoreError> {
        let mut settings = self.store.read_settings();
        settings.attach_mode = mode;
        self.store.write_settings(&settings)
    }
}
