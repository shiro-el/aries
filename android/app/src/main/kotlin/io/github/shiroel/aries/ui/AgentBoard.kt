package io.github.shiroel.aries.ui

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.TerminalInfo

/**
 * One Agent in the list of every Host's Agents. A Terminal is only known by
 * its Host together with its whole address (`t7` or `machine/t7`): two Hosts
 * may both have a `t1`.
 */
data class AgentEntry(
    val host: Host,
    val terminal: TerminalInfo,
    val space: AgentSpace? = null,
    val sequence: Int = 0,
    val layoutOrder: Int? = null,
    val hostOrder: Int = 0,
) {
    val key: AgentKey get() = AgentKey(host.id, terminal.address)
}

data class AgentKey(val hostId: String, val address: String)

/**
 * The Agents of every registered Host, live. [HostsModel] feeds each Host's
 * Terminal list in; this keeps them apart per Host and merges the Agents
 * (Terminals that report Agent status) into one list.
 */
class AgentBoard(val hosts: List<Host>) {
    private val lists = mutableStateMapOf<String, List<TerminalInfo>>()
    private val structures = mutableStateMapOf<String, HostStructure>()
    var view by mutableStateOf(AgentListView.Priority)
    val foldedAgents = mutableStateMapOf<AgentKey, Boolean>()
    val foldedGroups = mutableStateMapOf<String, Boolean>()

    /** Hosts that could not be reached, with why. */
    val unavailable = mutableStateMapOf<String, Message>()

    /** Hosts whose connection ended after their list arrived. */
    val closed = mutableStateMapOf<String, Boolean>()

    /** Each reached Host's line, by Host id; its state shows while it reconnects. */
    val links = mutableStateMapOf<String, HostLink>()

    /** Shared ordering, including descendant urgency and global pins. */
    val agents: List<AgentEntry>
        get() = orderAgents(hosts.flatMapIndexed { index, h ->
            withAgentSpaces(agentFamily(h, lists[h.id].orEmpty()), structures[h.id]).map { it.copy(hostOrder = index) }
        }, view)

    fun updateStructure(hostId: String, structure: HostStructure) {
        structures[hostId] = structure
    }

    fun find(key: AgentKey): AgentEntry? =
        hosts.find { it.id == key.hostId }?.let { h ->
            lists[h.id]?.find { it.address == key.address }?.let { AgentEntry(h, it) }
        }

    fun update(hostId: String, list: List<TerminalInfo>) {
        unavailable.remove(hostId)
        lists[hostId] = list
    }

    /** One Terminal changed (the reply of a label write), ahead of the watch. */
    fun replace(hostId: String, t: TerminalInfo) {
        lists[hostId]?.let { list -> lists[hostId] = list.map { if (it.address == t.address) t else it } }
    }

    fun fail(hostId: String, why: Message) {
        unavailable[hostId] = why
    }

    fun markClosed(hostId: String) {
        if (hostId in lists) closed[hostId] = true
    }

}

@Composable
fun AgentsScreen(
    board: AgentBoard,
    onBack: () -> Unit,
    onOpen: (AgentEntry) -> Unit,
    onPin: (AgentEntry, Boolean) -> Unit,
    showHeader: Boolean = true,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (showHeader) item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            Text(stringResource(R.string.agents_title), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Row {
                AgentListView.entries.forEach { view ->
                    TextButton(onClick = { board.view = view }, enabled = board.view != view) {
                        Text(stringResource(if (view == AgentListView.Priority) R.string.agents_view_priority else R.string.agents_view_grouped))
                    }
                }
            }
        }
        if (board.hosts.isEmpty()) item { Text(stringResource(R.string.agents_no_hosts)) }
        items(board.hosts.filter { it.id in board.unavailable }, key = { "down:" + it.id }) { host ->
            Column {
                Text(
                    stringResource(R.string.agents_host_unavailable, host.name),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                board.unavailable[host.id]?.let { Text(stringResource(it.res, *it.args.toTypedArray()), style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(board.hosts.filter { board.links[it.id]?.state == LinkState.Reconnecting }, key = { "again:" + it.id }) { host ->
            Text(stringResource(R.string.agents_host_reconnecting, host.name), style = MaterialTheme.typography.bodySmall)
        }
        val down = { h: Host -> board.closed[h.id] == true || board.links[h.id]?.state is LinkState.Down }
        items(board.hosts.filter(down), key = { "closed:" + it.id }) { host ->
            Text(
                stringResource(R.string.agents_host_closed, host.name),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val agents = board.agents
        if (board.hosts.isNotEmpty() && agents.isEmpty()) item { Text(stringResource(R.string.agents_empty)) }
        agentGroups(agents, board.view).forEach { group ->
            val folded = board.foldedGroups[group.id] == true
            item(key = "group:${group.id}") {
                TextButton(onClick = { board.foldedGroups[group.id] = !folded }) {
                    Text(agentGroupLabel(group))
                    if (folded) AgentStatusMark(priorityStatus(group.priority))
                }
            }
            if (!folded) {
                val rows = agentTreeRows(group.entries, board.foldedAgents.filterValues { it }.keys)
                items(rows, key = { "${it.value.key.hostId}\n${it.value.key.address}" }) { row ->
                    val entry = row.value
                    val descendants = agentDescendants(group.entries, entry.key)
                    TerminalBranch(row.guide, row.depth) {
                        AgentRow(entry, onOpen = { onOpen(entry) }, onPin = { onPin(entry, it) }, fold = {
                            AgentFoldButton(entry, descendants, board.foldedAgents[entry.key] == true) {
                                board.foldedAgents[entry.key] = board.foldedAgents[entry.key] != true
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRow(entry: AgentEntry, onOpen: () -> Unit, onPin: (Boolean) -> Unit, fold: @Composable () -> Unit) {
    ConsoleTerminalRow(entry.terminal, entry.host.name, onOpen,
        modifier = Modifier.testTag("agent-row:${entry.key.hostId}:${entry.key.address}"),
        trailing = { fold(); if (entry.terminal.agentStatus != null) PinButton(entry, onPin) })
}

@Composable
private fun agentGroupLabel(group: AgentGroup): String = when {
    group.id == "pinned" -> stringResource(R.string.agents_pinned_group, group.count)
    group.id.startsWith("priority:") -> stringResource(R.string.agents_priority_group,
        stringResource(when (group.priority) {
            4 -> R.string.agents_needs_you
            3 -> R.string.agent_status_done
            2 -> R.string.agent_status_working
            else -> R.string.agent_status_idle
        }), group.count)
    else -> stringResource(R.string.agents_space_group, group.entries.first().host.name,
        group.space?.name ?: stringResource(R.string.unplaced_title), group.count)
}

private fun priorityStatus(priority: Int): AgentStatus = when (priority) {
    4 -> AgentStatus.Blocked(null, null)
    3 -> AgentStatus.Done
    2 -> AgentStatus.Working
    else -> AgentStatus.Idle
}

@Composable
internal fun AgentFoldButton(entry: AgentEntry, descendants: List<AgentEntry>, folded: Boolean, onFold: () -> Unit) {
    if (descendants.isEmpty()) return
    val label = stringResource(if (folded) R.string.agents_expand_children else R.string.agents_collapse_children,
        displayName(entry.terminal), descendants.size)
    TextButton(onClick = onFold, modifier = Modifier.testTag("agent-fold:${entry.host.id}:${entry.terminal.address}")
        .semantics { contentDescription = label }) {
        Text(stringResource(if (folded) R.string.agents_fold_closed else R.string.agents_fold_open))
        if (folded) Row(Modifier.testTag("agent-summary:${entry.host.id}:${entry.terminal.address}")) {
            Text(stringResource(R.string.agents_child_count, descendants.size))
            AgentStatusMark(priorityStatus(descendants.maxOf { agentPriority(childAgentStatus(it.terminal.agentStatus)) }))
        }
    }
}

@Composable
private fun PinButton(entry: AgentEntry, onPin: (Boolean) -> Unit) {
    val name = displayName(entry.terminal)
    val pinned = entry.terminal.pinned
    val label = stringResource(
        if (pinned) R.string.agent_unpin_button_label else R.string.agent_pin_button_label,
        name,
        entry.host.name,
    )
    TextButton(
        onClick = { onPin(!pinned) },
        shape = RectangleShape,
        modifier = Modifier.semantics { contentDescription = label; selected = pinned },
    ) {
        Text(
            stringResource(if (pinned) R.string.agent_unpin_button else R.string.agent_pin_button),
            fontFamily = FontFamily.Monospace,
        )
    }
}
