//! Diffs and images for Chat items, with the size caps every Agent's
//! interpreter applies (`aries_protocol::chat::{IMAGE_MAX_BYTES,
//! DIFF_MAX_LINES, DIFF_MAX_LINE_CHARS}`).

use aries_protocol::chat::{
    ChatDiff, ChatImage, DIFF_MAX_LINE_CHARS, DIFF_MAX_LINES, DiffHunk, IMAGE_MAX_BYTES,
};

/// An image the transcript embeds as base64. Over the cap it keeps only its
/// description.
pub fn image(media_type: &str, base64: &str, size: Option<(u32, u32)>) -> ChatImage {
    let bytes = decoded_len(base64);
    ChatImage {
        media_type: media_type.to_owned(),
        data: (bytes <= IMAGE_MAX_BYTES).then(|| base64.to_owned()),
        bytes,
        width: size.map(|s| s.0),
        height: size.map(|s| s.1),
    }
}

/// Decoded size of standard base64 (whitespace ignored).
fn decoded_len(base64: &str) -> u64 {
    let chars = base64.bytes().filter(|b| !b.is_ascii_whitespace());
    let (mut n, mut pad) = (0u64, 0u64);
    for b in chars {
        n += 1;
        pad = if b == b'=' { pad + 1 } else { 0 };
    }
    (n * 3 / 4).saturating_sub(pad)
}

/// A diff from hunks, cut to the caps.
pub fn diff(path: &str, hunks: impl IntoIterator<Item = DiffHunk>) -> ChatDiff {
    let mut left = DIFF_MAX_LINES;
    let mut truncated = false;
    let mut out = Vec::new();
    for mut hunk in hunks {
        if left == 0 {
            truncated = true;
            break;
        }
        if hunk.lines.len() > left {
            hunk.lines.truncate(left);
            truncated = true;
        }
        left -= hunk.lines.len();
        for line in &mut hunk.lines {
            if line.chars().count() > DIFF_MAX_LINE_CHARS {
                let mut cut: String = line.chars().take(DIFF_MAX_LINE_CHARS - 1).collect();
                cut.push('…');
                *line = cut;
            }
        }
        out.push(hunk);
    }
    ChatDiff {
        path: path.to_owned(),
        hunks: out,
        truncated,
    }
}

/// A new file: every line added.
pub fn created(path: &str, content: &str) -> ChatDiff {
    let lines: Vec<String> = content.lines().map(|l| format!("+{l}")).collect();
    diff(
        path,
        [DiffHunk {
            old_start: 0,
            old_lines: 0,
            new_start: 1,
            new_lines: lines.len() as u32,
            lines,
        }],
    )
}
