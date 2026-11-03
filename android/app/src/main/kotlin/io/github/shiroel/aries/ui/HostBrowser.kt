package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
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
class HostBrowser(val host: Host, val showBeaconFirst: Boolean = false) {
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

    /** A Terminal as the status watch last saw it (the structure may be older). */
    fun current(t: TerminalInfo): TerminalInfo = live[t.address] ?: t

    /** Whole-address lookup, preferring the live watch over the Browse structure. */
    fun findTerminal(address: String): TerminalInfo? = live[address] ?: structure?.let { snapshot ->
        (snapshot.unplaced + snapshot.spaces.flatMap { space ->
            space.tabs.flatMap { tab -> tab.panes.mapNotNull { it.terminal } }
        }).find { it.address == address }
    }

    fun updateTerminals(list: List<TerminalInfo>) {
        val addresses = list.map { it.address }.toSet()
        live.keys.retainAll(addresses)
        list.forEach { live[it.address] = it }
    }

    /** Latest Agent snapshot for the navigation drawer; no additional watch is opened. */
    val agents: List<AgentEntry>
        get() = live.values.filter { it.agentStatus != null }.sortedBy { it.address }.map { AgentEntry(host, it) }

    /** The "new Terminal" form, while it is open. */
    var launchForm by mutableStateOf<LaunchForm?>(null)

    /** The Host's saved launch configs, each started with one tap. */
    var savedLaunches by mutableStateOf<List<SavedLaunch>>(emptyList())

    /** A just-launched Terminal to open once the structure lists it: (space, tab, address). */
    private var pendingReveal: Triple<String, String, String>? = null

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
    onLaunch: (LaunchForm) -> Unit = {},
    onSaveLaunch: (LaunchForm) -> Unit = {},
    onDeleteLaunch: (LaunchForm, SavedLaunch) -> Unit = { _, _ -> },
    onRunSaved: (SavedLaunch) -> Unit = {},
    hostSections: @Composable () -> Unit = {},
    showHeader: Boolean = true,
) {
    val space = browser.openSpace
    Column(Modifier.fillMaxSize()) {
        Row {
            if (showHeader) TextButton(onClick = if (space != null) browser::closeSpace else onBack) {
                Text(stringResource(R.string.back_button))
            }
            if (onNewTerminal != null && browser.structure != null && !browser.closed) {
                TextButton(onClick = onNewTerminal) { Text(stringResource(R.string.launch_open_button)) }
            }
        }
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
            )
        }
        val line = browser.link?.state
        if (line == LinkState.Reconnecting) Notice(stringResource(R.string.reconnecting))
        if (browser.closed || line is LinkState.Down) Notice(stringResource(R.string.structure_connection_closed))
        browser.structure?.problem?.let { Notice(problemText(it)) }
        when {
            browser.structure == null -> Text(stringResource(R.string.connecting, browser.host.name))
            space == null -> SpaceList(browser, browser.structure!!, hostSections, onOpen)
            else -> SpaceView(browser, space, onOpen)
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
private fun SpaceList(
    browser: HostBrowser,
    s: HostStructure,
    hostSections: @Composable () -> Unit,
    onOpen: (TerminalInfo) -> Unit,
) {
    LazyColumn {
        item {
            Text(stringResource(R.string.terminals_title, browser.host.name), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (browser.showBeaconFirst) item {
            Column(Modifier.padding(horizontal = 12.dp)) { hostSections() }
            HorizontalDivider()
        }
        if (s.spaces.isEmpty() && s.unplaced.isEmpty()) item { Text(stringResource(R.string.terminals_empty)) }
        items(s.spaces, key = { "space:" + it.id }) { space ->
            Row(Modifier.fillMaxWidth().clickable { browser.openSpace(space.id) }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("▸", Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) // i18n-ok: decorative disclosure mark
                Column(Modifier.weight(1f)) {
                    Text(space.name.ifBlank { stringResource(R.string.space_unnamed) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val count = space.tabs.sumOf { it.panes.size }
                    Text(
                        listOfNotNull(space.folder, stringResource(R.string.space_summary, space.tabs.size, count))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (s.unplaced.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(stringResource(R.string.unplaced_title), Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(s.unplaced, key = { "unplaced:" + it.address }) {
                val t = browser.current(it)
                ConsoleTerminalRow(t, browser.host.name, { onOpen(t) })
            }
        }
        if (!browser.showBeaconFirst) item {
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 12.dp)) { hostSections() }
        }
    }
}

@Composable
private fun SpaceView(
    browser: HostBrowser,
    space: SpaceInfo,
    onOpen: (TerminalInfo) -> Unit,
) {
    Text(space.name.ifBlank { stringResource(R.string.space_unnamed) }, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
    val tab = browser.tab
    if (tab == null) {
        Text(stringResource(R.string.space_no_tabs))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(space.tabs, key = { it.id }) { t ->
            ConsoleChoice(selected = t.id == tab.id, onClick = { browser.selectTab(t) }) {
                Text(t.name.ifBlank { stringResource(R.string.tab_unnamed) }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val zoomed = browser.zoomedPane(tab)
    val focused = browser.focusedPane(tab)
    if (zoomed != null) {
        TextButton(onClick = { browser.unzoom(tab) }) { Text(stringResource(R.string.pane_unzoom_button)) }
        PaneRow(zoomed, browser, focused = true, onOpen = onOpen) {}
        return
    }
    if (focused != null) {
        TextButton(onClick = { browser.zoom(tab) }) { Text(stringResource(R.string.pane_zoom_button)) }
    }
    if (tab.panes.isEmpty()) Text(stringResource(R.string.tab_empty))
    LazyColumn {
        itemsIndexed(tab.panes, key = { _, pane -> "pane:" + pane.id + ":" + pane.terminalAddress }) { index, pane ->
            PaneRow(pane, browser, focused = pane.id == focused, onOpen = onOpen, last = index == tab.panes.lastIndex) { browser.focus(tab, pane) }
        }
    }
}

@Composable
private fun PaneRow(
    pane: PaneInfo,
    browser: HostBrowser,
    focused: Boolean,
    onOpen: (TerminalInfo) -> Unit,
    last: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val modifier = Modifier.fillMaxWidth().semantics { selected = focused }
        .background(if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
        .drawBehind { if (focused) drawLine(accent, Offset.Zero, Offset(0f, size.height), 3.dp.toPx()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(if (last) "└─" else "├─", Modifier.padding(start = 12.dp).width(24.dp), style = MaterialTheme.typography.bodySmall, // i18n-ok: tree guides
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val t = pane.terminal?.let(browser::current)
        if (t == null) {
            Text(stringResource(R.string.pane_terminal_gone, pane.terminalAddress),
                Modifier.clickable(onClick = onClick).padding(12.dp), style = MaterialTheme.typography.bodySmall)
        } else {
            Column(Modifier.weight(1f)) {
                ConsoleTerminalRow(t, browser.host.name, onClick, trailing = {
                    val open = if (t.agentStatus != null) R.string.agent_open_button else R.string.terminal_open_button
                    TextButton(onClick = { onOpen(t) }) { Text(stringResource(open), style = MaterialTheme.typography.bodySmall) }
                })
            }
        }
    }
}
