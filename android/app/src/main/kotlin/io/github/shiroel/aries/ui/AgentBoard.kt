package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.SavedLaunch
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
    val live: Boolean = true,
    val lastUpdatedMs: Long? = null,
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

    val savedLaunches = mutableStateMapOf<String, List<SavedLaunch>>()

    private val updatedAt = mutableStateMapOf<String, Long>()
    private val refreshing = mutableStateMapOf<String, Boolean>()
    val watchErrors = mutableStateMapOf<String, CoreException>()
    private val observedConnections = mutableMapOf<String, HostConnection?>()

    private fun isLive(hostId: String): Boolean = (links[hostId]?.state ?: LinkState.Up) == LinkState.Up &&
        hostId !in watchErrors && refreshing[hostId] != true &&
        (links[hostId] == null || observedConnections[hostId] === links[hostId]?.connection)

    /** Hosts that could not be reached, with why. */
    val unavailable = mutableStateMapOf<String, Message>()

    /** Hosts whose connection ended after their list arrived. */
    val closed = mutableStateMapOf<String, Boolean>()

    /** Each reached Host's line, by Host id; its state shows while it reconnects. */
    val links = mutableStateMapOf<String, HostLink>()

    /** A connected socket is still loading until its first complete Terminal list arrives. */
    val loadingHosts: List<Host>
        get() = hosts.filter { (it.id !in lists || !isLive(it.id)) && it.id !in watchErrors && it.id !in unavailable && closed[it.id] != true && links[it.id]?.state !is LinkState.Down }

    val isEmpty: Boolean
        get() = hosts.isNotEmpty() && hosts.all { it.id in lists && isLive(it.id) } && unavailable.isEmpty() &&
            closed.values.none { it } && watchErrors.isEmpty() && links.values.none { it.state != LinkState.Up } && agents.isEmpty()

    /** Shared ordering, including descendant urgency and global pins. */
    val agents: List<AgentEntry>
        get() = orderAgents(hosts.flatMapIndexed { index, h ->
            withAgentSpaces(agentFamily(h, lists[h.id].orEmpty()), structures[h.id]).map { it.copy(hostOrder = index, live = isLive(h.id), lastUpdatedMs = updatedAt[h.id]) }
        }, view)

    /** Reuses the existing watches; an empty live list must replace stored Agents. */
    val quickOpenSources: List<QuickOpenSource>
        get() = hosts.filter { isLive(it.id) && it.id in lists }.map {
            QuickOpenSource(it, structures[it.id], lists[it.id].orEmpty())
        }

    fun updateStructure(hostId: String, structure: HostStructure) {
        structures[hostId] = structure
    }

    /** Stored data stays visibly stale until the first fresh Terminal list replaces it. */
    internal fun seed(state: io.github.shiroel.aries.core.LastKnownState) {
        if (hosts.none { it.id == state.hostId }) return
        lists[state.hostId] = state.agents
        state.structure?.let { structures[state.hostId] = it }
        updatedAt[state.hostId] = state.fetchedAtMs.toLong()
        refreshing[state.hostId] = true
    }

    /** Latest layout for retaining an Agent's navigation ancestors on the phone. */
    fun structureOf(hostId: String): HostStructure? = structures[hostId]

    fun find(key: AgentKey): AgentEntry? =
        hosts.find { it.id == key.hostId }?.let { h ->
            lists[h.id]?.find { it.address == key.address }?.let { AgentEntry(h, it, live = isLive(h.id), lastUpdatedMs = updatedAt[h.id]) }
        }

    fun update(hostId: String, list: List<TerminalInfo>) {
        unavailable.remove(hostId)
        closed.remove(hostId)
        watchErrors.remove(hostId)
        refreshing[hostId] = false
        updatedAt[hostId] = System.currentTimeMillis()
        observedConnections[hostId] = links[hostId]?.connection
        lists[hostId] = list
    }

    /** One Terminal changed (the reply of a label write), ahead of the watch. */
    fun replace(hostId: String, t: TerminalInfo) {
        lists[hostId]?.let { list -> lists[hostId] = list.map { if (it.address == t.address) t else it } }
    }

    fun starting(hostId: String) {
        watchErrors.remove(hostId)
        refreshing[hostId] = true
    }

    fun fail(hostId: String, error: CoreException) {
        watchErrors[hostId] = error
    }

    fun fail(hostId: String, why: Message) {
        unavailable[hostId] = why
    }

    fun markClosed(hostId: String) {
        closed[hostId] = true
    }

}

/** Notification entry may intentionally connect just one Host; make that scope explicit. */
@Composable
internal fun AgentHomeScope(model: HostsModel, board: AgentBoard) {
    val host = board.hosts.singleOrNull()?.takeIf { board.hosts != model.hosts } ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.agents_host_scope, host.name), Modifier.weight(1f),
            maxLines = accessibleNameLines(), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        TextButton(onClick = model::openAgents, modifier = Modifier.testTag("agents-show-all")) {
            Text(stringResource(R.string.agents_show_all))
        }
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
    val agents = board.agents
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val presentation = rememberAgentTreePresentation(agents, listState.isScrollInProgress)
    Column(Modifier.fillMaxSize()) {
        if (showHeader) ConsoleTopBar(stringResource(R.string.agents_title), onMenu = {}, onBack = onBack,
            showMark = false, inbox = {}, actions = { AgentActions(board) })
        LazyColumn(Modifier.weight(1f).then(presentation.modifier), state = listState) {
            if (board.hosts.isEmpty()) item {
                Text(stringResource(R.string.agents_no_hosts))
                onAddHost?.let { add ->
                    TextButton(onClick = add) { Text(stringResource(R.string.console_add_host)) }
                }
            }
            val connecting = board.hosts.filter {
                it in board.loadingHosts || board.links[it.id]?.state == LinkState.Reconnecting
            }
            if (connecting.isNotEmpty()) item(key = "connecting-hosts") {
                ScreenLoading(stringResource(R.string.connect_host_reconnecting, connecting.joinToString(", ") { it.name }),
                    compact = true, labelDelayMillis = if (board.agents.isEmpty()) 0L else 1_500L,
                    onRetry = { connecting.forEach { host ->
                        board.links[host.id]?.let { link ->
                            if (link.state == LinkState.Up) link.networkChanged() else link.retryNow()
                        }
                    } })
            }
            if (board.isEmpty) item {
                Text(stringResource(R.string.agents_empty))
                onOpenHosts?.let { open ->
                    TextButton(onClick = open) { Text(stringResource(R.string.agents_empty_hosts_button)) }
                }
            }
            agentTreeItems(agents, folds, onOpen, onPin, current = current, presentation = presentation.groups,
                canPin = { board.links[it.host.id]?.let { link -> link.state == LinkState.Up && link.connection != null } == true })
            items(board.hosts.filter { board.links[it.id]?.state is LinkState.Down || it.id in board.watchErrors },
                key = { "failure:" + it.id }) { host ->
                board.links[host.id]?.let { link ->
                    val down = link.state as? LinkState.Down
                    if (down != null) CompactHostFailure(link, down.error, stopped = false)
                    else board.watchErrors[host.id]?.let { WatchFailureNotice(link, it) }
                }
            }
        }
    }
}
