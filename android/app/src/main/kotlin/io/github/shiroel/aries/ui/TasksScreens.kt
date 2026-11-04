package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Tasks presentation reuses the shared navigation and operation owners. */
internal object TasksScreens : LayoutScreens() {
    @Composable override fun Shell(model: HostsModel, content: @Composable () -> Unit) = TasksShell(model, content)
    @Composable override fun Agents(model: HostsModel, board: AgentBoard) = TasksHome(model, board)
    @Composable override fun Hosts(model: HostsModel) = HostListScreen(model, tasks = true)
    @Composable override fun Home(model: HostsModel, browser: HostBrowser) =
        BrowseScreen(browser, onBack = model::back, showHeader = false,
            terminalList = { TasksHostContent(model, browser) })
    @Composable override fun Connecting(model: HostsModel, screen: Screen.Connecting) {
        val cached = screen.cachedBrowser
        if (cached == null) super.Connecting(model, screen)
        else BrowseScreen(cached, onBack = model::back, showHeader = false,
            terminalList = { TasksHostContent(model, cached) {
                if ((screen.link.state as? LinkState.Down)?.error is io.github.shiroel.aries.core.CoreException.AuthRejected &&
                    screen.host.endpoint is io.github.shiroel.aries.core.Endpoint.Ssh) HostKeyAuthorization(model, screen.host)
            } })
    }
    @Composable override fun NewTask(task: NewTask, onStart: () -> Unit, onOpenChat: () -> Unit, onDismiss: () -> Unit) =
        NewTaskDialog(task, onStart, onOpenChat, onDismiss, fullScreen = true)
}

internal val LocalTasksHosts = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
private fun TasksShell(model: HostsModel, content: @Composable () -> Unit) {
    var hostsOpen by rememberSaveable { mutableStateOf(false) }
    val screen = model.screen
    // A Tasks home is the shared multi-Host Agent board. Explicit Host management stays reachable.
    LaunchedEffect(model, screen, model.started) {
        if (model.started && model.screen == Screen.Hosts && !hostsOpen && model.hostAddMethod == null) model.openAgents()
    }
    CompositionLocalProvider(LocalTasksHosts provides { hostsOpen = true; model.back() }) {
        if (screen is Screen.Agents || screen == Screen.Hosts) {
            CallAttentionEffect(model.calls)
            Column(Modifier.fillMaxSize().semantics { if (model.callInboxOpen) hideFromAccessibility() }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (screen == Screen.Hosts) IconButton(onClick = { hostsOpen = false; model.openAgents() }) {
                        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.tasks_home))
                    }
                    if (screen == Screen.Hosts) {
                        Text(stringResource(R.string.hosts_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            maxLines = accessibleNameLines(), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    } else {
                        Image(painterResource(if (MaterialTheme.colorScheme.background.luminance() < .5f)
                            R.drawable.mark_light else R.drawable.mark_ink), null, Modifier.size(28.dp))
                        Text(stringResource(R.string.app_name), Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val board = (screen as? Screen.Agents)?.board
                        val connected = model.hosts.count { board?.links?.get(it.id)?.state == LinkState.Up }
                        val hostsLabel = stringResource(R.string.tasks_hosts_count, connected, model.hosts.size)
                        Row(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("tasks-hosts")
                            .clickable(role = Role.Button) { hostsOpen = true; model.back() }
                            .semantics { contentDescription = hostsLabel }
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            model.hosts.take(4).forEach { host ->
                                val live = board?.links?.get(host.id)?.state == LinkState.Up
                                Box(Modifier.size(7.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .then(if (live) Modifier.background(statusColor(io.github.shiroel.aries.core.AgentStatus.Done), CircleShape) else Modifier))
                            }
                            if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f) {
                                Text(hostsLabel, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        ActionIcon(R.drawable.ic_chat_view, R.string.calls_title, model::openCallInbox,
                            modifier = Modifier.testTag("tasks-inbox"))
                    }
                    IconButton(onClick = model::openSettings, modifier = Modifier.testTag("settings-open")) {
                        Icon(painterResource(R.drawable.file_type_settings), stringResource(R.string.settings_title), Modifier.size(20.dp))
                    }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f)) { content() }
            }
            if (model.callInboxOpen) CallInboxOverlay(model)
            if (model.quickOpenVisible) QuickOpenDialog(model)
            BackHandler(screen is Screen.Agents || hostsOpen) {
                if (model.callInboxOpen) model.closeCallInbox()
                else if (hostsOpen) { hostsOpen = false; model.openAgents() }
                else { hostsOpen = true; model.back() }
            }
        } else if (screen is Screen.Chat || screen is Screen.Terminal) ConsoleShell(model, content)
        else ConsoleShell(model, content, secondaryHeader = { TasksSecondaryHeader(model) })
    }
}
