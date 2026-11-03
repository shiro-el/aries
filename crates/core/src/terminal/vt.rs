//! The VT state of one attached Terminal: `alacritty_terminal` fed with the
//! attach stream's raw bytes, read back as changed rows of styled cell runs.

use std::sync::{Arc, Mutex};
use std::time::Instant;

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::cell::{Cell, Flags};
use alacritty_terminal::term::{Config, Term, TermDamage, TermMode};
use alacritty_terminal::vte::ansi::{Color, NamedColor, Processor, Rgb};

use super::{CellRun, CursorPos, ScreenFrame, ScreenLine};

/// Scrollback kept in the VT when the attach asked for less. The frames
/// carry only the viewport; [`Vt::scroll_to`] moves it into the scrollback.
pub const SCROLLBACK: usize = 1000;
/// Upper bound on the scrollback a view keeps, whatever the attach asked.
pub const MAX_SCROLLBACK: usize = 10_000;

struct Size {
    cols: usize,
    rows: usize,
}

impl Dimensions for Size {
    fn total_lines(&self) -> usize {
        self.rows
    }
    fn screen_lines(&self) -> usize {
        self.rows
    }
    fn columns(&self) -> usize {
        self.cols
    }
}

/// Keeps the window title; every other VT event is for a real tty (query
/// replies, clipboard, bell), which an observer must not answer: the Host's
/// herdr-core already did.
#[derive(Clone, Default)]
struct Listener {
    title: Arc<Mutex<Option<String>>>,
}

impl EventListener for Listener {
    fn send_event(&self, event: Event) {
        match event {
            Event::Title(t) => *self.title.lock().expect("title lock") = Some(t),
            Event::ResetTitle => *self.title.lock().expect("title lock") = None,
            _ => {}
        }
    }
}

pub(crate) struct Vt {
    term: Term<Listener>,
    parser: Processor,
    listener: Listener,
    /// The next frame must carry every row (new grid size, first paint).
    full: bool,
}

impl Vt {
    /// `scrollback`: lines of history to keep (at least [`SCROLLBACK`]).
    pub fn new(cols: u16, rows: u16, scrollback: usize) -> Self {
        let listener = Listener::default();
        let config = Config {
            scrolling_history: scrollback.clamp(SCROLLBACK, MAX_SCROLLBACK),
            ..Config::default()
        };
        let term = Term::new(config, &size(cols, rows), listener.clone());
        Self {
            term,
            parser: Processor::new(),
            listener,
            full: true,
        }
    }

    pub fn feed(&mut self, bytes: &[u8]) {
        self.parser.advance(&mut self.term, bytes);
    }

    /// When a synchronized update (`CSI ?2026h`) is buffering output, the
    /// time it must be shown even if the program never ends it.
    pub fn sync_deadline(&self) -> Option<Instant> {
        self.parser.sync_timeout().sync_timeout()
    }

    /// Shows a synchronized update whose deadline passed.
    pub fn flush_sync(&mut self) {
        self.parser.stop_sync(&mut self.term);
    }

    pub fn resize(&mut self, cols: u16, rows: u16) {
        if cols as usize == self.term.columns() && rows as usize == self.term.screen_lines() {
            return;
        }
        self.term.resize(size(cols, rows));
        self.full = true;
    }

    /// Shows the screen `lines` lines back into the scrollback (0 = live);
    /// stops at the oldest line. Returns where it stopped. While scrolled
    /// back, new output keeps the view on the same lines.
    pub fn scroll_to(&mut self, lines: u32) -> u32 {
        let history = self.term.grid().history_size();
        let target = (lines as usize).min(history);
        let now = self.term.grid().display_offset();
        if target != now {
            self.term
                .scroll_display(Scroll::Delta(target as i32 - now as i32));
            self.full = true;
        }
        target as u32
    }

    pub fn cols(&self) -> u16 {
        self.term.columns() as u16
    }

    /// The rows changed since the last frame (all rows when `full`).
    pub fn take_frame(&mut self) -> ScreenFrame {
        let rows = self.term.screen_lines();
        let damaged: Vec<usize> = match self.term.damage() {
            TermDamage::Full => (0..rows).collect(),
            TermDamage::Partial(lines) => lines.map(|d| d.line).collect(),
        };
        let full = self.full || damaged.len() == rows;
        self.full = false;
        self.term.reset_damage();
        let lines = if full {
            (0..rows).collect()
        } else {
            let mut d = damaged;
            d.sort_unstable();
            d.dedup();
            d
        };
        let offset = self.term.grid().display_offset();
        let cursor = self
            .term
            .mode()
            .contains(TermMode::SHOW_CURSOR)
            .then(|| self.term.grid().cursor.point)
            // Scrolled back far enough, the cursor is below the viewport.
            .filter(|p| p.line.0.max(0) as usize + offset < rows)
            .map(|p| CursorPos {
                row: (p.line.0.max(0) as usize + offset) as u16,
                column: p.column.0 as u16,
            });
        ScreenFrame {
            cols: self.cols(),
            rows: rows as u16,
            full,
            lines: lines.into_iter().map(|r| self.line(r)).collect(),
            cursor,
            title: self.listener.title.lock().expect("title lock").clone(),
            scrolled: offset as u32,
            history: self.term.grid().history_size() as u32,
        }
    }

    /// The visible rows as plain text, trailing blanks trimmed.
    #[cfg(test)]
    pub fn text(&self) -> Vec<String> {
        (0..self.term.screen_lines())
            .map(|r| {
                let mut s = String::new();
                for run in self.line(r).runs {
                    while s.chars().count() < run.column as usize {
                        s.push(' ');
                    }
                    s.push_str(&run.text);
                }
                s.trim_end().to_owned()
            })
            .collect()
    }

    /// Viewport row `row` (scrolled back by the display offset).
    fn line(&self, row: usize) -> ScreenLine {
        let grid = self.term.grid();
        let line = &grid[Line(row as i32 - grid.display_offset() as i32)];
        let mut runs: Vec<CellRun> = Vec::new();
        for col in 0..self.term.columns() {
            let cell = &line[Column(col)];
            if cell.flags.contains(Flags::WIDE_CHAR_SPACER) {
                continue;
            }
            let wide = cell.flags.contains(Flags::WIDE_CHAR);
            let style = self.style(cell, wide);
            let mut text = String::new();
            if cell
                .flags
                .intersects(Flags::HIDDEN | Flags::LEADING_WIDE_CHAR_SPACER)
            {
                text.push(' ');
            } else {
                text.push(cell.c);
                if let Some(zw) = cell.zerowidth() {
                    text.extend(zw);
                }
            }
            let width = if wide { 2 } else { 1 };
            match runs.last_mut() {
                Some(last)
                    if same_style(last, &style) && last.column + last.width == col as u16 =>
                {
                    last.text.push_str(&text);
                    last.width += width;
                }
                _ => runs.push(CellRun {
                    column: col as u16,
                    width,
                    text,
                    ..style
                }),
            }
        }
        // Trailing blanks that draw nothing are left out; that keeps the batch
        // small (most of a typical screen is empty).
        for r in runs.iter_mut().filter(|r| !shows_blanks(r)) {
            let kept = r.text.trim_end_matches(' ').len();
            r.width -= (r.text.len() - kept) as u16;
            r.text.truncate(kept);
        }
        runs.retain(|r| r.width > 0);
        ScreenLine {
            row: row as u16,
            runs,
        }
    }

    fn style(&self, cell: &Cell, wide: bool) -> CellRun {
        let f = cell.flags;
        CellRun {
            column: 0,
            width: 0,
            text: String::new(),
            wide,
            fg: self.color(cell.fg),
            bg: self.color(cell.bg),
            bold: f.contains(Flags::BOLD),
            dim: f.contains(Flags::DIM),
            italic: f.contains(Flags::ITALIC),
            underline: f.intersects(Flags::ALL_UNDERLINES),
            strikeout: f.contains(Flags::STRIKEOUT),
            inverse: f.contains(Flags::INVERSE),
            link: cell.hyperlink().map(|h| h.uri().to_owned()),
        }
    }

    /// `0xRRGGBB`, or `None` for the theme's default foreground/background.
    fn color(&self, c: Color) -> Option<u32> {
        let index = match c {
            Color::Spec(rgb) => return Some(pack(rgb)),
            Color::Indexed(i) => i as usize,
            Color::Named(n) => named_index(n)?,
        };
        Some(
            self.term.colors()[index]
                .map(pack)
                .unwrap_or_else(|| default_palette(index)),
        )
    }
}

fn size(cols: u16, rows: u16) -> Size {
    Size {
        cols: cols.max(1) as usize,
        rows: rows.max(1) as usize,
    }
}

fn same_style(a: &CellRun, b: &CellRun) -> bool {
    a.wide == b.wide
        && a.fg == b.fg
        && a.bg == b.bg
        && a.bold == b.bold
        && a.dim == b.dim
        && a.italic == b.italic
        && a.underline == b.underline
        && a.strikeout == b.strikeout
        && a.inverse == b.inverse
        && a.link == b.link
}

/// Whether blank cells of this style still draw something (a background,
/// a line).
fn shows_blanks(r: &CellRun) -> bool {
    r.bg.is_some() || r.inverse || r.underline || r.strikeout
}

/// Palette slot of a named colour; `None` for the default fg/bg/cursor.
fn named_index(n: NamedColor) -> Option<usize> {
    use NamedColor::*;
    Some(match n {
        Black | DimBlack => 0,
        Red | DimRed => 1,
        Green | DimGreen => 2,
        Yellow | DimYellow => 3,
        Blue | DimBlue => 4,
        Magenta | DimMagenta => 5,
        Cyan | DimCyan => 6,
        White | DimWhite => 7,
        BrightBlack => 8,
        BrightRed => 9,
        BrightGreen => 10,
        BrightYellow => 11,
        BrightBlue => 12,
        BrightMagenta => 13,
        BrightCyan => 14,
        BrightWhite => 15,
        Foreground | Background | Cursor | BrightForeground | DimForeground => return None,
    })
}

fn pack(c: Rgb) -> u32 {
    ((c.r as u32) << 16) | ((c.g as u32) << 8) | c.b as u32
}

/// xterm's default 256-colour palette.
fn default_palette(i: usize) -> u32 {
    const BASE: [u32; 16] = [
        0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5, 0x7f7f7f,
        0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
    ];
    match i {
        0..16 => BASE[i],
        16..232 => {
            let i = i - 16;
            let level = |v: usize| if v == 0 { 0 } else { (55 + v * 40) as u32 };
            (level(i / 36) << 16) | (level(i / 6 % 6) << 8) | level(i % 6)
        }
        232..256 => {
            let v = (8 + (i - 232) * 10) as u32;
            (v << 16) | (v << 8) | v
        }
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colours_runs_and_sends_only_the_rows_that_changed() {
        let mut vt = Vt::new(20, 4, 0);
        vt.feed(b"plain \x1b[31mred\x1b[0m\r\nnext");
        let first = vt.take_frame();
        assert!(first.full, "the first frame is a whole screen");
        assert_eq!(first.lines.len(), 4);
        let row0 = &first.lines[0].runs;
        assert_eq!(row0[0].text, "plain");
        assert_eq!(row0[0].fg, None);
        assert_eq!(row0[1].text, "red");
        assert_eq!(row0[1].column, 6);
        assert_eq!(row0[1].fg, Some(0xcd0000));

        vt.feed(b"\x1b[3;1Hthird");
        let next = vt.take_frame();
        assert!(!next.full);
        let rows: Vec<u16> = next.lines.iter().map(|l| l.row).collect();
        // Row 1 lost the cursor, row 2 got the text and the cursor.
        assert_eq!(rows, vec![1, 2]);
        assert_eq!(vt.text()[2], "third");
    }

    #[test]
    fn a_wide_character_takes_two_columns() {
        let mut vt = Vt::new(10, 1, 0);
        vt.feed("a한b".as_bytes());
        let runs = vt.take_frame().lines.remove(0).runs;
        let parts: Vec<(&str, u16, u16, bool)> = runs
            .iter()
            .map(|r| (r.text.as_str(), r.column, r.width, r.wide))
            .collect();
        assert_eq!(
            parts,
            vec![("a", 0, 1, false), ("한", 1, 2, true), ("b", 3, 1, false)]
        );
    }

    #[test]
    fn a_resize_sends_the_whole_new_grid() {
        let mut vt = Vt::new(10, 2, 0);
        vt.take_frame();
        vt.resize(30, 5);
        let f = vt.take_frame();
        assert_eq!((f.cols, f.rows, f.full, f.lines.len()), (30, 5, true, 5));
    }

    #[test]
    fn an_unfinished_synchronized_update_shows_after_its_deadline() {
        let mut vt = Vt::new(10, 1, 0);
        vt.feed(b"\x1b[?2026hhidden");
        assert_eq!(vt.text()[0], "");
        assert!(vt.sync_deadline().is_some());
        vt.flush_sync();
        assert_eq!(vt.text()[0], "hidden");
    }

    #[test]
    fn the_title_follows_osc_2() {
        let mut vt = Vt::new(10, 1, 0);
        vt.feed(b"\x1b]2;build\x07");
        assert_eq!(vt.take_frame().title.as_deref(), Some("build"));
    }

    /// docs/research/terminal-latency.md: `cargo test --release -p aries-core
    /// --lib vt_cost -- --ignored --nocapture`.
    #[test]
    #[ignore = "measurement, not a check"]
    fn vt_cost() {
        // Coloured compiler-like output, 200 columns.
        let mut chunk = Vec::new();
        for i in 0..2000 {
            chunk.extend_from_slice(
                format!(
                    "\x1b[1;32m   Compiling\x1b[0m crate-{i} v0.1.{i} (\x1b[33m/home/user/src/crate-{i}\x1b[0m) \x1b[2m{}\x1b[0m\r\n",
                    "x".repeat(i % 120)
                )
                .as_bytes(),
            );
        }
        let mut vt = Vt::new(200, 50, 0);
        let rounds = 50;
        let t = std::time::Instant::now();
        for _ in 0..rounds {
            vt.feed(&chunk);
        }
        let feed = t.elapsed();
        let mb = (chunk.len() * rounds) as f64 / 1e6;
        let t = std::time::Instant::now();
        let n = 1000;
        let mut runs = 0;
        let mut text = 0;
        for _ in 0..n {
            vt.full = true;
            let f = vt.take_frame();
            runs = f.lines.iter().map(|l| l.runs.len()).sum::<usize>();
            text = f
                .lines
                .iter()
                .flat_map(|l| &l.runs)
                .map(|r| r.text.len())
                .sum::<usize>();
        }
        let frame = t.elapsed() / n;
        eprintln!(
            "feed: {mb:.1} MB in {feed:?} = {:.0} MB/s; full 200x50 frame: {frame:?}, {runs} runs, {text} text bytes",
            mb / feed.as_secs_f64()
        );
    }
}
