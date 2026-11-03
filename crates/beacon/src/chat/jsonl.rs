//! Tails an append-only JSONL transcript: only complete lines are read, a
//! half-written last line waits for its newline, and a file that shrinks or
//! is replaced is reported so the reader can start over.

use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::MetadataExt;
use std::path::PathBuf;

use aries_protocol::chat::ChatEvent;

use super::Interpreter;

/// Most bytes read per [`JsonlSource::poll`], so a huge transcript is
/// streamed out in pieces instead of held at once.
const CHUNK: usize = 4 << 20;

pub struct JsonlSource {
    path: PathBuf,
    interpreter: Box<dyn Interpreter>,
    /// Bytes consumed: everything before is complete lines already read.
    offset: u64,
    /// Lines ending at or before this are read only to rebuild the
    /// interpreter's state (resuming from a cursor); their events are not
    /// emitted again.
    quiet_until: u64,
    partial: Vec<u8>,
    inode: Option<(u64, u64)>,
}

/// What one [`JsonlSource::poll`] found.
pub enum Poll {
    /// The file does not exist (yet).
    Missing,
    /// New events, each with the offset just after the line that made it.
    /// `more` is set when unread bytes remain.
    Events {
        events: Vec<(ChatEvent, u64)>,
        more: bool,
    },
    /// The file shrank or was replaced: what was read no longer holds.
    Replaced,
}

impl JsonlSource {
    /// Reads `path` from the start; `resume_after` (a cursor offset) makes
    /// the lines before it silent.
    pub fn new(path: PathBuf, interpreter: Box<dyn Interpreter>, resume_after: u64) -> Self {
        Self {
            path,
            interpreter,
            offset: 0,
            quiet_until: resume_after,
            partial: Vec::new(),
            inode: None,
        }
    }

    /// Bytes of complete lines read so far.
    pub fn offset(&self) -> u64 {
        self.offset
    }

    /// The file is at least `len` bytes long (a cursor into it is valid).
    pub fn holds(&self, len: u64) -> bool {
        std::fs::metadata(&self.path).is_ok_and(|m| m.len() >= len)
    }

    pub fn poll(&mut self) -> std::io::Result<Poll> {
        let mut file = match std::fs::File::open(&self.path) {
            Ok(f) => f,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Ok(if self.inode.is_some() {
                    Poll::Replaced
                } else {
                    Poll::Missing
                });
            }
            Err(e) => return Err(e),
        };
        let meta = file.metadata()?;
        let inode = (meta.dev(), meta.ino());
        let read_to = self.offset + self.partial.len() as u64;
        if self.inode.is_some_and(|i| i != inode) || meta.len() < read_to {
            return Ok(Poll::Replaced);
        }
        self.inode = Some(inode);
        file.seek(SeekFrom::Start(read_to))?;
        let mut buf = Vec::new();
        file.take(CHUNK as u64).read_to_end(&mut buf)?;
        let more = read_to + (buf.len() as u64) < meta.len();
        // Only the new bytes can hold a newline the old ones did not.
        let fresh = self.partial.len();
        self.partial.extend_from_slice(&buf);
        let Some(last_nl) = self.partial[fresh..].iter().rposition(|b| *b == b'\n') else {
            return Ok(Poll::Events {
                events: Vec::new(),
                more,
            });
        };
        let complete = fresh + last_nl + 1;

        let mut events = Vec::new();
        let mut start = 0;
        while let Some(nl) = self.partial[start..complete].iter().position(|b| *b == b'\n') {
            let line = &self.partial[start..start + nl];
            let end = self.offset + (start + nl + 1) as u64;
            let produced = self.interpreter.line(&String::from_utf8_lossy(line));
            if end > self.quiet_until {
                events.extend(produced.into_iter().map(|e| (e, end)));
            }
            start += nl + 1;
        }
        self.partial.drain(..start);
        self.offset += start as u64;
        Ok(Poll::Events { events, more })
    }
}
