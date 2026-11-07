use super::TerminalFacts;
use super::adapter::{Adapters, Feed};
use aries_protocol::chat::{ChatEvent, ChatLine};
use std::io::Write;
use std::time::{Duration, Instant};

pub struct Options {
    pub terminal: String,
    /// Keep following; without it, print what is there and exit.
    pub follow: bool,
    /// Resume after this cursor (from an earlier run).
    pub cursor: Option<String>,
    pub poll: Duration,
    pub heartbeat: Duration,
}

impl Options {
    pub fn new(terminal: impl Into<String>) -> Self {
        Self {
            terminal: terminal.into(),
            follow: false,
            cursor: None,
            poll: Duration::from_millis(250),
            heartbeat: Duration::from_secs(15),
        }
    }
}

/// How the loop asks herdr-core about the Terminal.
pub trait TerminalLookup {
    /// `Ok(None)`: no such Terminal. `Err`: herdr-core cannot be asked.
    fn get(&mut self, terminal: &str) -> Result<Option<TerminalFacts>, String>;
    fn screen(&mut self, _terminal: &str) -> Result<String, String> {
        Err("live screen is unavailable".into())
    }
}

pub fn run(
    opts: Options,
    lookup: &mut dyn TerminalLookup,
    adapters: &Adapters,
    out: &mut dyn Write,
) -> std::io::Result<()> {
    let feed = adapters.chat(&opts.terminal, opts.cursor.as_deref());
    run_feed(&opts, lookup, feed, out)
}
pub(super) fn run_feed(
    opts: &Options,
    lookup: &mut dyn TerminalLookup,
    mut feed: Feed<'_>,
    out: &mut dyn Write,
) -> std::io::Result<()> {
    let mut last_write = Instant::now();
    loop {
        let frame = feed.poll(lookup)?;
        frame.write_chat(out)?;
        out.flush()?;
        if frame.wrote() {
            last_write = Instant::now();
        }
        if frame.finished() || (frame.caught_up() && !opts.follow) {
            return Ok(());
        }
        if frame.caught_up() {
            std::thread::sleep(opts.poll);
            if last_write.elapsed() >= opts.heartbeat {
                writeln!(out, "{}", ChatLine::new(ChatEvent::Heartbeat).to_json())?;
                out.flush()?;
                last_write = Instant::now();
            }
        }
    }
}
