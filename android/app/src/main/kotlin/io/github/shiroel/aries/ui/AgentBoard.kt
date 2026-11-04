package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
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
    val tabName: String? = null,
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
    /** Compatibility with older callers; orderAgents ignores the retired Grouped value. */
    var view by mutableStateOf(AgentListView.Priority)

    /** Hosts that could not be reached, with why. */
    val unavailable = mutableStateMapOf<String, Message>()

    /** Hosts whose connection ended after their list arrived. */
    val closed = mutableStateMapOf<String, Boolean>()

    /** Each reached Host's line, by Host id; its state shows while it reconnects. */
    val links = mutableStateMapOf<String, HostLink>()

    /** A connected socket is still loading until its first complete Terminal list arrives. */
    val loadingHosts: List<Host>
        get() = hosts.filter { it.id !in lists && it.id !in unavailable && closed[it.id] != true && links[it.id]?.state !is LinkState.Down }

    val isEmpty: Boolean
        get() = hosts.isNotEmpty() && hosts.all { it.id in lists } && unavailable.isEmpty() &&
            closed.values.none { it } && links.values.none { it.state != LinkState.Up } && agents.isEmpty()

    /** Shared ordering, including descendant urgency and global pins. */
    val agents: List<AgentEntry>
        get() = orderAgents(hosts.flatMapIndexed { index, h ->
            withAgentSpaces(agentFamily(h, lists[h.id].orEmpty()), structures[h.id]).map { it.copy(hostOrder = index) }
        }, view)

    /** Reuses the existing watches; an empty live list must replace stored Agents. */
    val quickOpenSources: List<QuickOpenSource>
        get() = hosts.filter { links[it.id]?.state == LinkState.Up && it.id in lists }.map {
            QuickOpenSource(it, structures[it.id], lists[it.id].orEmpty())
        }

    fun updateStructure(hostId: String, structure: HostStructure) {
        structures[hostId] = structure
    }

    /** Latest layout for retaining an Agent's navigation ancestors on the phone. */
    fun structureOf(hostId: String): HostStructure? = structures[hostId]

    fun find(key: AgentKey): AgentEntry? =
        hosts.find { it.id == key.hostId }?.let { h ->
            lists[h.id]?.find { it.address == key.address }?.let { AgentEntry(h, it) }
        }

    fun update(hostId: String, list: List<TerminalInfo>) {
        unavailable.remove(hostId)
        closed.remove(hostId)
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
        closed[hostId] = true
    }

}

@Composable
fun AgentsScreen(
    board: AgentBoard,
    onBack: () -> Unit,
    onOpen: (AgentEntry) -> Unit,
    onPin: (AgentEntry, Boolean) -> Unit,
    showHeader: Boolean = true,
    current: AgentKey? = null,
    onAddHost: (() -> Unit)? = null,
    onOpenHosts: (() -> Unit)? = null,
) {
    val folds = rememberTreeFolds("agents")
    Column(Modifier.fillMaxSize()) {
        if (showHeader) ConsoleTopBar(stringResource(R.string.agents_title), onMenu = {}, onBack = onBack,
            showMark = false, inbox = {})
        LazyColumn(Modifier.weight(1f)) {
            if (board.hosts.isEmpty()) item {
                Text(stringResource(R.string.agents_no_hosts))
                onAddHost?.let { add ->
                    TextButton(onClick = add) { Text(stringResource(R.string.console_add_host)) }
                }
            }
            if (board.loadingHosts.isNotEmpty()) item {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.agents_loading_hosts, board.loadingHosts.size))
                }
            }
            items(board.hosts.filter { board.links[it.id]?.state != LinkState.Up }, key = { "connection:" + it.id }) { host ->
                board.links[host.id]?.let { ConnectionNotice(it) }
            }
            val agents = board.agents
            if (board.isEmpty) item {
                Text(stringResource(R.string.agents_empty))
                onOpenHosts?.let { open ->
                    TextButton(onClick = open) { Text(stringResource(R.string.agents_empty_hosts_button)) }
                }
            }
            agentTreeItems(agents, folds, onOpen, onPin, current = current)
        }
    }
}
