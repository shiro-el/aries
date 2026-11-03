//! Pushes a just-recorded Call to the paired phone, if the push policy
//! (`push-policy.json`, see [`crate::status_push`]) allows it. The Call is
//! already in Call history, so whatever happens here never loses it.

use aries_protocol::push::{PADDED_LEN, PushMessage};

use crate::calls::Call;
use crate::push::{self, SendError};

/// Choices are cut to this many characters only when the Call would not
/// fit otherwise (answers name a Choice by index, so this is display only).
const SHORT_CHOICE_CHARS: usize = 60;
const SHORT_TITLE_CHARS: usize = 120;

/// Sends the push for `call` when the policy allows it. Failures are
/// reported on stderr and otherwise ignored.
pub fn deliver(call: &Call) {
    if !crate::status_push::policy().allows_call(call) {
        return;
    }
    let Some(message) = message(call) else {
        return;
    };
    match push::send(&message) {
        Ok(()) | Err(SendError::NotPaired) => {}
        Err(e) => {
            eprintln!("aries-beacon call: push failed ({e}); the Call is still in Call history")
        }
    }
}

fn fits(m: &PushMessage) -> bool {
    serde_json::to_vec(m).is_ok_and(|j| j.len() <= PADDED_LEN - 2)
}

/// `text` cut to `chars` characters, ending in `…` when cut.
fn clip(text: &str, chars: usize) -> String {
    match text.char_indices().nth(chars) {
        Some((end, _)) => format!("{}…", &text[..end]),
        None => text.to_owned(),
    }
}

/// The push for a confirmed `call`, with the body (then, if needed, the
/// title and Choices) cut until it fits the envelope.
fn message(call: &Call) -> Option<PushMessage> {
    let build = |title: String, body: String, choices: Vec<String>| PushMessage::Call {
        id: call.id.clone(),
        terminal: call.terminal.clone().unwrap_or_default(),
        top_level: call.top_level,
        title,
        body,
        urgency: call.urgency,
        choices,
        at_ms: call.at_ms,
    };
    let full = build(call.title.clone(), call.body.clone(), call.choices.clone());
    if fits(&full) {
        return Some(full);
    }
    // Longest body prefix that fits, by bisection on characters.
    let (mut lo, mut hi) = (0, call.body.chars().count());
    while lo < hi {
        let mid = (lo + hi).div_ceil(2);
        let m = build(
            call.title.clone(),
            clip(&call.body, mid),
            call.choices.clone(),
        );
        if fits(&m) { lo = mid } else { hi = mid - 1 }
    }
    let body = if lo == 0 {
        String::new()
    } else {
        clip(&call.body, lo)
    };
    let m = build(call.title.clone(), body.clone(), call.choices.clone());
    if fits(&m) {
        return Some(m);
    }
    let short = build(
        clip(&call.title, SHORT_TITLE_CHARS),
        String::new(),
        call.choices
            .iter()
            .map(|c| clip(c, SHORT_CHOICE_CHARS))
            .collect(),
    );
    fits(&short).then_some(short)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aries_protocol::calls::Urgency;

    fn call(title: &str, body: &str, choices: &[&str]) -> Call {
        Call {
            id: "c1".into(),
            at_ms: 1,
            terminal: Some("t1".into()),
            top_level: true,
            title: title.into(),
            body: body.into(),
            urgency: Urgency::Check,
            choices: choices.iter().map(|c| c.to_string()).collect(),
            answer: None,
        }
    }

    fn sealable(m: &PushMessage) {
        let kp = aries_protocol::push::KeyPair::generate();
        aries_protocol::push::seal(m, &kp.public, &kp).expect("fits");
    }

    #[test]
    fn a_call_that_fits_is_sent_whole() {
        let c = call("Merge?", "Tests pass.", &["Yes", "No"]);
        match message(&c).unwrap() {
            PushMessage::Call {
                title,
                body,
                choices,
                ..
            } => {
                assert_eq!((title.as_str(), body.as_str()), ("Merge?", "Tests pass."));
                assert_eq!(choices, ["Yes", "No"]);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn the_largest_call_still_fits_with_its_choices_cut_last() {
        let wide = "가".repeat(200);
        let c = call(
            &"\u{1}".repeat(200),
            &"\u{1}".repeat(4000),
            &[&wide, &wide, &wide],
        );
        let m = message(&c).expect("some form fits");
        sealable(&m);
        match m {
            PushMessage::Call { body, choices, .. } => {
                assert_eq!(body, "");
                assert_eq!(choices.len(), 3);
                assert_eq!(choices[0].chars().count(), SHORT_CHOICE_CHARS + 1);
            }
            other => panic!("{other:?}"),
        }
    }
}
