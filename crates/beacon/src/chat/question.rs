//! Keep recorded answers attached to their question, independent of the
//! Agent's prose wrapper and of which device submitted the answer.
use aries_protocol::chat::{Question, QuestionAnswer};

pub(super) fn answer(q: &Question, values: &[String], notes: Option<String>) -> QuestionAnswer {
    let mut choices = Vec::new();
    let mut text = Vec::new();
    for value in values {
        let mut rest = value.trim();
        while !rest.is_empty() {
            // Labels can contain commas and can share a prefix.
            let found = q
                .options
                .iter()
                .enumerate()
                .filter(|(_, option)| {
                    rest == option.label
                        || (q.multi
                            && rest
                                .strip_prefix(&option.label)
                                .is_some_and(|r| r.starts_with(", ")))
                })
                .max_by_key(|(_, option)| option.label.len());
            let Some((index, option)) = found else {
                text.push(rest.to_owned());
                break;
            };
            choices.push(index as u32);
            rest = rest[option.label.len()..].trim_start_matches(", ").trim();
        }
    }
    choices.sort_unstable();
    choices.dedup();
    QuestionAnswer {
        choices,
        text: (!text.is_empty()).then(|| text.join("\n")),
        notes,
    }
}

/// Older Claude transcripts only have the prose tool_result. Use the exact
/// prompt as the boundary; never associate answers by map iteration order.
pub(super) fn claude_text<'a>(raw: &'a str, prompt: &str, prompts: &[String]) -> Option<&'a str> {
    let start = raw.find(&format!("\"{prompt}\"=\""))? + prompt.len() + 4;
    let rest = &raw[start..];
    let next = prompts
        .iter()
        .filter_map(|p| rest.find(&format!("\", \"{p}\"=\"")))
        .min();
    let end = next
        .or_else(|| rest.rfind("\"."))
        .or_else(|| rest.strip_suffix('"').map(str::len))?;
    Some(&rest[..end])
}
