//! The loop behind `aries-beacon chat`: pair the Terminal with a transcript,
//! read it, follow rotations, and print Chat lines with cursors.

use std::io::Write;
use std::time::{Duration, Instant};

use aries_protocol::chat::{ChatEvent, ChatLine, ChatState};

use super::jsonl::{JsonlSource, Poll};
use super::{Agent, Located, TerminalFacts};

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
}

/// `<agent>:<session>:<offset>`.
struct Cursor {
    agent: String,
    session: String,
    offset: u64,
}

impl Cursor {
    fn parse(s: &str) -> Option<Self> {
        let (agent, rest) = s.split_once(':')?;
        let (session, offset) = rest.rsplit_once(':')?;
        Some(Self {
            agent: agent.to_owned(),
            session: session.to_owned(),
            offset: offset.parse().ok()?,
        })
    }

    fn format(agent: &str, session: &str, offset: u64) -> String {
        format!("{agent}:{session}:{offset}")
    }
}

struct Current {
    agent: &'static str,
    located: Located,
    source: JsonlSource,
}

impl Current {
    fn cursor(&self) -> String {
        Cursor::format(self.agent, &self.located.session, self.source.offset())
    }
}

/// Runs until the Terminal is gone, herdr-core is unreachable, stdout
/// closes or (without `follow`) the transcript has been read.
pub fn run(
    opts: Options,
    lookup: &mut dyn TerminalLookup,
    agents: &[Box<dyn Agent>],
    out: &mut dyn Write,
) -> std::io::Result<()> {
    let mut resume = opts.cursor.as_deref().and_then(Cursor::parse);
    let mut current: Option<Current> = None;
    let mut last_state: Option<ChatState> = None;
    let mut last_write = Instant::now();
    let emit = |out: &mut dyn Write, line: ChatLine| -> std::io::Result<()> {
        writeln!(out, "{}", line.to_json())
    };

    loop {
        let facts = match lookup.get(&opts.terminal) {
            Ok(Some(f)) => f,
            Ok(None) => {
                emit(out, state(ChatState::NoTerminal))?;
                return out.flush();
            }
            Err(e) => {
                eprintln!("aries-beacon: {e}");
                emit(out, state(ChatState::NoCore))?;
                return out.flush();
            }
        };
        let found = agents.iter().find_map(|a| a.locate(&facts).map(|l| (a, l)));

        if let Some((agent, located)) = &found {
            let same = current
                .as_ref()
                .is_some_and(|c| c.agent == agent.name() && c.located == *located);
            if !same {
                let mut after = 0;
                let resumes = resume.take().is_some_and(|c| {
                    let fits = c.agent == agent.name() && c.session == located.session;
                    after = c.offset;
                    fits
                });
                let source = JsonlSource::new(located.path.clone(), agent.interpreter(), 0);
                let source = if resumes && source.holds(after) {
                    JsonlSource::new(located.path.clone(), agent.interpreter(), after)
                } else {
                    emit(
                        out,
                        ChatLine::new(ChatEvent::Reset {
                            agent: agent.name().into(),
                            session: located.session.clone(),
                        }),
                    )?;
                    last_state = None;
                    source
                };
                current = Some(Current {
                    agent: agent.name(),
                    located: located.clone(),
                    source,
                });
            }
        } else if current.is_none() {
            resume = None;
        }

        let mut wrote = false;
        let mut caught_up = true;
        let mut now_state = ChatState::NoAgent;
        if let Some(cur) = current.as_mut() {
            match cur.source.poll()? {
                Poll::Missing => now_state = ChatState::Waiting,
                Poll::Replaced => {
                    emit(
                        out,
                        ChatLine::new(ChatEvent::Reset {
                            agent: cur.agent.into(),
                            session: cur.located.session.clone(),
                        }),
                    )?;
                    wrote = true;
                    last_state = None;
                    let agent = agents.iter().find(|a| a.name() == cur.agent);
                    if let Some(agent) = agent {
                        cur.source =
                            JsonlSource::new(cur.located.path.clone(), agent.interpreter(), 0);
                    }
                    caught_up = false;
                    now_state = ChatState::Live;
                }
                Poll::Events { events, more } => {
                    let ends: Vec<u64> = events.iter().map(|(_, end)| *end).collect();
                    for (i, (event, end)) in events.into_iter().enumerate() {
                        let mut line = ChatLine::new(event);
                        // Only the last event of a transcript line carries
                        // the cursor, so resuming never skips the rest of it.
                        if ends.get(i + 1) != Some(&end) {
                            line.cursor =
                                Some(Cursor::format(cur.agent, &cur.located.session, end));
                        }
                        emit(out, line)?;
                        wrote = true;
                    }
                    caught_up = !more;
                    now_state = ChatState::Live;
                }
            }
            if found.is_none() {
                now_state = ChatState::Ended;
            }
        }

        if caught_up && last_state != Some(now_state) {
            let mut line = state(now_state);
            if let Some(cur) = &current {
                line.cursor = Some(cur.cursor());
            }
            emit(out, line)?;
            wrote = true;
            last_state = Some(now_state);
        }
        out.flush()?;
        if wrote {
            last_write = Instant::now();
        }

        if caught_up {
            if !opts.follow {
                return Ok(());
            }
            std::thread::sleep(opts.poll);
            if last_write.elapsed() >= opts.heartbeat {
                emit(out, ChatLine::new(ChatEvent::Heartbeat))?;
                out.flush()?;
                last_write = Instant::now();
            }
        }
    }
}

fn state(state: ChatState) -> ChatLine {
    ChatLine::new(ChatEvent::State { state })
}
