//! Allowlisted transcript measurements only. Never forward account identity,
//! plan, credit balance, or cumulative response tokens as account usage.
use aries_protocol::chat::{AgentUsage, ChatEvent, ContextUsage, UsageLimit};
use serde_json::Value;

pub fn codex(usage: &mut AgentUsage, record: &Value) -> Option<ChatEvent> {
    let p = &record["payload"];
    let ts = record["timestamp"].as_str().map(str::to_owned);
    let mut changed = false;
    if p["type"] == "task_started" && p["model_context_window"].as_u64().is_some() {
        usage.context = Some(ContextUsage {
            used_tokens: None,
            window_tokens: p["model_context_window"].as_u64().filter(|n| *n > 0),
            updated_at: ts.clone(),
        });
        changed = true;
    }
    if p["type"] == "token_count" {
        let info = &p["info"];
        if info.is_object() {
            usage.context = Some(ContextUsage {
                used_tokens: info["last_token_usage"]["total_tokens"].as_u64(),
                window_tokens: info["model_context_window"].as_u64().filter(|n| *n > 0),
                updated_at: ts.clone(),
            });
            changed = true;
        }
        if let Some(limits) = p["rate_limits"].as_object() {
            usage.limits = ["primary", "secondary"]
                .into_iter()
                .filter_map(|name| {
                    let v = limits.get(name)?.as_object()?;
                    Some(UsageLimit {
                        name: name.into(),
                        window_minutes: v.get("window_minutes").and_then(Value::as_u64),
                        used_basis_points: v.get("used_percent").and_then(|v| percent(v, 100.0)),
                        resets_at: v.get("resets_at").and_then(Value::as_u64),
                        updated_at: ts.clone(),
                    })
                })
                .collect();
            changed = true;
        }
    }
    changed.then(|| ChatEvent::Usage {
        usage: usage.clone(),
    })
}

fn percent(value: &Value, scale: f64) -> Option<u32> {
    let n = value.as_f64()? * scale;
    (n.is_finite() && (0.0..=10000.0).contains(&n)).then(|| n.round() as u32)
}

#[derive(Default)]
pub struct ClaudeUsage {
    usage: AgentUsage,
    model: Option<String>,
}

impl ClaudeUsage {
    pub fn record(&mut self, record: &Value) -> Option<ChatEvent> {
        let ts = record["timestamp"].as_str().map(str::to_owned);
        match record["type"].as_str()? {
            "assistant" => {
                let message = &record["message"];
                let model = message["model"].as_str().map(str::to_owned);
                if self.model != model {
                    self.usage.context = None;
                }
                self.model = model;
                let u = &message["usage"];
                // The last prompt, including cached prompt tokens, not the
                // cumulative billing counts in result.modelUsage.
                let used = u["input_tokens"].as_u64().and_then(|n| {
                    n.checked_add(u["cache_read_input_tokens"].as_u64()?)?
                        .checked_add(u["cache_creation_input_tokens"].as_u64()?)
                });
                let window = self.usage.context.as_ref().and_then(|c| c.window_tokens);
                self.usage.context = Some(ContextUsage {
                    used_tokens: used,
                    window_tokens: window,
                    updated_at: ts,
                });
            }
            "result" => {
                let window = self.model.as_ref().and_then(|m| {
                    record["modelUsage"][m]["contextWindow"]
                        .as_u64()
                        .filter(|n| *n > 0)
                })?;
                // A capacity alone cannot give a remaining percentage.
                self.usage.context.get_or_insert_default().window_tokens = Some(window);
            }
            "rate_limit_event" => {
                // CLI wire keys are camelCase; SDKs may store snake_case.
                let info = &record["rate_limit_info"];
                let name = info["rateLimitType"]
                    .as_str()
                    .or_else(|| info["rate_limit_type"].as_str())?;
                let window = match name {
                    "five_hour" => Some(300),
                    "seven_day" | "seven_day_opus" | "seven_day_sonnet" => Some(10080),
                    _ => return None,
                };
                let limit = UsageLimit {
                    name: name.into(),
                    window_minutes: window,
                    used_basis_points: percent(&info["utilization"], 10000.0),
                    resets_at: info["resetsAt"]
                        .as_u64()
                        .or_else(|| info["resets_at"].as_u64()),
                    updated_at: ts,
                };
                if let Some(old) = self.usage.limits.iter_mut().find(|l| l.name == name) {
                    *old = limit;
                } else {
                    self.usage.limits.push(limit);
                }
            }
            "system" if record["subtype"] == "compact_boundary" => {
                if let Some(context) = &mut self.usage.context {
                    context.used_tokens = None;
                    context.updated_at = ts;
                }
            }
            _ => return None,
        }
        Some(ChatEvent::Usage {
            usage: self.usage.clone(),
        })
    }
}
