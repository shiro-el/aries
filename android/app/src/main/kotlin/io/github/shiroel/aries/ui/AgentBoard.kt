package io.github.shiroel.aries.ui

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.TerminalInfo

/**
 * One Agent in the list of every Host's Agents. A Terminal is only known by
 * its Host together with its whole address (`t7` or `machine/t7`): two Hosts
 * may both have a `t1`.
 */
data class AgentEntry(val host: Host, val terminal: TerminalInfo) {
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

    /** Hosts that could not be reached, with why. */
    val unavailable = mutableStateMapOf<String, Message>()

    /** Hosts whose connection ended after their list arrived. */
    val closed = mutableStateMapOf<String, Boolean>()

    /** Each reached Host's line, by Host id; its state shows while it reconnects. */
    val links = mutableStateMapOf<String, HostLink>()

    /**
     * Waiting (Blocked/Error) first, with pinned Agents first in each group;
     * then status, Host and whole Terminal address for stable ordering.
     */
    val agents: List<AgentEntry>
        get() = hosts.flatMap { h -> agentFamily(h, lists[h.id].orEmpty()) }
            .sortedWith(
                compareBy<AgentEntry>(
                    { !isWaiting(it.terminal.agentStatus) },
                    { !it.terminal.pinned },
                    { rank(it.terminal.agentStatus) },
                    { hosts.indexOf(it.host) },
                    { it.terminal.address },
                ),
            )

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

    private fun rank(s: AgentStatus?): Int = when (s) {
        is AgentStatus.Blocked, is AgentStatus.Error -> 0
        AgentStatus.Done -> 1
        AgentStatus.Working -> 2
        AgentStatus.Idle -> 3
        null -> 4
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
        val rows = terminalTree(agents, namespace = { it.host.id }) { it.terminal }
        items(rows, key = { "${it.value.key.hostId}\n${it.value.key.address}" }) { row ->
            val entry = row.value
            TerminalBranch(row.guide, row.depth) {
                AgentRow(entry, onOpen = { onOpen(entry) }, onPin = { onPin(entry, it) })
            }
        }
    }
}

@Composable
private fun AgentRow(entry: AgentEntry, onOpen: () -> Unit, onPin: (Boolean) -> Unit) {
    ConsoleTerminalRow(entry.terminal, entry.host.name, onOpen,
        modifier = Modifier.testTag("agent-row:${entry.key.hostId}:${entry.key.address}"),
        trailing = { if (entry.terminal.agentStatus != null) PinButton(entry, onPin) })
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
