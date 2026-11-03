package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.HostStructure

enum class AgentListView { Priority, Grouped }

/** Space ids are scoped to the AgentEntry's Host. */
data class AgentSpace(val id: String, val name: String, val order: Int)

fun withAgentSpaces(entries: List<AgentEntry>, structure: HostStructure?): List<AgentEntry> {
    val positions = mutableMapOf<String, Int>()
    val spaces = buildMap {
        structure?.spaces?.forEachIndexed { index, space ->
            val location = AgentSpace(space.id, space.name, index)
            space.tabs.forEach { tab -> tab.panes.forEach { pane ->
                put(pane.terminalAddress, location)
                positions[pane.terminalAddress] = positions.size
                pane.subAgents.forEach { put(it.address, location) }
            } }
        }
    }
    val byKey = entries.associateBy { it.key }
    return entries.map { entry ->
        var current: AgentEntry? = entry
        var location: AgentSpace? = null
        val visited = mutableSetOf<AgentKey>()
        while (current != null && visited.add(current.key)) {
            location = spaces[current.terminal.address]
            if (location != null) break
            current = current.terminal.parent?.let { byKey[AgentKey(entry.host.id, it)] }
        }
        entry.copy(space = location, layoutOrder = positions[entry.terminal.address])
    }
}

/** Error retains its own display, but shares the Needs you priority with Blocked. */
fun agentPriority(status: AgentStatus?): Int = when (status) {
    is AgentStatus.Blocked, is AgentStatus.Error -> 4
    AgentStatus.Done -> 3
    AgentStatus.Working -> 2
    AgentStatus.Idle -> 1
    null -> 0
}

/** Child completion is news for its parent, rather than a separate unread Done. */
fun childAgentStatus(status: AgentStatus?): AgentStatus? =
    if (status == AgentStatus.Done) AgentStatus.Idle else status

private data class AgentFamily(val entries: List<AgentEntry>, val sequence: Int) {
    val root get() = entries.first()
    val priority get() = entries.maxOf { agentPriority(it.terminal.agentStatus) }
}

private fun families(entries: List<AgentEntry>): List<AgentFamily> {
    val rows = terminalTree(entries, namespace = { it.host.id }, preserveSiblingOrder = true) { it.terminal }
    val result = mutableListOf<MutableList<AgentEntry>>()
    rows.forEach { row ->
        if (row.depth == 0) result += mutableListOf<AgentEntry>()
        result.last() += if (row.depth == 0) row.value else row.value.copy(
            terminal = row.value.terminal.copy(agentStatus = childAgentStatus(row.value.terminal.agentStatus)),
        )
    }
    return result.map { AgentFamily(it, it.first().sequence) }
}

/**
 * Shared herdr-app ordering for lists, drawers and phone surfaces. Sequence is
 * core listing order; its reverse breaks ties where desktop's local seq is
 * unavailable. Grouped view follows Space/Tab/Pane order. Children stay in core
 * order below their parent. Folding never changes this result.
 */
fun orderAgents(entries: List<AgentEntry>, view: AgentListView = AgentListView.Priority): List<AgentEntry> {
    val ordered = families(entries).let { families ->
        if (view == AgentListView.Priority) families.sortedWith(
            compareByDescending<AgentFamily> { it.priority }
                .thenByDescending { it.root.terminal.statusMs ?: 0uL }
                .thenByDescending { it.sequence },
        ) else families.sortedWith(compareBy(
            { it.root.hostOrder },
            { it.root.space?.order ?: Int.MAX_VALUE },
            { it.root.layoutOrder ?: Int.MAX_VALUE },
            { it.sequence },
        ))
    }
    return (ordered.filter { it.root.terminal.pinned } + ordered.filterNot { it.root.terminal.pinned })
        .flatMap { it.entries }
}

data class AgentGroup(
    val id: String,
    val entries: List<AgentEntry>,
    val count: Int,
    val priority: Int,
    val space: AgentSpace? = null,
)

/** Pinned is always its own section; other sections use effective family urgency. */
fun agentGroups(entries: List<AgentEntry>, view: AgentListView): List<AgentGroup> {
    val groups = families(orderAgents(entries, view)).groupBy { family ->
        when {
            family.root.terminal.pinned -> "pinned"
            view == AgentListView.Priority -> "priority:${family.priority.coerceAtLeast(1)}"
            else -> "space:${family.root.host.id}:${family.root.space?.id.orEmpty()}"
        }
    }
    return groups.map { (id, members) ->
        AgentGroup(id, members.flatMap { it.entries }, members.size, members.maxOf { it.priority }, members.first().root.space)
    }
}

internal fun agentTreeRows(entries: List<AgentEntry>, folded: Set<AgentKey> = emptySet(), rootGuides: Boolean = false): List<TerminalTreeRow<AgentEntry>> {
    var hiddenBelow: Int? = null
    return terminalTree(entries, rootGuides, namespace = { it.host.id }, preserveSiblingOrder = true) { it.terminal }
        .filter { row ->
            if (hiddenBelow != null && row.depth > hiddenBelow!!) false
            else {
                hiddenBelow = if (row.value.key in folded) row.depth else null
                true
            }
        }
}

internal fun agentDescendants(entries: List<AgentEntry>, key: AgentKey): List<AgentEntry> {
    val rows = agentTreeRows(entries)
    val start = rows.indexOfFirst { it.value.key == key }
    if (start < 0) return emptyList()
    return rows.drop(start + 1).takeWhile { it.depth > rows[start].depth }.map { it.value }
}
