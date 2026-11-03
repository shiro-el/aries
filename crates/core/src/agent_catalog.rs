//! Read-only Agent catalogs. Host files are data, never executed or installed.
use crate::{AgentPreset, CoreError, HostConnection, error::protocol, on_runtime};
use serde_json::json;
use std::{
    collections::BTreeMap,
    time::{Duration, Instant},
};

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AgentCatalogEntry {
    pub name: String,
    pub token: String,
    pub description: String,
    pub argument_hint: String,
    /// None for built-ins; otherwise the Host's exact source path.
    pub source: Option<String>,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct AgentCatalog {
    pub commands: Vec<AgentCatalogEntry>,
    pub skills: Vec<AgentCatalogEntry>,
}

#[derive(Default)]
pub(crate) struct CatalogCache(BTreeMap<String, (Instant, AgentCatalog)>);

// POSIX tools only (Linux/macOS). Paths go through argv, never shell interpolation.
// NUL-delimited records preserve spaces/newlines; metadata reads are bounded.
const COLLECT: &str = r#"
scan() {
    [ -d "$2" ] || return 0
    find -L "$2" -type f -name "$3" -exec sh -c '
        kind=$1; root=$2; shift 2
        for p do
            [ -r "$p" ] || continue
            printf "%s\000%s\000%s\000" "$kind" "${p#"$root"/}" "$p"
            head -c 8192 "$p"
            printf "\000"
        done' catalog "$1" "$2" {} + || exit 1
}
case "$1" in
claude)
    scan c "$HOME/.claude/commands" '*.md'
    scan s "$HOME/.claude/skills" SKILL.md
    scan c "$PWD/.claude/commands" '*.md'
    scan s "$PWD/.claude/skills" SKILL.md ;;
codex)
    scan c "${CODEX_HOME:-$HOME/.codex}/prompts" '*.md'
    scan s "${CODEX_HOME:-$HOME/.codex}/skills" SKILL.md
    scan s "$HOME/.agents/skills" SKILL.md
    scan s "$PWD/.agents/skills" SKILL.md
    scan s "$PWD/.codex/skills" SKILL.md ;;
opencode)
    scan c "${XDG_CONFIG_HOME:-$HOME/.config}/opencode/commands" '*.md'
    scan s "${XDG_CONFIG_HOME:-$HOME/.config}/opencode/skills" SKILL.md
    scan c "$PWD/.opencode/commands" '*.md'
    scan s "$PWD/.opencode/skills" SKILL.md
    scan s "$HOME/.claude/skills" SKILL.md
    scan s "$HOME/.agents/skills" SKILL.md
    scan s "$PWD/.claude/skills" SKILL.md
    scan s "$PWD/.agents/skills" SKILL.md ;;
pi)
    scan c "$HOME/.pi/agent/prompts" '*.md'
    scan s "$HOME/.pi/agent/skills" SKILL.md
    scan c "$PWD/.pi/prompts" '*.md'
    scan s "$PWD/.pi/skills" SKILL.md
    scan s "$HOME/.agents/skills" SKILL.md
    scan s "$PWD/.agents/skills" SKILL.md ;;
esac
"#;

fn field(text: &str, key: &str) -> String {
    let mut lines = text.lines();
    if lines.next().map(str::trim) != Some("---") {
        return String::new();
    }
    let lines: Vec<_> = lines.take_while(|s| s.trim() != "---").collect();
    for (i, line) in lines.iter().enumerate() {
        if let Some(value) = line.strip_prefix(key).and_then(|s| s.strip_prefix(':')) {
            let value = value.trim();
            if matches!(value, ">" | "|" | ">-" | "|-") {
                return lines[i + 1..]
                    .iter()
                    .take_while(|s| s.starts_with([' ', '\t']))
                    .map(|s| s.trim())
                    .collect::<Vec<_>>()
                    .join(" ");
            }
            return value.trim_matches(['\'', '"']).to_owned();
        }
    }
    String::new()
}

fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 160
        && name
            .chars()
            .all(|c| c.is_alphanumeric() || "-_:".contains(c))
}

fn catalog(bytes: &[u8], preset: AgentPreset) -> Result<AgentCatalog, CoreError> {
    let builtin: &[&str] = match preset {
        AgentPreset::ClaudeCode => &[
            "help",
            "clear",
            "compact",
            "context",
            "cost",
            "model",
            "permissions",
            "resume",
            "status",
        ],
        AgentPreset::Codex => &[
            "help",
            "new",
            "compact",
            "diff",
            "model",
            "permissions",
            "resume",
            "status",
        ],
        AgentPreset::OpenCode => &["help", "new", "compact", "models", "sessions", "status"],
        AgentPreset::Pi => &[
            "help", "new", "compact", "model", "resume", "settings", "tree",
        ],
    };
    let mut commands: BTreeMap<_, _> = builtin
        .iter()
        .map(|name| {
            (
                format!("/{name}"),
                AgentCatalogEntry {
                    name: (*name).into(),
                    token: format!("/{name}"),
                    description: String::new(),
                    argument_hint: String::new(),
                    source: None,
                },
            )
        })
        .collect();
    let mut skills = BTreeMap::new();
    if !bytes.is_empty() && !bytes.ends_with(&[0]) {
        return Err(protocol("incomplete Agent catalog"));
    }
    let fields: Vec<_> = if bytes.is_empty() {
        vec![]
    } else {
        bytes[..bytes.len() - 1].split(|b| *b == 0).collect()
    };
    let (records, remainder) = fields.as_chunks::<4>();
    if !remainder.is_empty() || records.len() > 2048 {
        return Err(protocol("invalid or oversized Agent catalog"));
    }
    for record in records {
        let text = |i: usize| String::from_utf8_lossy(record[i]).into_owned();
        let kind = text(0);
        let relative = text(1);
        let path = text(2);
        let metadata = text(3);
        let skill = kind == "s";
        let name = if skill {
            let named = field(&metadata, "name");
            if named.is_empty() {
                relative
                    .strip_suffix("/SKILL.md")
                    .unwrap_or("")
                    .rsplit('/')
                    .next()
                    .unwrap_or("")
                    .into()
            } else {
                named
            }
        } else {
            relative.strip_suffix(".md").unwrap_or("").replace('/', ":")
        };
        if !valid_name(&name) {
            continue;
        }
        let token = if skill && preset == AgentPreset::Pi {
            format!("/skill:{name}")
        } else if skill && preset == AgentPreset::OpenCode {
            format!("@\"{}\"", path.replace('"', "\\\""))
        } else if skill && preset == AgentPreset::Codex {
            format!("${name}")
        } else if !skill && preset == AgentPreset::Codex {
            format!("/prompts:{name}")
        } else {
            format!("/{name}")
        };
        let entry = AgentCatalogEntry {
            name,
            token: token.clone(),
            description: field(&metadata, "description"),
            argument_hint: field(&metadata, "argument-hint"),
            source: Some(path),
        };
        if skill {
            skills.insert(token.clone(), entry.clone());
            if preset == AgentPreset::ClaudeCode {
                commands.insert(token, entry);
            }
        } else {
            commands.insert(token, entry);
        }
    }
    Ok(AgentCatalog {
        commands: commands.into_values().collect(),
        skills: skills.into_values().collect(),
    })
}

#[uniffi::export]
impl HostConnection {
    /// Uses the Agent's own OSC status first, so a shell/Node launcher does not
    /// hide its catalog. Process argv is a fallback; editable titles are ignored.
    pub async fn chat_catalog(
        &self,
        address: String,
        refresh: bool,
    ) -> Result<AgentCatalog, CoreError> {
        let terminal = self.catalog_terminal(address.clone()).await?;
        let identify = |name: &str| match name.rsplit('/').next().unwrap_or("") {
            "claude-code" | "claude" => Some(AgentPreset::ClaudeCode),
            "codex" => Some(AgentPreset::Codex),
            "opencode" => Some(AgentPreset::OpenCode),
            "pi" => Some(AgentPreset::Pi),
            _ => None,
        };
        let preset = terminal["status"]["app"]
            .as_str()
            .and_then(identify)
            .or_else(|| {
                terminal["foreground"]["argv"][0]
                    .as_str()
                    .and_then(identify)
            })
            .or_else(|| terminal["foreground"]["name"].as_str().and_then(identify))
            .or_else(|| terminal["argv"][0].as_str().and_then(identify))
            .or_else(|| {
                let shell = terminal["argv"][0]
                    .as_str()
                    .unwrap_or("")
                    .rsplit('/')
                    .next();
                (shell == Some("sh") && terminal["argv"][1].as_str() == Some("-c"))
                    .then(|| terminal["argv"][3].as_str().and_then(identify))
                    .flatten()
            });
        match preset {
            Some(preset) => {
                self.collect_catalog(address, preset, refresh, terminal)
                    .await
            }
            None => Ok(AgentCatalog::default()),
        }
    }

    /// Five-minute connection-local cache, partitioned by Agent, machine and live cwd.
    /// Refresh failure leaves the previous catalog available to callers already holding it.
    pub async fn agent_catalog(
        &self,
        address: String,
        preset: AgentPreset,
        refresh: bool,
    ) -> Result<AgentCatalog, CoreError> {
        let terminal = self.catalog_terminal(address.clone()).await?;
        self.collect_catalog(address, preset, refresh, terminal)
            .await
    }
}

impl HostConnection {
    async fn catalog_terminal(&self, address: String) -> Result<serde_json::Value, CoreError> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .call("terminal.get", json!({"terminal":address}))
                .await
        })
        .await
    }

    async fn collect_catalog(
        &self,
        address: String,
        preset: AgentPreset,
        refresh: bool,
        terminal: serde_json::Value,
    ) -> Result<AgentCatalog, CoreError> {
        let cwd = terminal["foreground"]["cwd"]
            .as_str()
            .filter(|s| !s.is_empty())
            .or_else(|| terminal["cwd"].as_str().filter(|s| !s.is_empty()))
            .map(str::to_owned);
        let on = address.rsplit_once('/').map(|(m, _)| m.to_owned());
        let key = format!("{preset:?}:{on:?}:{cwd:?}");
        if !refresh
            && let Some((at, value)) = self.catalogs.lock().await.0.get(&key)
            && at.elapsed() < Duration::from_secs(300)
        {
            return Ok(value.clone());
        }
        let agent = match preset {
            AgentPreset::ClaudeCode => "claude",
            AgentPreset::Codex => "codex",
            AgentPreset::OpenCode => "opencode",
            AgentPreset::Pi => "pi",
        };
        let out = self
            .machine_exec_on(
                vec![
                    "sh".into(),
                    "-c".into(),
                    COLLECT.into(),
                    "catalog".into(),
                    agent.into(),
                ],
                cwd,
                15,
                on,
            )
            .await?;
        if out.code != Some(0) || out.signal.is_some() || out.truncated || out.timed_out {
            return Err(protocol(
                "Agent catalog unavailable, timed out or exceeded the response budget",
            ));
        }
        let result = catalog(&out.stdout, preset)?;
        let mut cache = self.catalogs.lock().await;
        // Bound memory even when a connection browses many repositories.
        if cache.0.len() >= 32 {
            cache.0.clear();
        }
        cache.0.insert(key, (Instant::now(), result.clone()));
        Ok(result)
    }
}
