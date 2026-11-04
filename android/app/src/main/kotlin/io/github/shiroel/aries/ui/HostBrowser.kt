package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.shiroel.aries.LaunchShortcuts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.SavedLaunch
import io.github.shiroel.aries.core.SpaceInfo
import io.github.shiroel.aries.core.StructureProblem
import io.github.shiroel.aries.core.TabInfo
import io.github.shiroel.aries.core.TerminalInfo

/**
 * One open Host's Space / Tab / Pane structure, as the desktop last wrote it,
 * plus where the phone is looking. The open Space, chosen Tab, focused Pane
 * and zoom live only here: nothing of it is sent to the Host, so browsing on
 * the phone never moves the desktop.
 */
class HostBrowser(val host: Host) {
    var layoutEditor: LayoutEditor? = null
    var structure by mutableStateOf<HostStructure?>(null)
        private set

    /** The connection ended; the structure shown may be stale. */
    var closed by mutableStateOf(false)
        private set

    /** The Host line; its state shows while it reconnects. */
    var link: HostLink? = null

    var openSpaceId by mutableStateOf<String?>(null)
        private set
    private val tabOfSpace = mutableStateMapOf<String, String>()
    private val focusOfTab = mutableStateMapOf<String, String>()
    private val zoomOfTab = mutableStateMapOf<String, String>()

    /** The latest facts per Terminal address, from the live Agent status watch. */
    private val live = mutableStateMapOf<String, TerminalInfo>()
    private var liveOrder by mutableStateOf<List<String>?>(null)

    /** A Terminal as the status watch last saw it (the structure may be older). */
    fun current(t: TerminalInfo): TerminalInfo = live[t.address] ?: t

    /** Whole-address lookup in the latest complete Terminal snapshot. */
    fun findTerminal(address: String): TerminalInfo? = terminals.find { it.address == address }

    fun replaceTerminal(terminal: TerminalInfo) { live[terminal.address] = terminal }

    fun updateTerminals(list: List<TerminalInfo>) {
        liveOrder = list.map { it.address }
        val addresses = list.map { it.address }.toSet()
        live.keys.retainAll(addresses)
        list.forEach { live[it.address] = it }
    }

    /** Structure bootstraps Browse until the status watch delivers its first list.
     * Thereafter even an empty live list replaces it; stale layout must not revive removed Terminals. */
    val terminals: List<TerminalInfo>
        get() = liveOrder?.mapNotNull(live::get) ?: structure?.let { snapshot ->
            snapshot.unplaced + snapshot.spaces.flatMap { space ->
                space.tabs.flatMap { tab -> tab.panes.flatMap { listOfNotNull(it.terminal) + it.subAgents } }
            }
        }.orEmpty().map(::current).distinctBy { it.address }

    /** Latest Agent snapshot for Browse and its drawer; no additional watch is opened. */
    val agents: List<AgentEntry>
        get() = orderAgents(withAgentSpaces(agentFamily(host, terminals), structure))

    /** The "new Terminal" form, while it is open. */
    var launchForm by mutableStateOf<LaunchForm?>(null)

    /** The Host's saved launch configs, each started with one tap. */
    var savedLaunches by mutableStateOf<List<SavedLaunch>>(emptyList())

    /** A just-launched Terminal to open once the structure lists it: (space, tab, address). */
    private var pendingReveal: Triple<String, String, String>? = null
    internal var revealRequest by mutableStateOf<Triple<String, String, String>?>(null)
        private set

    val openSpace: SpaceInfo? get() = structure?.spaces?.find { it.id == openSpaceId }

    val tab: TabInfo?
        get() = openSpace?.let { s -> s.tabs.find { it.id == tabOfSpace[s.id] } ?: s.tabs.firstOrNull() }

    fun focusedPane(tab: TabInfo): String? = focusOfTab[key(tab)]

    fun zoomedPane(tab: TabInfo): PaneInfo? = zoomOfTab[key(tab)]?.let { id -> tab.panes.find { it.id == id } }

    /** A new structure from the Host. View choices whose target is gone are dropped. */
    fun update(s: HostStructure) {
        structure = s
        if (openSpace == null) openSpaceId = null
        tabOfSpace.keys.retainAll { sid -> s.spaces.any { it.id == sid } }
        val panes = s.spaces.flatMap { sp -> sp.tabs.map { t -> "${sp.id}/${t.id}" to t.panes.map { it.id } } }.toMap()
        focusOfTab.entries.removeAll { (tab, pane) -> panes[tab]?.contains(pane) != true }
        zoomOfTab.entries.removeAll { (tab, pane) -> panes[tab]?.contains(pane) != true }
        tryReveal()
    }

    /**
     * Shows a Terminal just launched into [spaceId]/[tabId]: opens that Space
     * and Tab and focuses its Pane as soon as the structure lists it. Without
     * a Space it is unplaced, so the Space list (where those show) opens.
     */
    fun reveal(spaceId: String?, tabId: String?, address: String) {
        if (spaceId == null || tabId == null) {
            pendingReveal = null
            openSpaceId = null
            return
        }
        pendingReveal = Triple(spaceId, tabId, address)
        revealRequest = pendingReveal
        openSpaceId = spaceId
        tabOfSpace[spaceId] = tabId
        tryReveal()
    }

    private fun tryReveal() {
        val (spaceId, tabId, address) = pendingReveal ?: return
        val tab = structure?.spaces?.find { it.id == spaceId }?.tabs?.find { it.id == tabId } ?: return
        val pane = tab.panes.find { it.terminalAddress == address } ?: return
        focusOfTab["$spaceId/$tabId"] = pane.id
        pendingReveal = null
    }

    fun markClosed() {
        closed = true
    }

    fun openSpace(id: String) {
        openSpaceId = id
    }

    fun closeSpace() {
        openSpaceId = null
    }

    fun selectTab(tab: TabInfo) {
        openSpaceId?.let { tabOfSpace[it] = tab.id }
    }

    fun focus(tab: TabInfo, pane: PaneInfo) {
        focusOfTab[key(tab)] = pane.id
    }

    fun zoom(tab: TabInfo) {
        focusOfTab[key(tab)]?.let { zoomOfTab[key(tab)] = it }
    }

    fun unzoom(tab: TabInfo) {
        zoomOfTab.remove(key(tab))
    }

    private fun key(tab: TabInfo) = "$openSpaceId/${tab.id}"
}

fun displayName(t: TerminalInfo): String = t.name ?: t.title.ifBlank { t.argv.joinToString(" ") }

/** [hostSections] are per-Host panels (the Beacon) shown at the end of the Space list. */
@Composable
fun BrowseScreen(
    browser: HostBrowser,
    onBack: () -> Unit,
    onOpen: (TerminalInfo) -> Unit = {},
    onNewTerminal: (() -> Unit)? = null,
    onPin: (AgentEntry, Boolean) -> Unit = { _, _ -> },
    onLaunch: (LaunchForm) -> Unit = {},
    onSaveLaunch: (LaunchForm) -> Unit = {},
    onDeleteLaunch: (LaunchForm, SavedLaunch) -> Unit = { _, _ -> },
    onRunSaved: (SavedLaunch) -> Unit = {},
    hostSections: @Composable () -> Unit = {},
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val editor = rememberLayoutEditor(browser)
    Column(Modifier.fillMaxSize()) {
        if (showHeader) ConsoleTopBar(browser.host.name, onMenu = {}, onBack = onBack,
            showMark = false, inbox = {}, actions = { HostActions(browser, onNewTerminal) })
        LayoutEditControls(browser, editor)
        if (onNewTerminal != null && browser.structure != null && !browser.closed && browser.savedLaunches.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(browser.savedLaunches, key = { "saved:" + it.id }) { s ->
                    TextButton(modifier = Modifier.testTag("saved_run:" + s.id), onClick = { onRunSaved(s) }) {
                        Text(s.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        browser.launchForm?.let { form ->
            LaunchDialog(
                form,
                onLaunch = { onLaunch(form) },
                onDismiss = { browser.launchForm = null },
                onSave = { onSaveLaunch(form) },
                onDelete = { onDeleteLaunch(form, it) },
                onPin = if (LaunchShortcuts.canPin(context)) { saved -> LaunchShortcuts.pin(context, browser.host.id, saved) } else null,
            )
        }
        val line = browser.link?.state
        browser.link?.let { ConnectionNotice(it) }
        if (browser.closed && line == LinkState.Up) Notice(stringResource(R.string.structure_connection_closed))
        browser.structure?.problem?.let { Notice(problemText(it)) }
        when {
            browser.structure == null -> Text(stringResource(R.string.connecting, browser.host.name))
            else -> HostTreeList(browser, browser.structure!!, hostSections, onOpen, onPin, onNewTerminal)
        }
    }
}

@Composable
private fun problemText(p: StructureProblem): String = when (p) {
    StructureProblem.NoDocument -> stringResource(R.string.structure_no_document)
    StructureProblem.UnknownFormat -> stringResource(R.string.structure_unknown_format)
    is StructureProblem.Unreadable -> stringResource(R.string.structure_unreadable, p.detail)
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun HostTreeList(
    browser: HostBrowser,
    structure: HostStructure,
    hostSections: @Composable () -> Unit,
    onOpen: (TerminalInfo) -> Unit,
    onPin: (AgentEntry, Boolean) -> Unit,
    onNewTerminal: (() -> Unit)?,
) {
    val folds = rememberTreeFolds("host:${browser.host.id}")
    val agentFolds = rememberTreeFolds("agents")
    val agents = browser.agents
    val family = agents.map { it.key.address }.toSet()
    val plain = withAgentSpaces(browser.terminals.filterNot { it.address in family }
        .mapIndexed { index, terminal -> AgentEntry(browser.host, terminal, sequence = index) }, structure)
    val plainKey = "plain-terminals"
    androidx.compose.runtime.LaunchedEffect(browser.revealRequest) {
        if (browser.revealRequest != null) folds.expand(plainKey)
    }
    val actions: @Composable ColumnScope.(AgentEntry, () -> Unit) -> Unit = { entry, close ->
        // Selecting context here retains Space/Tab edits without adding tree levels.
        if (entry.space != null) ActionMenuItem(stringResource(R.string.menu_switch_context), R.drawable.ic_panels, onClick = {
            selectTerminalContext(browser, entry.terminal.address)
            close()
        })
        if (entry.space == null) WorktreeControls(browser, entry.terminal, asMenuItem = true)
        browser.layoutEditor?.let { EndTerminalButton(it, entry.terminal, close) }
    }
    LazyColumn {
        if (browser.terminals.isEmpty()) item {
            Text(stringResource(R.string.terminals_empty))
            onNewTerminal?.let { launch ->
                TextButton(onClick = launch, enabled = browser.link?.state == LinkState.Up && !browser.closed) {
                    Text(stringResource(R.string.launch_open_button))
                }
            }
        }
        agentTreeItems(agents, agentFolds, { onOpen(it.terminal) }, onPin, tag = "host-agent",
            sectionNamespace = "all", actions = actions)
        if (plain.isNotEmpty()) {
            item {
                TreeSectionRow(stringResource(R.string.terminals_plain_group, plain.size), 0, folds.contains(plainKey),
                    { folds.toggle(plainKey) }, Modifier.testTag("host-plain-terminals"))
            }
            if (!folds.contains(plainKey)) agentTreeItems(orderAgents(plain), agentFolds,
                { onOpen(it.terminal) }, onPin, tag = "host-terminal", sections = false, actions = actions)
        }
        val missing = structure.spaces.flatMap { it.tabs }.flatMap { it.panes }.filter { it.terminal == null }
        items(missing, key = { "gone:${it.id}" }) { pane ->
            Text(stringResource(R.string.pane_terminal_gone, pane.terminalAddress), Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        item { Column(Modifier.padding(horizontal = 12.dp)) { hostSections() } }
    }
}

private fun selectTerminalContext(browser: HostBrowser, address: String) {
    browser.structure?.spaces?.forEach { space ->
        space.tabs.firstOrNull { tab -> tab.panes.any { pane ->
            pane.terminalAddress == address || pane.subAgents.any { it.address == address }
        } }?.let { tab ->
            browser.openSpace(space.id)
            browser.selectTab(tab)
            tab.panes.firstOrNull { pane -> pane.terminalAddress == address || pane.subAgents.any { it.address == address } }
                ?.let { browser.focus(tab, it) }
            return
        }
    }
}
