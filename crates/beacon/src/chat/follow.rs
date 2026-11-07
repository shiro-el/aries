//! The loop behind `aries-beacon chat`: pair the Terminal with a transcript,
//! read it, follow rotations, and print Chat lines with cursors.

use std::io::Write;
use std::time::{Duration, Instant};

use aries_protocol::chat::{ChatEvent, ChatLine, ChatState};

use super::source::{Batch, Source};
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
    fn screen(&mut self, _terminal: &str) -> Result<String, String> {
        Err("live screen is unavailable".into())
    }
}

/// `<agent>:<session>:<position>`; the position is the Agent's
/// [`Source::position`] (a byte offset for JSONL transcripts).
struct Cursor {
    agent: String,
    session: String,
    position: String,
}

impl Cursor {
    fn parse(s: &str) -> Option<Self> {
        let (agent, rest) = s.split_once(':')?;
        let (session, position) = rest.rsplit_once(':')?;
        Some(Self {
            agent: agent.to_owned(),
            session: session.to_owned(),
            position: position.to_owned(),
        })
    }

    fn format(agent: &str, session: &str, position: &str) -> String {
        format!("{agent}:{session}:{position}")
    }
}

struct Current {
    agent: &'static str,
    located: Located,
    source: Box<dyn Source>,
}

impl Current {
    fn cursor(&self) -> String {
        Cursor::format(self.agent, &self.located.session, &self.source.position())
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
    let mut permission: Option<aries_protocol::chat::ChatItem> = None;
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
                let after = resume
                    .take()
                    .filter(|c| c.agent == agent.name() && c.session == located.session)
                    .map(|c| c.position);
                let opened = crate::claude_hook::open(
                    agent.as_ref(),
                    located,
                    after.as_deref(),
                    &crate::state_dir(),
                    &opts.terminal,
                );
                let source = opened.source;
                if !opened.resumed {
                    emit(
                        out,
                        ChatLine::new(ChatEvent::Reset {
                            agent: agent.name().into(),
                            session: located.session.clone(),
                        }),
                    )?;
                    last_state = None;
                    permission = None;
                }
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
                Batch::Missing => now_state = ChatState::Waiting,
                Batch::Replaced => {
                    emit(
                        out,
                        ChatLine::new(ChatEvent::Reset {
                            agent: cur.agent.into(),
                            session: cur.located.session.clone(),
                        }),
                    )?;
                    wrote = true;
                    last_state = None;
                    permission = None;
                    let agent = agents.iter().find(|a| a.name() == cur.agent);
                    if let Some(agent) = agent {
                        cur.source = crate::claude_hook::open(
                            agent.as_ref(),
                            &cur.located,
                            None,
                            &crate::state_dir(),
                            &opts.terminal,
                        )
                        .source;
                    }
                    caught_up = false;
                    now_state = ChatState::Live;
                }
                Batch::Events { events, more } => {
                    for (event, position) in events {
                        let mut line = ChatLine::new(event);
                        if let Some(p) = position {
                            line.cursor = Some(Cursor::format(cur.agent, &cur.located.session, &p));
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

        if caught_up {
            let next =
                found.as_ref().and_then(|(agent, located)| {
                    if facts.status.as_ref().is_none_or(|s| {
                        s.state != "blocked" || s.kind.as_deref() != Some("permission")
                    }) {
                        return None;
                    }
                    super::permission::detect(
                        agent.name(),
                        &located.session,
                        &facts,
                        &lookup.screen(&opts.terminal).ok()?,
                    )
                    .map(|p| p.item)
                });
            // Remove transient approvals when answered, replaced, or no longer
            // recognized; never leave them actionable in cached history.
            if permission.as_ref().map(|p| &p.id) != next.as_ref().map(|p| &p.id)
                && let Some(old) = permission.take()
            {
                emit(out, ChatLine::new(ChatEvent::Remove { id: old.id }))?;
                wrote = true;
            }
            if permission != next {
                if let Some(item) = &next {
                    emit(out, ChatLine::new(ChatEvent::Item { item: item.clone() }))?;
                    wrote = true;
                }
                permission = next;
            }
            if permission.is_some() {
                now_state = ChatState::Live;
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
