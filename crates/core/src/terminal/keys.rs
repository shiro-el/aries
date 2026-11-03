//! Keys to the bytes a program reads. herdr-core does not encode keys for
//! attached viewers, so the core does it, xterm style, following the
//! program's cursor-key mode.

/// A key that is not plain text.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TerminalKey {
    /// One character typed with a modifier (Ctrl-C is `Char { text: "c" }`
    /// with `ctrl`). Only the first character of `text` counts.
    Char {
        text: String,
    },
    Enter,
    Tab,
    Backspace,
    Escape,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    /// F1 to F12.
    F {
        n: u8,
    },
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, uniffi::Record)]
pub struct KeyMods {
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
}

impl KeyMods {
    fn any(self) -> bool {
        self.ctrl || self.alt || self.shift
    }

    /// The xterm modifier parameter: 1 + shift + 2·alt + 4·ctrl.
    fn param(self) -> u8 {
        1 + self.shift as u8 + 2 * self.alt as u8 + 4 * self.ctrl as u8
    }
}

/// The bytes for `key`; `app_cursor` is the program's DECCKM mode.
pub(crate) fn encode(key: &TerminalKey, mods: KeyMods, app_cursor: bool) -> Vec<u8> {
    use TerminalKey as K;
    let alt = |mut b: Vec<u8>| {
        if mods.alt {
            b.insert(0, 0x1b);
        }
        b
    };
    // `CSI 1;m X` with modifiers, else `CSI X` (or `SS3 X` in app mode).
    let cursor = |c: u8| {
        if mods.any() {
            format!("\x1b[1;{}{}", mods.param(), c as char).into_bytes()
        } else if app_cursor {
            vec![0x1b, b'O', c]
        } else {
            vec![0x1b, b'[', c]
        }
    };
    let tilde = |n: u8| {
        if mods.any() {
            format!("\x1b[{n};{}~", mods.param()).into_bytes()
        } else {
            format!("\x1b[{n}~").into_bytes()
        }
    };
    match key {
        K::Char { text } => match text.chars().next() {
            Some(c) => alt(char_bytes(c, mods)),
            None => Vec::new(),
        },
        K::Enter => alt(vec![b'\r']),
        K::Tab if mods.shift => b"\x1b[Z".to_vec(),
        K::Tab => alt(vec![b'\t']),
        K::Backspace => alt(vec![if mods.ctrl { 0x08 } else { 0x7f }]),
        K::Escape => alt(vec![0x1b]),
        K::Up => cursor(b'A'),
        K::Down => cursor(b'B'),
        K::Right => cursor(b'C'),
        K::Left => cursor(b'D'),
        K::Home => cursor(b'H'),
        K::End => cursor(b'F'),
        K::Insert => tilde(2),
        K::Delete => tilde(3),
        K::PageUp => tilde(5),
        K::PageDown => tilde(6),
        K::F { n } => match n {
            1..=4 => {
                let c = b'P' + (n - 1);
                if mods.any() {
                    format!("\x1b[1;{}{}", mods.param(), c as char).into_bytes()
                } else {
                    vec![0x1b, b'O', c]
                }
            }
            5..=12 => tilde([15, 17, 18, 19, 20, 21, 23, 24][(*n - 5) as usize]),
            _ => Vec::new(),
        },
    }
}

/// A character with Ctrl (and Shift) applied; Alt is added by the caller.
fn char_bytes(c: char, mods: KeyMods) -> Vec<u8> {
    let c = if mods.shift {
        c.to_ascii_uppercase()
    } else {
        c
    };
    if mods.ctrl {
        let control = match c {
            'a'..='z' => Some(c as u8 - b'a' + 1),
            'A'..='Z' => Some(c as u8 - b'A' + 1),
            '@' | ' ' | '2' => Some(0),
            '[' | '3' => Some(0x1b),
            '\\' | '4' => Some(0x1c),
            ']' | '5' => Some(0x1d),
            '^' | '6' => Some(0x1e),
            '_' | '-' | '7' => Some(0x1f),
            '?' | '8' => Some(0x7f),
            _ => None,
        };
        if let Some(b) = control {
            return vec![b];
        }
    }
    c.to_string().into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    const CTRL: KeyMods = KeyMods {
        ctrl: true,
        alt: false,
        shift: false,
    };

    fn ch(c: &str) -> TerminalKey {
        TerminalKey::Char { text: c.into() }
    }

    // Expected bytes are xterm's (ctlseqs "PC-Style Function Keys").
    #[test]
    fn encodes_keys_like_xterm() {
        let none = KeyMods::default();
        assert_eq!(encode(&ch("c"), CTRL, false), b"\x03");
        assert_eq!(encode(&ch("["), CTRL, false), b"\x1b");
        assert_eq!(encode(&TerminalKey::Up, none, false), b"\x1b[A");
        assert_eq!(encode(&TerminalKey::Up, none, true), b"\x1bOA");
        assert_eq!(encode(&TerminalKey::Left, CTRL, true), b"\x1b[1;5D");
        assert_eq!(
            encode(
                &TerminalKey::Tab,
                KeyMods {
                    shift: true,
                    ..none
                },
                false
            ),
            b"\x1b[Z"
        );
        assert_eq!(encode(&TerminalKey::PageDown, none, false), b"\x1b[6~");
        assert_eq!(encode(&TerminalKey::F { n: 5 }, none, false), b"\x1b[15~");
        assert_eq!(encode(&TerminalKey::F { n: 1 }, none, false), b"\x1bOP");
        assert_eq!(
            encode(&ch("x"), KeyMods { alt: true, ..none }, false),
            b"\x1bx"
        );
    }
}
