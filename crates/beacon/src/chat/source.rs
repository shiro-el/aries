//! Where an Agent's Chat events come from. JSONL transcripts share
//! [`JsonlSource`]; an Agent with another kind of transcript (OpenCode's
//! SQLite database) brings its own [`Source`] through [`Agent::open`].
//!
//! [`Agent::open`]: super::Agent::open

use aries_protocol::chat::ChatEvent;

use super::jsonl::{JsonlSource, Poll};

/// What one [`Source::poll`] found.
pub enum Batch {
    /// The transcript does not exist (yet).
    Missing,
    /// New events. An event with a position is a safe resume point: a
    /// cursor holding it repeats nothing before it and skips nothing after
    /// it. `more` is set when unread data remains.
    Events {
        events: Vec<(ChatEvent, Option<String>)>,
        more: bool,
    },
    /// What was read no longer holds (the transcript was replaced): the
    /// reader starts over with a fresh source.
    Replaced,
}

/// One transcript being read.
pub trait Source {
    fn poll(&mut self) -> std::io::Result<Batch>;
    /// The resume point after everything read so far. Never contains `:`.
    fn position(&self) -> String;
}

/// A source opened by [`Agent::open`](super::Agent::open). `resumed`: the
/// requested resume position holds, so events before it are not repeated
/// (no `reset` needed).
pub struct Opened {
    pub source: Box<dyn Source>,
    pub resumed: bool,
}

impl Source for JsonlSource {
    fn poll(&mut self) -> std::io::Result<Batch> {
        Ok(match JsonlSource::poll(self)? {
            Poll::Missing => Batch::Missing,
            Poll::Replaced => Batch::Replaced,
            Poll::Events { events, more } => {
                let ends: Vec<String> = events.iter().map(|(_, end)| end.clone()).collect();
                let events = events
                    .into_iter()
                    .enumerate()
                    // Only the last event of a transcript line is a resume
                    // point, so resuming never skips the rest of the line.
                    .map(|(i, (event, end))| {
                        (event, (ends.get(i + 1) != Some(&end)).then_some(end))
                    })
                    .collect();
                Batch::Events { events, more }
            }
        })
    }

    fn position(&self) -> String {
        JsonlSource::position(self)
    }
}
