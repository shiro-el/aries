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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.PaneInfo
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
    var structure by mutableStateOf<HostStructure?>(null)
        private set

    /** The connection ended; the structure shown may be stale. */
    var closed by mutableStateOf(false)
        private set

    var openSpaceId by mutableStateOf<String?>(null)
        private set
    private val tabOfSpace = mutableStateMapOf<String, String>()
    private val focusOfTab = mutableStateMapOf<String, String>()
    private val zoomOfTab = mutableStateMapOf<String, String>()

    /** The latest facts per Terminal address, from the live Agent status watch. */
    private val live = mutableStateMapOf<String, TerminalInfo>()

    /** A Terminal as the status watch last saw it (the structure may be older). */
    fun current(t: TerminalInfo): TerminalInfo = live[t.address] ?: t

    fun updateTerminals(list: List<TerminalInfo>) {
        val addresses = list.map { it.address }.toSet()
        live.keys.retainAll(addresses)
        list.forEach { live[it.address] = it }
    }

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
    onOpenChat: (TerminalInfo) -> Unit = {},
    hostSections: @Composable () -> Unit = {},
) {
    val space = browser.openSpace
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            TextButton(onClick = if (space != null) browser::closeSpace else onBack) {
                Text(stringResource(R.string.back_button))
            }
        }
        if (browser.closed) Notice(stringResource(R.string.structure_connection_closed))
        browser.structure?.problem?.let { Notice(problemText(it)) }
        when {
            browser.structure == null -> Text(stringResource(R.string.connecting, browser.host.name))
            space == null -> SpaceList(browser, browser.structure!!, onOpenChat, hostSections)
            else -> SpaceView(browser, space, onOpenChat)
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
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SpaceList(
    browser: HostBrowser,
    s: HostStructure,
    onOpenChat: (TerminalInfo) -> Unit,
    hostSections: @Composable () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.terminals_title, browser.host.name), style = MaterialTheme.typography.headlineSmall)
        }
        if (s.spaces.isEmpty() && s.unplaced.isEmpty()) item { Text(stringResource(R.string.terminals_empty)) }
        items(s.spaces, key = { "space:" + it.id }) { space ->
            Column(Modifier.fillMaxWidth().clickable { browser.openSpace(space.id) }.padding(vertical = 8.dp)) {
                Text(space.name.ifBlank { stringResource(R.string.space_unnamed) }, style = MaterialTheme.typography.titleMedium)
                val count = space.tabs.sumOf { it.panes.size }
                Text(
                    listOfNotNull(space.folder, stringResource(R.string.space_summary, space.tabs.size, count))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (s.unplaced.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(stringResource(R.string.unplaced_title), style = MaterialTheme.typography.titleMedium)
            }
            items(s.unplaced, key = { "unplaced:" + it.address }) { TerminalRow(browser.current(it), onOpenChat) }
        }
        item {
            HorizontalDivider()
            hostSections()
        }
    }
}

@Composable
private fun SpaceView(browser: HostBrowser, space: SpaceInfo, onOpenChat: (TerminalInfo) -> Unit) {
    Text(space.name.ifBlank { stringResource(R.string.space_unnamed) }, style = MaterialTheme.typography.headlineSmall)
    val tab = browser.tab
    if (tab == null) {
        Text(stringResource(R.string.space_no_tabs))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(space.tabs, key = { it.id }) { t ->
            FilterChip(
                selected = t.id == tab.id,
                onClick = { browser.selectTab(t) },
                label = { Text(t.name.ifBlank { stringResource(R.string.tab_unnamed) }) },
            )
        }
    }
    val zoomed = browser.zoomedPane(tab)
    val focused = browser.focusedPane(tab)
    if (zoomed != null) {
        TextButton(onClick = { browser.unzoom(tab) }) { Text(stringResource(R.string.pane_unzoom_button)) }
        PaneRow(zoomed, browser, focused = true, onOpenChat) {}
        return
    }
    if (focused != null) {
        TextButton(onClick = { browser.zoom(tab) }) { Text(stringResource(R.string.pane_zoom_button)) }
    }
    if (tab.panes.isEmpty()) Text(stringResource(R.string.tab_empty))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(tab.panes, key = { "pane:" + it.id + ":" + it.terminalAddress }) { pane ->
            PaneRow(pane, browser, focused = pane.id == focused, onOpenChat) { browser.focus(tab, pane) }
        }
    }
}

@Composable
private fun PaneRow(
    pane: PaneInfo,
    browser: HostBrowser,
    focused: Boolean,
    onOpenChat: (TerminalInfo) -> Unit,
    onClick: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    Column(
        (if (focused) modifier.background(MaterialTheme.colorScheme.secondaryContainer) else modifier).padding(8.dp),
    ) {
        val t = pane.terminal?.let(browser::current)
        if (t == null) {
            Text(stringResource(R.string.pane_terminal_gone, pane.terminalAddress), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(displayName(t), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.terminal_subtitle, t.address, t.state), style = MaterialTheme.typography.bodySmall)
            AgentStatusLine(t)
            ChatButton(t, onOpenChat)
        }
    }
}

@Composable
private fun TerminalRow(t: TerminalInfo, onOpenChat: (TerminalInfo) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(displayName(t), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.terminal_subtitle, t.address, t.state), style = MaterialTheme.typography.bodySmall)
        AgentStatusLine(t)
        ChatButton(t, onOpenChat)
    }
}

/** Agents (Terminals that report Agent status) have a Chat. */
@Composable
private fun ChatButton(t: TerminalInfo, onOpenChat: (TerminalInfo) -> Unit) {
    if (t.agentStatus == null) return
    val label = stringResource(R.string.chat_open_label, displayName(t))
    TextButton(onClick = { onOpenChat(t) }, modifier = Modifier.semantics { contentDescription = label }) {
        Text(stringResource(R.string.chat_open_button))
    }
}
