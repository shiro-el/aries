//! The phone's Agent usage readings. Scoped to a transcript, never inferred
//! from response metadata or combined across unknown accounts.
use aries_protocol::chat as wire;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AgentUsage {
    pub context: Option<ContextUsage>,
    pub limits: Vec<UsageLimit>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContextUsage {
    pub used_tokens: Option<u64>,
    pub window_tokens: Option<u64>,
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct UsageLimit {
    pub name: String,
    pub window_minutes: Option<u64>,
    /// Hundredths of a percent. None is not 0%.
    pub used_basis_points: Option<u32>,
    pub resets_at: Option<u64>,
    pub updated_at: Option<String>,
}

impl From<wire::AgentUsage> for AgentUsage {
    fn from(value: wire::AgentUsage) -> Self {
        Self {
            context: value.context.map(|c| ContextUsage {
                used_tokens: c.used_tokens,
                window_tokens: c.window_tokens,
                updated_at: c.updated_at,
            }),
            limits: value
                .limits
                .into_iter()
                .map(|l| UsageLimit {
                    name: l.name,
                    window_minutes: l.window_minutes,
                    used_basis_points: l.used_basis_points,
                    resets_at: l.resets_at,
                    updated_at: l.updated_at,
                })
                .collect(),
        }
    }
}
