//! Space / Tab / Pane structure, read from the Host's `herdr-app` document.
//!
//! herdr-core knows only Terminals. herdr-app keeps its Spaces, Tabs and
//! split layouts in the opaque document `herdr-app`
//! (`{spaces:[{id,name,folder,tabs:[{id,name,root}]}]}`, `root` a tree of
//! `block` / `row` / `column` / `stack` nodes; a Pane is a block whose
//! `content` is `{terminal}`), shape taken from herdr-app f6fe216
//! `src/mirror.rs` and `src/layout.rs` (Apache-2.0). The format has no
//! stability promise for other readers (herdr-app#43), so Aries only ever
//! reads it, walks it leniently, and when it is missing, unreadable or of an
//! unknown shape groups Terminals by the documented `herdr.space` /
//! `herdr.tab` labels instead, saying so. Terminals the document does not show
//! yet but whose labels name one of its Tabs (just launched from the phone)
//! are listed in that Tab until herdr-app adopts them.
//!
//! Which Space/Tab/Pane the phone shows, and any zoom, is the app's own
//! state: nothing here writes to the Host.

use std::collections::{BTreeMap, HashSet};

use serde_json::Value;

use crate::{TerminalInfo, terminal_info};

/// Name of herdr-app's document in herdr-core.
pub(crate) const DOCUMENT: &str = "herdr-app";
pub(crate) const SPACE_LABEL: &str = "herdr.space";
pub(crate) const TAB_LABEL: &str = "herdr.tab";

/// A Host's Terminals as herdr-app arranges them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HostStructure {
    pub spaces: Vec<SpaceInfo>,
    /// Terminals with neither their own Pane nor a listed ancestor in a Pane.
    pub unplaced: Vec<TerminalInfo>,
    /// Set when the document could not be used and `spaces` was built from
    /// `herdr.space` / `herdr.tab` labels instead.
    pub problem: Option<StructureProblem>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpaceInfo {
    pub id: String,
    /// May be empty (an unnamed Space, or one known only from labels).
    pub name: String,
    pub folder: Option<String>,
    pub tabs: Vec<TabInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TabInfo {
    pub id: String,
    pub name: String,
    /// In layout order (depth first, floating Panes after their node's children).
    pub panes: Vec<PaneInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PaneInfo {
    pub id: String,
    pub terminal_address: String,
    /// `None` when the Terminal is no longer listed by herdr-core.
    pub terminal: Option<TerminalInfo>,
    /// Pane-less descendants; `parent` keeps their tree, ordered by address.
    #[uniffi(default = [])]
    pub sub_agents: Vec<TerminalInfo>,
}

/// Why the structure came from labels rather than the document.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum StructureProblem {
    /// The Host has no `herdr-app` document (herdr-app never ran there).
    NoDocument,
    /// The document has the known shape but wrong contents.
    Unreadable { detail: String },
    /// The document is not in a format this app knows (a newer herdr-app?).
    UnknownFormat,
}

/// Builds the structure from a `doc.get` result and `terminal.list` records.
pub(crate) fn build(doc: &Value, terminals: &[Value]) -> HostStructure {
    let mut structure = match parse(&doc["value"], terminals) {
        Ok(mut spaces) => {
            place_by_labels(&mut spaces, terminals);
            let placed: HashSet<&str> = spaces
                .iter()
                .flat_map(|s| &s.tabs)
                .flat_map(|t| &t.panes)
                .map(|p| p.terminal_address.as_str())
                .collect();
            let unplaced = terminals
                .iter()
                .filter(|t| !placed.contains(t["address"].as_str().unwrap_or("")))
                .map(terminal_info)
                .collect();
            HostStructure {
                spaces,
                unplaced,
                problem: None,
            }
        }
        Err(problem) => by_labels(terminals, problem),
    };
    place_sub_agents(&mut structure, terminals);
    structure
}

/// Follow only listed ancestors, bounded like herdr-app's `write_where`.
/// Existing Panes win; labels do not give a child a synthetic Pane.
fn place_sub_agents(structure: &mut HostStructure, terminals: &[Value]) {
    let live: BTreeMap<_, _> = terminals
        .iter()
        .filter_map(|t| Some((t["address"].as_str()?, t)))
        .collect();
    let placed: BTreeMap<_, _> = structure
        .spaces
        .iter()
        .enumerate()
        .flat_map(|(s, space)| {
            space.tabs.iter().enumerate().flat_map(move |(t, tab)| {
                tab.panes
                    .iter()
                    .enumerate()
                    .map(move |(p, pane)| (pane.terminal_address.clone(), (s, t, p)))
            })
        })
        .collect();
    structure.unplaced.retain(|terminal| {
        let mut current = terminal.address.as_str();
        let mut visited = HashSet::from([current]);
        for _ in 0..32 {
            let Some(parent) = live.get(current).and_then(|t| t["parent"].as_str()) else {
                break;
            };
            if !live.contains_key(parent) || !visited.insert(parent) {
                break;
            }
            if let Some(&(s, t, p)) = placed.get(parent) {
                structure.spaces[s].tabs[t].panes[p]
                    .sub_agents
                    .push(terminal.clone());
                return false;
            }
            current = parent;
        }
        true
    });
    for pane in structure
        .spaces
        .iter_mut()
        .flat_map(|s| &mut s.tabs)
        .flat_map(|t| &mut t.panes)
    {
        pane.sub_agents.sort_by(|a, b| a.address.cmp(&b.address));
    }
}

fn parse(value: &Value, terminals: &[Value]) -> Result<Vec<SpaceInfo>, StructureProblem> {
    let spaces = match value {
        Value::Null => return Err(StructureProblem::NoDocument),
        Value::Object(o) => match o.get("spaces") {
            Some(s) => s,
            None => return Err(StructureProblem::UnknownFormat),
        },
        _ => return Err(StructureProblem::UnknownFormat),
    };
    let unreadable = |detail: String| StructureProblem::Unreadable { detail };
    let spaces = spaces
        .as_array()
        .ok_or_else(|| unreadable("spaces is not a list".into()))?;
    let lookup = |address: &str| {
        terminals
            .iter()
            .find(|t| t["address"].as_str() == Some(address))
            .map(terminal_info)
    };
    let mut out = vec![];
    for (i, space) in spaces.iter().enumerate() {
        let at = format!("spaces[{i}]");
        let space = space
            .as_object()
            .ok_or_else(|| unreadable(format!("{at} is not an object")))?;
        let text = |o: &serde_json::Map<String, Value>, key: &str, at: &str| match o.get(key) {
            None | Some(Value::Null) => Ok(String::new()),
            Some(Value::String(s)) => Ok(s.clone()),
            Some(_) => Err(unreadable(format!("{at}.{key} is not text"))),
        };
        let tabs_value = space.get("tabs").cloned().unwrap_or(Value::Array(vec![]));
        let tabs = tabs_value
            .as_array()
            .ok_or_else(|| unreadable(format!("{at}.tabs is not a list")))?;
        let mut tab_infos = vec![];
        for (j, tab) in tabs.iter().enumerate() {
            let tat = format!("{at}.tabs[{j}]");
            let tab = tab
                .as_object()
                .ok_or_else(|| unreadable(format!("{tat} is not an object")))?;
            let mut panes = vec![];
            if let Some(root) = tab.get("root") {
                walk(root, &format!("{tat}.root"), &mut panes)?;
            }
            tab_infos.push(TabInfo {
                id: text(tab, "id", &tat)?,
                name: text(tab, "name", &tat)?,
                panes: panes
                    .into_iter()
                    .map(|(id, address)| PaneInfo {
                        sub_agents: vec![],
                        terminal: lookup(&address),
                        id,
                        terminal_address: address,
                    })
                    .collect(),
            });
        }
        let folder = text(space, "folder", &at)?;
        out.push(SpaceInfo {
            id: text(space, "id", &at)?,
            name: text(space, "name", &at)?,
            folder: (!folder.is_empty()).then_some(folder),
            tabs: tab_infos,
        });
    }
    Ok(out)
}

/// Collects `(pane id, terminal address)` for every terminal block under
/// `node`. Node types this app does not know are walked through by their
/// `children`; mod, tab-context and agent blocks are not Panes of a Terminal.
fn walk(node: &Value, at: &str, panes: &mut Vec<(String, String)>) -> Result<(), StructureProblem> {
    let node = node
        .as_object()
        .ok_or_else(|| StructureProblem::Unreadable {
            detail: format!("{at} is not an object"),
        })?;
    let id = node.get("id").and_then(Value::as_str).unwrap_or("");
    if let Some(address) = node
        .get("content")
        .and_then(|c| c.get("terminal"))
        .and_then(Value::as_str)
    {
        panes.push((id.to_owned(), address.to_owned()));
    }
    if let Some(children) = node.get("children") {
        let children = children
            .as_array()
            .ok_or_else(|| StructureProblem::Unreadable {
                detail: format!("{at}.children is not a list"),
            })?;
        for (i, child) in children.iter().enumerate() {
            walk(child, &format!("{at}.children[{i}]"), panes)?;
        }
    }
    if let Some(floating) = node.get("floating").and_then(Value::as_array) {
        for float in floating {
            if let Some(address) = float
                .get("content")
                .and_then(|c| c.get("terminal"))
                .and_then(Value::as_str)
            {
                let fid = float.get("id").and_then(Value::as_str).unwrap_or("");
                panes.push((fid.to_owned(), address.to_owned()));
            }
        }
    }
    Ok(())
}

/// Appends to a document Tab the Terminals that no Pane shows yet but whose
/// `herdr.space` / `herdr.tab` labels name that Tab (just launched from the
/// phone, not adopted by herdr-app yet). Pane id = Terminal address. Labels
/// naming a Space or Tab the document lacks leave the Terminal unplaced.
fn place_by_labels(spaces: &mut [SpaceInfo], terminals: &[Value]) {
    let shown: HashSet<String> = spaces
        .iter()
        .flat_map(|s| &s.tabs)
        .flat_map(|t| &t.panes)
        .map(|p| p.terminal_address.clone())
        .collect();
    for t in terminals {
        if t["parent"].as_str().is_some() {
            continue;
        }
        let label = |k: &str| t["labels"][k].as_str();
        let (Some(space), Some(tab)) = (label(SPACE_LABEL), label(TAB_LABEL)) else {
            continue;
        };
        let info = terminal_info(t);
        if shown.contains(&info.address) {
            continue;
        }
        if let Some(tab) = spaces
            .iter_mut()
            .find(|s| s.id == space)
            .and_then(|s| s.tabs.iter_mut().find(|x| x.id == tab))
        {
            tab.panes.push(PaneInfo {
                sub_agents: vec![],
                id: info.address.clone(),
                terminal_address: info.address.clone(),
                terminal: Some(info),
            });
        }
    }
}

/// The fallback: one Space per `herdr.space` label, one Tab per `herdr.tab`
/// label inside it, one Pane per top-level Terminal (Pane id = its address),
/// ordered by id. Descendants follow their ancestors; other Terminals are unplaced.
fn by_labels(terminals: &[Value], problem: StructureProblem) -> HostStructure {
    let mut spaces: BTreeMap<String, BTreeMap<String, Vec<PaneInfo>>> = BTreeMap::new();
    let mut unplaced = vec![];
    for t in terminals {
        if t["parent"].as_str().is_some() {
            unplaced.push(terminal_info(t));
            continue;
        }
        let label = |k: &str| t["labels"][k].as_str().map(str::to_owned);
        let info = terminal_info(t);
        match label(SPACE_LABEL) {
            Some(space) => spaces
                .entry(space)
                .or_default()
                .entry(label(TAB_LABEL).unwrap_or_default())
                .or_default()
                .push(PaneInfo {
                    sub_agents: vec![],
                    id: info.address.clone(),
                    terminal_address: info.address.clone(),
                    terminal: Some(info),
                }),
            None => unplaced.push(info),
        }
    }
    HostStructure {
        spaces: spaces
            .into_iter()
            .map(|(id, tabs)| SpaceInfo {
                id,
                name: String::new(),
                folder: None,
                tabs: tabs
                    .into_iter()
                    .map(|(id, panes)| TabInfo {
                        id,
                        name: String::new(),
                        panes,
                    })
                    .collect(),
            })
            .collect(),
        unplaced,
        problem: Some(problem),
    }
}

#[cfg(test)]
#[path = "structure_sub_agents_tests.rs"]
mod sub_agent_tests;
