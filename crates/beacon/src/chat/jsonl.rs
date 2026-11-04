//! Tails an append-only JSONL transcript: only complete lines are read, a
//! half-written last line waits for its newline, and a file that shrinks or
//! is replaced is reported so the reader can start over.

use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::MetadataExt;
use std::path::PathBuf;

use aries_protocol::chat::ChatEvent;
use sha2::{Digest, Sha256};

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
    checkpoint: Sha256,
    /// An inherited rollout is immutable only through this byte boundary.
    limit: Option<u64>,
}

/// What one [`JsonlSource::poll`] found.
pub enum Poll {
    /// The file does not exist (yet).
    Missing,
    /// New events, each with the offset just after the line that made it.
    /// `more` is set when unread bytes remain.
    Events {
        events: Vec<(ChatEvent, String)>,
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
            checkpoint: Sha256::new(),
            limit: None,
        }
    }

    pub(super) fn with_limit(mut self, limit: u64) -> Self {
        self.limit = Some(limit);
        self
    }

    /// Bytes of complete lines read so far.
    pub fn offset(&self) -> u64 {
        self.offset
    }

    pub fn position(&self) -> String {
        self.position_at(self.offset)
    }

    fn position_at(&self, offset: u64) -> String {
        format!(
            "{}{offset}.{}",
            self.interpreter.cursor_tag(),
            digest(&self.checkpoint)
        )
    }

    /// A resume point binds the entire consumed prefix, so rewrites of an
    /// equal or longer file cannot silently reuse an old offset.
    pub fn holds(&self, position: &str) -> Option<u64> {
        let position = position.strip_prefix(self.interpreter.cursor_tag())?;
        let (offset, digest) = position.split_once('.')?;
        let offset = offset.parse::<u64>().ok()?;
        let file = std::fs::File::open(&self.path).ok()?;
        if file.metadata().ok()?.len() < offset {
            return None;
        }
        let mut hash = Sha256::new();
        let mut prefix = file.take(offset);
        let mut buf = [0; 65536];
        loop {
            let n = prefix.read(&mut buf).ok()?;
            if n == 0 {
                break;
            }
            hash.update(&buf[..n]);
        }
        (self::digest(&hash) == digest).then_some(offset)
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
        if self.limit.is_some_and(|limit| meta.len() < limit) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "inherited rollout was truncated",
            ));
        }
        let len = self.limit.unwrap_or(meta.len());
        let inode = (meta.dev(), meta.ino());
        let read_to = self.offset + self.partial.len() as u64;
        if self.inode.is_some_and(|i| i != inode) || len < read_to {
            return Ok(Poll::Replaced);
        }
        self.inode = Some(inode);
        file.seek(SeekFrom::Start(read_to))?;
        let mut buf = Vec::new();
        file.take((CHUNK as u64).min(len - read_to))
            .read_to_end(&mut buf)?;
        let more = read_to + (buf.len() as u64) < len;
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
        while let Some(nl) = self.partial[start..complete]
            .iter()
            .position(|b| *b == b'\n')
        {
            let line = &self.partial[start..start + nl];
            let end = self.offset + (start + nl + 1) as u64;
            let produced = self.interpreter.line(&String::from_utf8_lossy(line));
            self.checkpoint.update(&self.partial[start..start + nl + 1]);
            if end > self.quiet_until {
                let position = self.position_at(end);
                events.extend(produced.into_iter().map(|e| (e, position.clone())));
            }
            start += nl + 1;
        }
        self.partial.drain(..start);
        self.offset += start as u64;
        Ok(Poll::Events { events, more })
    }
}

fn digest(hash: &Sha256) -> String {
    hash.clone()
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}
