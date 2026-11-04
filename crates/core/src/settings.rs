//! The attach-mode and Agent view options. Kept with the other app-wide options in
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

/// How an Agent's Terminal opens: the view the user last picked with the
/// Terminal/Chat toggle.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum AgentView {
    #[default]
    Terminal,
    Chat,
}

#[uniffi::export]
impl AriesCore {
    /// How opening a Terminal attaches; [`AttachMode::Observe`] until changed.
    pub fn attach_mode(&self) -> Result<AttachMode, CoreError> {
        Ok(self.store.read_settings()?.attach_mode)
    }

    pub fn set_attach_mode(&self, mode: AttachMode) -> Result<(), CoreError> {
        self.store
            .update_settings(|settings| settings.attach_mode = mode, |_| {})
    }

    /// How an Agent's Terminal opens; [`AgentView::Terminal`] until changed.
    pub fn agent_view(&self) -> Result<AgentView, CoreError> {
        Ok(self.store.read_settings()?.agent_view)
    }

    pub fn set_agent_view(&self, view: AgentView) -> Result<(), CoreError> {
        self.store
            .update_settings(|settings| settings.agent_view = view, |_| {})
    }
}
