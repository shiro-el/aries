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
//! `herdr.tab` labels instead, saying so.
//!
//! Which Space/Tab/Pane the phone shows, and any zoom, is the app's own
//! state: nothing here writes to the Host.

use std::collections::{BTreeMap, HashSet};

use serde_json::Value;

use crate::{TerminalInfo, terminal_info};

/// Name of herdr-app's document in herdr-core.
pub(crate) const DOCUMENT: &str = "herdr-app";
const SPACE_LABEL: &str = "herdr.space";
const TAB_LABEL: &str = "herdr.tab";

/// A Host's Terminals as herdr-app arranges them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HostStructure {
    pub spaces: Vec<SpaceInfo>,
    /// Terminals in no Pane (started outside herdr-app, or not placed yet).
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
    match parse(&doc["value"], terminals) {
        Ok(spaces) => {
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

/// The fallback: one Space per `herdr.space` label, one Tab per `herdr.tab`
/// label inside it, one Pane per Terminal (Pane id = its address), ordered by
/// id. Terminals without a `herdr.space` label are unplaced.
fn by_labels(terminals: &[Value], problem: StructureProblem) -> HostStructure {
    let mut spaces: BTreeMap<String, BTreeMap<String, Vec<PaneInfo>>> = BTreeMap::new();
    let mut unplaced = vec![];
    for t in terminals {
        let label = |k: &str| t["labels"][k].as_str().map(str::to_owned);
        let info = terminal_info(t);
        match label(SPACE_LABEL) {
            Some(space) => spaces
                .entry(space)
                .or_default()
                .entry(label(TAB_LABEL).unwrap_or_default())
                .or_default()
                .push(PaneInfo {
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
