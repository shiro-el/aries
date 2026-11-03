use super::*;

#[test]
fn descendants_belong_to_the_ancestor_pane_not_other_terminals() {
    let fixture: Value =
        serde_json::from_str(include_str!("../../../fixtures/herdr-app/sub-agents.json")).unwrap();
    let structure = build(
        &fixture["document"],
        fixture["terminals"].as_array().unwrap(),
    );
    let addresses: Vec<_> = structure
        .unplaced
        .iter()
        .map(|t| t.address.as_str())
        .collect();
    assert_eq!(addresses, ["t5", "t4", "t6", "t7", "t8"]);
    assert_eq!(structure.spaces[0].tabs[0].panes.len(), 1);
}
#[test]
fn retains_parent_links_and_sorts_pane_descendants_by_address() {
    let fixture: Value =
        serde_json::from_str(include_str!("../../../fixtures/herdr-app/sub-agents.json")).unwrap();
    let s = build(
        &fixture["document"],
        fixture["terminals"].as_array().unwrap(),
    );
    let children = &s.spaces[0].tabs[0].panes[0].sub_agents;
    assert_eq!(
        children
            .iter()
            .map(|t| t.address.as_str())
            .collect::<Vec<_>>(),
        ["t2", "t3"]
    );
    assert_eq!(children[0].parent.as_deref(), Some("t1"));
    assert_eq!(children[1].parent.as_deref(), Some("t2"));
    assert_eq!(
        s.unplaced
            .iter()
            .find(|t| t.address == "t5")
            .unwrap()
            .parent
            .as_deref(),
        Some("t4")
    );
}

#[test]
fn labels_do_not_steal_children_from_their_ancestor_pane() {
    let mut fixture: Value =
        serde_json::from_str(include_str!("../../../fixtures/herdr-app/sub-agents.json")).unwrap();
    fixture["terminals"][0]["labels"] = serde_json::json!({"herdr.space":"s1", "herdr.tab":"tab1"});
    let s = build(
        &fixture["document"],
        fixture["terminals"].as_array().unwrap(),
    );
    assert_eq!(s.spaces[0].tabs[0].panes.len(), 1);
    assert_eq!(s.spaces[0].tabs[0].panes[0].sub_agents.len(), 2);
}

#[test]
fn fallback_labels_place_roots_and_their_descendants_together() {
    let terminals = serde_json::json!([
        {"address":"box/t2", "parent":"box/t1", "labels":{"herdr.space":"wrong"}},
        {"address":"box/t1", "labels":{"herdr.space":"s", "herdr.tab":"tab"}},
        {"address":"t2", "parent":"t1"}
    ]);
    let s = build(&Value::Null, terminals.as_array().unwrap());
    assert_eq!(s.spaces.len(), 1);
    assert_eq!(s.spaces[0].tabs[0].panes[0].sub_agents[0].address, "box/t2");
    assert_eq!(s.unplaced[0].address, "t2");
}

#[test]
fn a_child_in_its_own_pane_stays_there_and_missing_ancestors_do_not_own_children() {
    let doc = serde_json::json!({"value":{"spaces":[{"tabs":[{"root":{"children":[
        {"id":"p1", "content":{"terminal":"t1"}},
        {"id":"p2", "content":{"terminal":"t2"}}
    ]}}]}]}});
    let terminals = serde_json::json!([
        {"address":"t2", "parent":"t1"}, {"address":"t3", "parent":"t2"}
    ]);
    let s = build(&doc, terminals.as_array().unwrap());
    assert!(s.unplaced.is_empty());
    assert!(s.spaces[0].tabs[0].panes[0].sub_agents.is_empty());
    assert_eq!(s.spaces[0].tabs[0].panes[1].sub_agents[0].address, "t3");
    let s = build(&doc, &[serde_json::json!({"address":"t3", "parent":"t1"})]);
    assert_eq!(s.unplaced.len(), 1);
}

#[test]
fn ancestor_search_is_bounded_at_32_links() {
    let doc =
        serde_json::json!({"value":{"spaces":[{"tabs":[{"root":{"content":{"terminal":"t0"}}}]}]}});
    let mut terminals = vec![serde_json::json!({"address":"t0"})];
    terminals.extend(
        (1..=33)
            .map(|n| serde_json::json!({"address":format!("t{n}"), "parent":format!("t{}", n-1)})),
    );
    let s = build(&doc, &terminals);
    assert_eq!(s.spaces[0].tabs[0].panes[0].sub_agents.len(), 32);
    assert_eq!(s.unplaced[0].address, "t33");
}

#[test]
fn child_status_is_preserved_without_requesting_human_attention() {
    let info = terminal_info(&serde_json::json!({
        "address":"t2", "parent":"t1", "turn":1,
        "status":{"state":"blocked", "kind":"question", "msg":"Ask parent"}
    }));
    assert!(info.agent_status.is_some());
    assert!(!info.needs_attention);
}
