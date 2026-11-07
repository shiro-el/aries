use super::source::{Batch, Source};
use super::{Agent, Located, TerminalLookup};
use aries_protocol::chat::{ChatEvent, ChatItem, ChatLine, ChatState};
use std::io::Write;
use std::path::Path;

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

struct Current<'a> {
    agent: &'a dyn Agent,
    located: Located,
    source: Box<dyn Source>,
}

impl Current<'_> {
    fn cursor(&self) -> String {
        Cursor::format(
            self.agent.name(),
            &self.located.session,
            &self.source.position(),
        )
    }
}

pub struct Feed<'a> {
    state_dir: &'a Path,
    agents: &'a [Box<dyn Agent>],
    terminal: String,
    resume: Option<Cursor>,
    current: Option<Current<'a>>,
    last_state: Option<ChatState>,
    permission: Option<ChatItem>,
}
impl<'a> Feed<'a> {
    pub(super) fn new(
        state_dir: &'a Path,
        agents: &'a [Box<dyn Agent>],
        terminal: &str,
        cursor: Option<&str>,
    ) -> Self {
        Self {
            state_dir,
            agents,
            terminal: terminal.to_owned(),
            resume: cursor.and_then(Cursor::parse),
            current: None,
            last_state: None,
            permission: None,
        }
    }
    pub fn poll(&mut self, lookup: &mut dyn TerminalLookup) -> std::io::Result<ChatFrame> {
        let Self {
            state_dir,
            agents,
            terminal,
            resume,
            current,
            last_state,
            permission,
        } = self;
        let mut lines = Vec::new();
        let emit = |lines: &mut Vec<ChatLine>, line: ChatLine| -> std::io::Result<()> {
            lines.push(line);
            Ok(())
        };
        let facts = match lookup.get(&*terminal) {
            Ok(Some(f)) => f,
            Ok(None) => {
                emit(&mut lines, state(ChatState::NoTerminal))?;
                return Ok(ChatFrame {
                    lines,
                    caught_up: true,
                    finished: true,
                });
            }
            Err(e) => {
                eprintln!("aries-beacon: {e}");
                emit(&mut lines, state(ChatState::NoCore))?;
                return Ok(ChatFrame {
                    lines,
                    caught_up: true,
                    finished: true,
                });
            }
        };
        let found = agents.iter().find_map(|a| a.locate(&facts).map(|l| (a, l)));

        if let Some((agent, located)) = &found {
            let same = current
                .as_ref()
                .is_some_and(|c| c.agent.name() == agent.name() && c.located == *located);
            if !same {
                let after = resume
                    .take()
                    .filter(|c| c.agent == agent.name() && c.session == located.session)
                    .map(|c| c.position);
                let opened = agent.open_chat(located, after.as_deref(), state_dir, &*terminal);
                let source = opened.source;
                if !opened.resumed {
                    emit(
                        &mut lines,
                        ChatLine::new(ChatEvent::Reset {
                            agent: agent.name().into(),
                            session: located.session.clone(),
                        }),
                    )?;
                    *last_state = None;
                    *permission = None;
                }
                *current = Some(Current {
                    agent: (*agent).as_ref(),
                    located: located.clone(),
                    source,
                });
            }
        } else if current.is_none() {
            *resume = None;
        }

        let mut caught_up = true;
        let mut now_state = ChatState::NoAgent;
        if let Some(cur) = current.as_mut() {
            match cur.source.poll()? {
                Batch::Missing => now_state = ChatState::Waiting,
                Batch::Replaced => {
                    emit(
                        &mut lines,
                        ChatLine::new(ChatEvent::Reset {
                            agent: cur.agent.name().into(),
                            session: cur.located.session.clone(),
                        }),
                    )?;

                    *last_state = None;
                    *permission = None;
                    cur.source = cur
                        .agent
                        .open_chat(&cur.located, None, state_dir, &*terminal)
                        .source;
                    caught_up = false;
                    now_state = ChatState::Live;
                }
                Batch::Events { events, more } => {
                    for (event, position) in events {
                        let mut line = ChatLine::new(event);
                        if let Some(p) = position {
                            line.cursor =
                                Some(Cursor::format(cur.agent.name(), &cur.located.session, &p));
                        }
                        emit(&mut lines, line)?;
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
            let next = found.as_ref().and_then(|(agent, located)| {
                if !super::requests::blocked_on(&facts, "permission") {
                    return None;
                }
                let screen = lookup.screen(terminal).ok()?;
                super::requests::collect_requests(
                    agent.as_ref(),
                    located,
                    state_dir,
                    terminal,
                    &facts,
                    &[],
                    &screen,
                )
                .pending
                .into_iter()
                .find_map(|r| r.approval_item())
            });
            // Remove transient approvals when answered, replaced, or no longer
            // recognized; never leave them actionable in cached history.
            if permission.as_ref().map(|p| &p.id) != next.as_ref().map(|p| &p.id)
                && let Some(old) = permission.take()
            {
                emit(&mut lines, ChatLine::new(ChatEvent::Remove { id: old.id }))?;
            }
            if *permission != next {
                if let Some(item) = &next {
                    emit(
                        &mut lines,
                        ChatLine::new(ChatEvent::Item { item: item.clone() }),
                    )?;
                }
                *permission = next;
            }
            if permission.is_some() {
                now_state = ChatState::Live;
            }
        }
        if caught_up && *last_state != Some(now_state) {
            let mut line = state(now_state);
            if let Some(cur) = &current {
                line.cursor = Some(cur.cursor());
            }
            emit(&mut lines, line)?;

            *last_state = Some(now_state);
        }

        Ok(ChatFrame {
            lines,
            caught_up,
            finished: false,
        })
    }
}
pub struct ChatFrame {
    lines: Vec<ChatLine>,
    caught_up: bool,
    finished: bool,
}
impl ChatFrame {
    pub(crate) fn wrote(&self) -> bool {
        !self.lines.is_empty()
    }
    pub fn caught_up(&self) -> bool {
        self.caught_up
    }
    pub fn finished(&self) -> bool {
        self.finished
    }
    pub fn write_chat(&self, out: &mut dyn Write) -> std::io::Result<()> {
        for line in &self.lines {
            writeln!(out, "{}", line.to_json())?;
        }
        Ok(())
    }
}
fn state(state: ChatState) -> ChatLine {
    ChatLine::new(ChatEvent::State { state })
}
