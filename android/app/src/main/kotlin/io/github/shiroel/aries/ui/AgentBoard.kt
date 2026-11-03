package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

    /** The Agent whose detail is open, if any. */
    var openKey by mutableStateOf<AgentKey?>(null)
        private set

    /**
     * Pinned first, then what needs the user (Blocked, Error), then Done,
     * Working and Idle; within that by Host, then address.
     */
    val agents: List<AgentEntry>
        get() = hosts.flatMap { h -> lists[h.id].orEmpty().filter { it.agentStatus != null }.map { AgentEntry(h, it) } }
            .sortedWith(
                compareBy<AgentEntry>(
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
    onChat: (AgentEntry) -> Unit = {},
) {
    val key = board.openKey
    if (key != null) {
        AgentDetail(board, key, onPin, onTerminal, onChat)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
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
        items(board.hosts.filter { board.closed[it.id] == true }, key = { "closed:" + it.id }) { host ->
            Text(
                stringResource(R.string.agents_host_closed, host.name),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val agents = board.agents
        if (board.hosts.isNotEmpty() && agents.isEmpty()) item { Text(stringResource(R.string.agents_empty)) }
        items(agents, key = { "${it.key.hostId}\n${it.key.address}" }) { entry ->
            AgentRow(entry, onOpen = { onOpen(entry) }, onPin = { onPin(entry, it) })
        }
    }
}

@Composable
private fun AgentRow(entry: AgentEntry, onOpen: () -> Unit, onPin: (Boolean) -> Unit) {
    val t = entry.terminal
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 8.dp)) {
            AgentHeading(entry)
            AgentStatusLine(t)
        }
        PinButton(entry, onPin)
    }
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
    TextButton(onClick = { onPin(!pinned) }, modifier = Modifier.semantics { contentDescription = label }) {
        Text(stringResource(if (pinned) R.string.agent_unpin_button else R.string.agent_pin_button))
    }
}

@Composable
private fun AgentDetail(
    board: AgentBoard,
    key: AgentKey,
    onPin: (AgentEntry, Boolean) -> Unit,
    onTerminal: (AgentEntry) -> Unit,
    onChat: (AgentEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = board::closeDetail) { Text(stringResource(R.string.back_button)) }
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
        Button(onClick = { onTerminal(entry) }) { Text(stringResource(R.string.terminal_open_button)) }
        val chatLabel = stringResource(R.string.chat_open_label, displayName(t))
        Button(onClick = { onChat(entry) }, modifier = Modifier.semantics { contentDescription = chatLabel }) {
            Text(stringResource(R.string.chat_open_button))
        }
        Text(stringResource(R.string.agent_detail_host, entry.host.name), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.agent_detail_address, t.address), style = MaterialTheme.typography.bodySmall)
        t.cwd?.let { Text(stringResource(R.string.agent_detail_folder, it), style = MaterialTheme.typography.bodySmall) }
        if (t.argv.isNotEmpty()) {
            Text(stringResource(R.string.agent_detail_command, t.argv.joinToString(" ")), style = MaterialTheme.typography.bodySmall)
        }
    }
}
