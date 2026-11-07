use super::claude::hook;
use super::delivery::{Route, Tui};
use super::{Adapters, Agent, Batch, Located, TerminalFacts, TerminalLookup};
use aries_protocol::chat::{ChatBody, ChatEvent, ChatItem, ChatQuestion, ToolStatus};
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestId(String);
impl RequestId {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}
fn request_id(spelling: &str) -> RequestId {
    RequestId(spelling.to_owned())
}

pub struct PendingRequest {
    id: RequestId,
    item: ChatItem,
    route: Route,
    current: bool,
    visible: bool,
    identity: super::claims::Identity,
}
impl PendingRequest {
    pub fn id(&self) -> &RequestId {
        &self.id
    }
    pub(super) fn identity(&self) -> &super::claims::Identity {
        &self.identity
    }
    pub(super) fn route(&self) -> &Route {
        &self.route
    }
    pub(super) fn is_current(&self) -> bool {
        self.current
    }
    pub(super) fn approval_item(self) -> Option<ChatItem> {
        match self.route {
            Route::Tui(Tui::Approval(_))
            | Route::ClaudeHook {
                fallback: Tui::Approval(_),
                ..
            } => Some(self.item),
            _ => None,
        }
    }
    pub(super) fn question(&self) -> Option<&ChatQuestion> {
        match &self.item.body {
            ChatBody::Tool { question, .. } => question.as_ref(),
            _ => None,
        }
    }
}

pub(super) struct RequestSet {
    pub(super) pending: Vec<PendingRequest>,
}

pub(super) fn blocked_on(facts: &TerminalFacts, kind: &str) -> bool {
    facts
        .status
        .as_ref()
        .is_some_and(|s| s.state == "blocked" && s.kind.as_deref() == Some(kind))
}

pub(super) fn collect_requests(
    agent: &dyn Agent,
    located: &Located,
    state: &Path,
    terminal: &str,
    facts: &TerminalFacts,
    items: &[ChatItem],
    screen: &str,
) -> RequestSet {
    let hook = agent.hook(state, terminal, located);
    let head = agent.current_question(items).map(|(id, _)| id);
    let correlated: Vec<_> = hook
        .as_ref()
        .map(|p| {
            pending_questions(items)
                .filter_map(|(id, _)| {
                    let item = items.iter().find(|item| item.id == id)?;
                    match &item.body {
                        ChatBody::Tool { name, input, .. }
                            if name == &p.request.tool_name
                                && hook::same_input(name, input, &p.request.tool_input) =>
                        {
                            Some(id)
                        }
                        _ => None,
                    }
                })
                .collect()
        })
        .unwrap_or_default();
    let ambiguous = correlated.len() > 1;
    let correlation = (correlated.len() == 1).then(|| correlated[0]);
    let mut pending = Vec::new();
    for (id, _) in pending_questions(items) {
        let item = items
            .iter()
            .find(|item| item.id == id)
            .expect("pending question");
        let covered = correlation == Some(item.id.as_str());
        let potential_conflict = ambiguous && correlated.contains(&item.id.as_str());
        pending.push(PendingRequest {
            id: request_id(&item.id),
            item: item.clone(),
            route: Route::Tui(Tui::Question),
            current: head == Some(item.id.as_str()) && !potential_conflict,
            visible: !covered,
            identity: super::claims::Identity::new(
                agent,
                located,
                item,
                hook.as_ref().filter(|_| covered),
                covered.then_some(item.id.as_str()),
            ),
        });
    }
    if let Some(primary) = hook
        .as_ref()
        .filter(|p| p.request.tool_name == "AskUserQuestion" || !blocked_on(facts, "permission"))
    {
        let item = hook::item(primary);
        pending.push(PendingRequest {
            id: request_id(&item.id),
            identity: super::claims::Identity::new(
                agent,
                located,
                &item,
                Some(primary),
                correlation,
            ),
            item,
            current: !ambiguous,
            visible: true,
            route: Route::ClaudeHook {
                primary: Box::new(primary.clone()),
                fallback: Tui::Question,
            },
        });
    }
    if let Some(permission) = agent.approval(&located.session, facts, screen) {
        let item = permission.item.clone();
        let fallback = Tui::Approval(Box::new(permission));
        let identity = super::claims::Identity::new(agent, located, &item, hook.as_ref(), None);
        let route = match hook.filter(|p| p.request.tool_name != "AskUserQuestion") {
            Some(primary) => Route::ClaudeHook {
                primary: Box::new(primary),
                fallback,
            },
            None => Route::Tui(fallback),
        };
        pending.push(PendingRequest {
            id: request_id(&item.id),
            item,
            identity,
            route,
            current: true,
            visible: true,
        });
    }
    RequestSet { pending }
}

pub struct Observation {
    facts: Option<TerminalFacts>,
    requests: RequestSet,
    last_reply: Option<String>,
}
impl Observation {
    pub fn matches(&self, expected: &TerminalFacts) -> bool {
        self.facts
            .as_ref()
            .is_some_and(|f| f.turn == expected.turn && f.status == expected.status)
    }
    pub fn pending(&self) -> &[PendingRequest] {
        &self.requests.pending
    }
    pub fn turn(&self) -> Option<u64> {
        self.facts.as_ref().and_then(|f| f.turn)
    }
    pub fn last_reply(&self) -> Option<&str> {
        self.last_reply.as_deref()
    }
    pub fn quick_reply(&self) -> Option<QuickReply<'_>> {
        let facts = self.facts.as_ref()?;
        let permission = blocked_on(facts, "permission");
        if !permission && !blocked_on(facts, "question") {
            return None;
        }
        let mut visible = self
            .requests
            .pending
            .iter()
            .filter(|r| r.visible && r.question().is_some());
        let r = visible.next()?;
        if visible.next().is_some() || !r.current {
            return None;
        }
        let [q] = r.question()?.questions.as_slice() else {
            return None;
        };
        if q.multi || !(1..=3).contains(&q.options.len()) {
            return None;
        }
        let text = if permission {
            std::iter::once(q.text.clone())
                .chain(
                    q.options
                        .iter()
                        .filter(|o| o.label == "Allow always")
                        .filter_map(|o| o.description.clone()),
                )
                .collect::<Vec<_>>()
                .join("\n")
        } else {
            q.text.clone()
        };
        Some(QuickReply {
            id: &r.id,
            text,
            options: q.options.iter().map(|o| o.label.clone()).collect(),
        })
    }
}
pub struct QuickReply<'a> {
    id: &'a RequestId,
    text: String,
    options: Vec<String>,
}
impl QuickReply<'_> {
    pub fn id(&self) -> &RequestId {
        self.id
    }
    pub fn text(&self) -> &str {
        &self.text
    }
    pub fn options(&self) -> &[String] {
        &self.options
    }
}
impl Adapters {
    pub fn observe(
        &self,
        terminal: &str,
        lookup: &mut dyn TerminalLookup,
    ) -> std::io::Result<Observation> {
        let mut out = Observation {
            facts: lookup.get(terminal).ok().flatten(),
            requests: RequestSet { pending: vec![] },
            last_reply: None,
        };
        let Some(facts) = &out.facts else {
            return Ok(out);
        };
        let Some((agent, located)) = self
            .agents
            .iter()
            .find_map(|a| a.locate(facts).map(|l| (a, l)))
        else {
            return Ok(out);
        };
        if blocked_on(facts, "permission") {
            for attempt in 0..20 {
                let Some(current) = lookup.get(terminal).ok().flatten() else {
                    break;
                };
                if current.turn != facts.turn || current.status != facts.status {
                    break;
                }
                let screen = lookup.screen(terminal).unwrap_or_default();
                out.requests = collect_requests(
                    agent.as_ref(),
                    &located,
                    &self.state,
                    terminal,
                    &current,
                    &[],
                    &screen,
                );
                if !out.requests.pending.is_empty() {
                    break;
                }
                if attempt < 19 {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
            }
        } else {
            let items = transcript(agent.as_ref(), &located)?.unwrap_or_default();
            out.last_reply = items.iter().rev().find_map(|i| match &i.body {
                ChatBody::Agent { text, .. } => Some(text.clone()),
                _ => None,
            });
            out.requests = collect_requests(
                agent.as_ref(),
                &located,
                &self.state,
                terminal,
                facts,
                &items,
                "",
            );
        }
        Ok(out)
    }
}

/// A current transcript, applying the same upsert / remove / reset semantics
/// as Chat. Shared by answers and status notifications; missing or replaced
/// transcripts have no details to show.
pub fn transcript(
    agent: &dyn Agent,
    located: &super::Located,
) -> std::io::Result<Option<Vec<ChatItem>>> {
    read_transcript(agent.open(located, None).source)
}

pub(super) fn terminal_transcript(
    agent: &dyn Agent,
    located: &super::Located,
    state: &Path,
    terminal: &str,
) -> std::io::Result<Option<Vec<ChatItem>>> {
    read_transcript(hook::open(agent, located, None, state, terminal).source)
}

fn read_transcript(
    mut source: Box<dyn super::source::Source>,
) -> std::io::Result<Option<Vec<ChatItem>>> {
    let mut items: Vec<ChatItem> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    loop {
        match source.poll()? {
            Batch::Missing | Batch::Replaced => {
                return Ok(None);
            }
            Batch::Events { events, more } => {
                for (event, _) in events {
                    match event {
                        ChatEvent::Item { item } => match index.get(&item.id) {
                            Some(&i) => items[i] = item,
                            None => {
                                index.insert(item.id.clone(), items.len());
                                items.push(item);
                            }
                        },
                        ChatEvent::Remove { id } => {
                            if index.remove(&id).is_some() {
                                items.retain(|i| i.id != id);
                                index = (items.iter().enumerate())
                                    .map(|(n, i)| (i.id.clone(), n))
                                    .collect();
                            }
                        }
                        ChatEvent::Reset { .. } => {
                            items.clear();
                            index.clear();
                        }
                        _ => {}
                    }
                }
                if !more {
                    break;
                }
            }
        }
    }
    Ok(Some(items))
}

/// Unresolved questions in the current user turn, in transcript order.
pub fn pending_questions(
    items: &[ChatItem],
) -> impl DoubleEndedIterator<Item = (&str, &ChatQuestion)> {
    let start = items
        .iter()
        .rposition(|item| matches!(item.body, ChatBody::User { queued: false, .. }))
        .map_or(0, |i| i + 1);
    items[start..].iter().filter_map(|item| match &item.body {
        ChatBody::Tool {
            status: ToolStatus::Running,
            question: Some(q),
            ..
        } => Some((item.id.as_str(), q)),
        _ => None,
    })
}

/// Codex queues requests in transcript order within the current user turn.
/// A new user message retires unfinished tools from previous turns.
pub fn queued_question(items: &[ChatItem]) -> Option<(&str, &ChatQuestion)> {
    pending_questions(items).next()
}

/// The newest unresolved question in the current user turn. Older running
/// tools may have been interrupted; a later user message retires them.
pub fn open_question(items: &[ChatItem]) -> Option<(&str, &ChatQuestion)> {
    pending_questions(items).next_back()
}
