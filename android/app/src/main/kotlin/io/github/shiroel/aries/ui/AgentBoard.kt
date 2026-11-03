package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

    /** The Agent whose detail is open, if any. */
    var openKey by mutableStateOf<AgentKey?>(null)
        private set

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

    val opened: AgentEntry? get() = openKey?.let(::find)

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

    fun open(key: AgentKey) {
        openKey = key
    }

    fun closeDetail() {
        openKey = null
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
    onTerminal: (AgentEntry) -> Unit = {},
    showHeader: Boolean = true,
) {
    val key = board.openKey
    if (key != null) {
        AgentDetail(board, key, onPin, onTerminal, showHeader)
        return
    }
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
private fun AgentHeading(entry: AgentEntry) {
    val t = entry.terminal
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(displayName(t), style = MaterialTheme.typography.titleMedium)
        if (t.pinned) {
            Text(stringResource(R.string.agent_pinned_label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
    Text(stringResource(R.string.terminal_subtitle, entry.host.name, t.address), style = MaterialTheme.typography.bodySmall)
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
    val color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = { onPin(!pinned) }, modifier = Modifier.semantics { contentDescription = label; selected = pinned }) {
        Canvas(Modifier.size(16.dp)) {
            val u = size.width / 16f
            val path = Path().apply {
                moveTo(5*u, 2*u); lineTo(11*u, 2*u); lineTo(10*u, 7*u)
                lineTo(13*u, 10*u); lineTo(3*u, 10*u); lineTo(6*u, 7*u); close()
                moveTo(8*u, 10*u); lineTo(8*u, 15*u)
            }
            drawPath(path, color, style = Stroke(1.5f*u))
        }
    }
}

@Composable
private fun AgentDetail(
    board: AgentBoard,
    key: AgentKey,
    onPin: (AgentEntry, Boolean) -> Unit,
    onTerminal: (AgentEntry) -> Unit,
    showHeader: Boolean,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeader) TextButton(onClick = board::closeDetail) { Text(stringResource(R.string.back_button)) }
        val entry = board.find(key)
        val host = board.hosts.find { it.id == key.hostId }
        if (entry == null || entry.terminal.agentStatus == null) {
            Text(stringResource(R.string.agent_detail_gone))
            host?.let { Text(stringResource(R.string.agent_detail_host, it.name), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.agent_detail_address, key.address), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val t = entry.terminal
        AgentHeading(entry)
        AgentStatusLine(t)
        PinButton(entry) { onPin(entry, it) }
        // One entry: the Agent opens in the view picked last (Terminal or Chat).
        ConsoleButton(onClick = { onTerminal(entry) }) { Text(stringResource(R.string.agent_open_button)) }
        Text(stringResource(R.string.agent_detail_host, entry.host.name), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.agent_detail_address, t.address), style = MaterialTheme.typography.bodySmall)
        t.cwd?.let { Text(stringResource(R.string.agent_detail_folder, it), style = MaterialTheme.typography.bodySmall) }
        if (t.argv.isNotEmpty()) {
            Text(stringResource(R.string.agent_detail_command, t.argv.joinToString(" ")), style = MaterialTheme.typography.bodySmall)
        }
    }
}
