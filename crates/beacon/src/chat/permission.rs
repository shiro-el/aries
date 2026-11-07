//! Live permission menus are not transcript questions (Codex never records
//! the pending approval). Project recognized menus onto the existing answer
//! contract, with an identity covering the turn, process, prompt and scope.
use aries_protocol::chat::{
    ChatBody, ChatItem, ChatQuestion, Question, QuestionOption, ToolStatus,
};
use sha2::{Digest, Sha256};

use super::TerminalFacts;

pub const PREFIX: &str = "permission:";
pub const TOOL: &str = "permission";

pub struct Permission {
    pub item: ChatItem,
    keys: Vec<String>,
}

impl Permission {
    pub fn keys(&self, picks: &[Vec<usize>], text: &[Option<String>]) -> Option<Vec<String>> {
        if text.iter().any(Option::is_some) {
            return None;
        }
        let [pick] = picks else { return None };
        let [pick] = pick.as_slice() else { return None };
        Some(vec![self.keys.get(*pick)?.clone()])
    }
}

/// Fail closed on unknown agents, incomplete menus, or missing Host turn.
/// Only explicit permission status permits reading the screen as a menu.
pub fn detect(
    agent: &str,
    session: &str,
    facts: &TerminalFacts,
    screen: &str,
) -> Option<Permission> {
    let status = facts.status.as_ref()?;
    if status.state != "blocked" || status.kind.as_deref() != Some("permission") {
        return None;
    }
    let turn = facts.turn?;
    if !matches!(agent, "claude-code" | "codex") || screen.len() > 32_768 {
        return None;
    }
    let lines: Vec<_> = screen.lines().map(str::trim).collect();
    let heading = lines.iter().rposition(|line| match agent {
        "codex" => matches!(
            *line,
            "Would you like to run the following command?"
                | "Would you like to make the following edits?"
        ),
        _ => line.starts_with("Do you want to ") && line.ends_with('?'),
    })?;
    let max_rows = if agent == "claude-code" { 4 } else { 3 };
    let mut rows: Vec<(usize, String)> = Vec::new();
    let mut end = heading;
    let mut menu_start = None;
    for (i, line) in lines.iter().enumerate().skip(heading + 1) {
        let line = line.trim_start_matches(['❯', '›']).trim();
        if let Some((n, label)) = line.split_once(". ")
            && let Ok(n) = n.parse::<usize>()
        {
            if n != rows.len() + 1 || n > max_rows {
                return None;
            }
            menu_start.get_or_insert(i);
            rows.push((n, label.to_owned()));
            end = i;
        } else if rows.len() < max_rows
            && let Some((_, label)) = rows.last_mut()
            && !line.is_empty()
            && !line.starts_with("Esc ")
            && !line.starts_with("Press ")
        {
            label.push(' ');
            label.push_str(line);
            end = i;
        }
    }
    if !(2..=max_rows).contains(&rows.len()) {
        return None;
    }
    let first = &rows[0].1;
    let last = &rows.last()?.1;
    if !(first == "Yes" || first == "Yes, proceed (y)")
        || !(last == "No" || last == "No (esc)" || last.starts_with("No, "))
    {
        return None;
    }
    for (_, label) in &rows[1..rows.len() - 1] {
        let scope = label.to_lowercase();
        let known_scope = ["don't ask again", "allow all", "always allow"]
            .iter()
            .any(|s| scope.contains(s));
        let auto = agent == "claude-code" && scope.starts_with("yes, and switch to auto mode");
        if !scope.starts_with("yes, ") || !(known_scope || auto) {
            return None;
        }
    }
    let mut keys = Vec::new();
    for (i, (number, label)) in rows.iter().enumerate() {
        let key = if agent == "claude-code" {
            number.to_string()
        } else if i == 0 {
            if !label.ends_with("(y)") {
                return None;
            }
            "y".into()
        } else if i == rows.len() - 1 {
            if !label.ends_with("(esc)") {
                return None;
            }
            "esc".into()
        } else if label.ends_with("(p)") {
            "p".into()
        } else if label.ends_with("(a)") {
            "a".into()
        } else {
            return None;
        };
        keys.push(key);
    }
    // Include the command/diff before Claude's question as well. Ignore the
    // selection glyph: another viewer moving the highlight changes no scope.
    let text = lines[..=end]
        .iter()
        .map(|s| s.trim_start_matches(['❯', '›']).trim())
        .collect::<Vec<_>>()
        .join("\n");
    let normalized = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let identity = format!(
        "{agent}:{session}:{turn}:{:?}:{:?}:{normalized}",
        facts.pid, status.msg
    );
    let digest: String = Sha256::digest(identity.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    let id = format!("{PREFIX}{digest}");
    let options = rows
        .iter()
        .enumerate()
        .map(|(i, (_, description))| QuestionOption {
            label: if i == 0 {
                "Allow"
            } else if i == rows.len() - 1 {
                "Deny"
            } else if rows[i].1.to_lowercase().contains("switch to auto mode") {
                "Switch to auto mode"
            } else {
                "Allow always"
            }
            .into(),
            description: Some(description.clone()),
        })
        .collect();
    Some(Permission {
        item: ChatItem {
            id,
            ts: None,
            body: ChatBody::Tool {
                name: TOOL.into(),
                title: lines[heading].into(),
                input: serde_json::Value::Null,
                status: ToolStatus::Running,
                output: None,
                details: None,
                diff: None,
                images: vec![],
                question: Some(ChatQuestion {
                    questions: vec![Question {
                        text: lines[..menu_start?].join("\n").trim().to_owned(),
                        header: None,
                        multi: false,
                        options,
                        answer: None,
                    }],
                }),
            },
        },
        keys,
    })
}
