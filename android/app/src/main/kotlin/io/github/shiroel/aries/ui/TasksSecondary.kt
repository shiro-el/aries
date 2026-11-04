package io.github.shiroel.aries.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

/** Presentation only: shared shell still owns overlay, navigation and connection lifetimes. */
@Composable
internal fun TasksSecondaryHeader(model: HostsModel, screen: Screen) {
    val hosts = LocalTasksHosts.current
    val back = if (screen == Screen.Settings || screen is Screen.JumpHosts) model::settingsBack else hosts
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("tasks-secondary-header"),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back, modifier = Modifier.testTag("console-back")) {
            Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.back_button), Modifier.size(20.dp))
        }
        if (screen is Screen.Browse) HostContextTitle(screen.browser, Modifier.weight(1f))
        else {
            val title = when (screen) {
                Screen.Settings -> model.settingsHost?.name ?: stringResource(when {
                    model.settingsKeys -> R.string.settings_keys_open
                    model.settingsDiagnostics -> R.string.diagnostics_title
                    else -> R.string.settings_title
                })
                is Screen.Connecting -> screen.host.name
                is Screen.JumpHosts -> stringResource(R.string.jumps_title, screen.host.name)
                is Screen.Agents -> stringResource(R.string.tasks_home)
                else -> stringResource(R.string.hosts_title)
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        }
        if (screen is Screen.Browse) {
            IconButton(onClick = { model.openHostSettings(screen.browser.host) },
                modifier = Modifier.testTag("tasks-host-settings")) {
                Icon(painterResource(R.drawable.file_type_settings), stringResource(R.string.settings_hosts_title), Modifier.size(20.dp))
            }
            HostActions(screen.browser, onRunSaved = { model.runSaved(screen.browser, it) },
                onNewTerminal = { model.newTerminal(screen.browser) })
        }
    }
    HorizontalDivider()
    if (screen == model.screen) CallAttentionSlot()
}

/** Host content uses the same task rows as Home, grouped by actual Space membership. */
@Composable
internal fun TasksHostContent(model: HostsModel, browser: HostBrowser, hostSections: @Composable () -> Unit = {}) {
    val editor = rememberLayoutEditor(browser)
    val agents = browser.agents
    val family = agents.map { it.terminal.address }.toSet()
    val plain = browser.terminals.filterNot { it.address in family }
    val enabled = browser.link?.state == LinkState.Up && !browser.closed
    val start = LocalStartTask.current
    LazyColumn(Modifier.fillMaxSize().testTag("tasks-host-content")) {
        item(key = "tools") { TasksHostTools(browser) }
        if (browser.terminals.isEmpty()) item { Text(stringResource(R.string.terminals_empty), Modifier.padding(16.dp)) }
        agents.groupBy { it.space }.forEach { (space, entries) ->
            item(key = "space:${space?.id}") {
                Text(space?.name ?: stringResource(R.string.unplaced_title), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(entries, key = { "agent:${it.terminal.address}" }) { entry ->
                var menu by remember(entry.key) { mutableStateOf(false) }
                Box {
                    TasksTaskRow(entry, onLongClick = { menu = true }, showHost = false, showSpace = false) { model.selectListedTerminal(browser, entry.terminal) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        ActionMenuItem(stringResource(if (entry.terminal.pinned) R.string.menu_unpin else R.string.menu_pin),
                            R.drawable.agent_pin, enabled = enabled && entry.live,
                            onClick = { menu = false; model.pin(entry, !entry.terminal.pinned) })
                        WorktreeControls(browser, entry.terminal, asMenuItem = true)
                        EndTerminalButton(editor, entry.terminal) { menu = false }
                    }
                }
            }
        }
        if (plain.isNotEmpty()) {
            item { Text(stringResource(R.string.terminals_plain_group, plain.size), Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(plain, key = { "terminal:${it.address}" }) { terminal ->
                var menu by remember(terminal.address) { mutableStateOf(false) }
                Box {
                    ConsoleTerminalRow(terminal, browser.host.name, { model.selectListedTerminal(browser, terminal) },
                        showHost = false, live = browser.hasLiveTerminals, lastUpdatedMs = browser.lastUpdatedMs,
                        onLongClick = { menu = true })
                    DropdownMenu(menu, onDismissRequest = { menu = false }) { EndTerminalButton(editor, terminal) { menu = false } }
                }
                HorizontalDivider()
            }
        }
        item(key = "new") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { hostSections() }
            start?.let { action ->
                TextButton(onClick = { action(null) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_task_on_host, browser.host.name))
                }
            }
            if (model.beacon.ui == BeaconUi.Known(BeaconState.NotInstalled)) TextButton(
                onClick = { model.openHostSettings(browser.host) }, modifier = Modifier.testTag("browse-host-settings:${browser.host.id}")) {
                Text(stringResource(R.string.settings_beacon_missing))
            }
        }
    }
}

/** Tools pin the selected Terminal for the duration of a panel; no new connection or task. */
@Composable
private fun TasksHostTools(browser: HostBrowser) {
    val link = browser.link
    val pane = browser.tab?.let { tab -> tab.panes.find { it.id == browser.focusedPane(tab) } ?: tab.panes.firstOrNull() }
    val selected = pane?.terminalAddress?.let(browser::findTerminal) ?: browser.terminals.firstOrNull { !it.address.contains('/') }
    var tool by remember(browser) { mutableStateOf<String?>(null) }
    var terminal by remember(browser) { mutableStateOf<TerminalInfo?>(null) }
    val enabled = selected != null && link?.state == LinkState.Up && !browser.closed
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("files", R.string.files_title, R.drawable.ic_folder),
            Triple("github", R.string.github_button, R.drawable.file_type_git),
            Triple("preview", R.string.preview_title, R.drawable.ic_browser_preview)).forEach { (id, label, icon) ->
            Surface(onClick = { terminal = selected; tool = id },
                enabled = enabled && (id != "preview" || link?.host?.endpoint is Endpoint.Ssh),
                modifier = Modifier.weight(1f).testTag("tasks-host-$id"), shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(icon), null, Modifier.size(22.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    val target = terminal ?: return
    if (tool == "github") GithubDialog(link, target) { tool = null }
    if (tool == "files") Dialog(onDismissRequest = { tool = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val connection = link?.connections?.collectAsState()?.value
            RemoteFilesScreen(connection, target.address, onClose = { tool = null })
        }
    }
    if (tool == "preview") Dialog(onDismissRequest = { tool = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val preview = rememberLocalhostPreview(link, "", target.address)
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.preview_title), Modifier.weight(1f).padding(16.dp), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(R.drawable.ic_x, R.string.preview_close, { tool = null })
                }
                HorizontalDivider()
                if (preview.ports.isEmpty()) Text(stringResource(R.string.preview_no_servers), Modifier.padding(16.dp))
                // Select a detected port explicitly; never open a guessed endpoint.
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    preview.ports.forEach { port ->
                        FilterChip(selected = preview.selected == port, onClick = { preview.selected = port },
                            label = { Text(stringResource(R.string.preview_open_port, port.toInt())) })
                    }
                }
                LocalhostPreviewBrowser(preview, link, Modifier.weight(1f), captureEnabled = false)
            }
        }
    }
}
