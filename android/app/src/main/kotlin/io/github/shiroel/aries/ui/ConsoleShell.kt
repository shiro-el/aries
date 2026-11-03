package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import kotlinx.coroutines.launch

/** Shared 44dp header. Ticket 05 can supply Agent actions; ticket 04 owns [inbox]. */
@Composable
fun ConsoleTopBar(
    title: String,
    onMenu: () -> Unit,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    inbox: @Composable RowScope.() -> Unit = { ConsoleInboxAction() },
    showMark: Boolean = true,
    titleContent: @Composable RowScope.() -> Unit = {
        Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
) {
    Row(Modifier.fillMaxWidth().height(44.dp).testTag("console-top-bar"), verticalAlignment = Alignment.CenterVertically) {
        val navigationLabel = stringResource(if (onBack == null) R.string.console_open_drawer else R.string.back_button)
        Box(
            Modifier.size(44.dp).testTag(if (onBack == null) "console-menu" else "console-back")
                .clickable(onClick = onBack ?: onMenu).semantics { contentDescription = navigationLabel },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(if (onBack == null) R.string.console_menu_glyph else R.string.console_back_glyph), Modifier.clearAndSetSemantics { text = AnnotatedString(navigationLabel) }, fontSize = 20.sp) }
        if (showMark) ConsoleMark()
        titleContent()
        actions()
        inbox()
    }
    HorizontalDivider()
}

@Composable
private fun ConsoleMark() {
    Image(
        painterResource(if (MaterialTheme.colorScheme.background.luminance() < .5f) R.drawable.mark_light else R.drawable.mark_ink),
        contentDescription = null,
        modifier = Modifier.padding(end = 8.dp).size(22.dp),
    )
}

/** Modal drawer owns navigation only; entering a destination goes through model cleanup. */
@Composable
internal fun ConsoleShell(model: HostsModel, content: @Composable () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val screen = model.screen
    val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp * .86f).coerceAtMost(320.dp)
    LaunchedEffect(screen) { drawer.close(); model.closeCallInbox() }
    LaunchedEffect(model.callInboxOpen) { if (model.callInboxOpen) drawer.close() }
    val navigate: (() -> Unit) -> Unit = { action ->
        scope.launch { drawer.close(); action() }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(drawerWidth).testTag("console-drawer"),
                drawerShape = RectangleShape,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                if (drawer.isOpen || drawer.targetValue == DrawerValue.Open) ConsoleDrawer(model, navigate)
            }
        },
    ) {
        CompositionLocalProvider(LocalCallInbox provides CallInboxAction(model.calls, model.callInboxOpen) {
            scope.launch {
                drawer.close()
                if (model.callInboxOpen) model.closeCallInbox() else model.openCallInbox()
            }
        }) {
            Column(Modifier.fillMaxSize()) {
                // Agent screens supply their own ConsoleTopBar in ticket 05.
                val agentHeader = screen is Screen.Terminal || screen is Screen.Chat
                if (!agentHeader) {
                    ConsoleTopBar(consoleTitle(screen), onMenu = {
                        model.closeCallInbox()
                        scope.launch { drawer.open() }
                    }, onBack = consoleBack(model))
                }
                Box(Modifier.weight(1f)) {
                    content()
                    if (model.callInboxOpen && drawer.isClosed && drawer.targetValue == DrawerValue.Closed) {
                        CallInboxOverlay(model, Modifier.padding(top = if (agentHeader) 45.dp else 0.dp))
                    }
                }
            }
        }
    }
    // One owner avoids screen handlers registered later taking precedence over overlays.
    BackHandler(drawer.isOpen || model.callInboxOpen || screen != Screen.Hosts) {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else if (model.callInboxOpen) model.closeCallInbox()
        else consoleBack(model)?.invoke() ?: model.back()
    }
}

@Composable
private fun consoleTitle(screen: Screen): String = when (screen) {
    Screen.Hosts -> stringResource(R.string.hosts_title)
    Screen.Settings -> stringResource(R.string.settings_title)
    is Screen.Connecting -> screen.host.name
    is Screen.Browse -> screen.browser.host.name
    is Screen.JumpHosts -> stringResource(R.string.jumps_title, screen.host.name)
    is Screen.Agents -> stringResource(R.string.agents_title)
    is Screen.Terminal -> displayName(screen.viewer.terminal)
    is Screen.Chat -> displayName(screen.chat.terminal)
}

private fun consoleBack(model: HostsModel): (() -> Unit)? = when (val screen = model.screen) {
    Screen.Hosts -> null
    is Screen.Terminal -> model::closeTerminal
    is Screen.Chat -> model::closeChat
    is Screen.Agents -> if (screen.board.openKey != null) screen.board::closeDetail else null
    is Screen.Browse -> if (screen.browser.openSpaceId != null) screen.browser::closeSpace else null
    else -> model::back
}

@Composable
private fun ConsoleDrawer(model: HostsModel, navigate: (() -> Unit) -> Unit) {
    val agents = model.drawerAgents
    val waiting = agents.count { isWaiting(it.terminal.agentStatus) }
    val current = model.screen
    val hostId = when (current) {
        is Screen.Browse -> current.browser.host.id
        is Screen.Connecting -> current.host.id
        else -> null
    }
    LazyColumn {
        item {
            Row(Modifier.height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                ConsoleMark()
                Text(stringResource(R.string.app_name), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider()
            DrawerRow(
                stringResource(R.string.agents_button), current is Screen.Agents && current.board.openKey == null,
                onClick = { navigate(model::openAgents) },
            ) {
                Text(stringResource(R.string.console_agents_waiting, waiting), fontSize = 12.sp,
                    color = if (waiting > 0) statusColor(AgentStatus.Blocked(null, null)) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.hosts_title), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        model.hosts.forEach { host ->
            item(key = "host:${host.id}") {
                DrawerRow(host.name, hostId == host.id, Modifier.testTag("drawer-host:${host.id}"), onClick = { navigate { model.openHost(host) } })
            }
            val children = agents.filter { it.host.id == host.id }
            children.forEachIndexed { index, entry ->
                item(key = "agent:${entry.key.hostId}\n${entry.key.address}") {
                    DrawerRow(
                        displayName(entry.terminal), (current as? Screen.Agents)?.board?.openKey == entry.key,
                        Modifier.testTag("drawer-agent:${entry.key.hostId}:${entry.key.address}"),
                        guide = stringResource(if (index == children.lastIndex) R.string.console_tree_last else R.string.console_tree_branch),
                        onClick = { navigate { model.openDrawerAgent(entry) } },
                    ) { DrawerAgentStatus(entry.terminal.agentStatus) }
                }
            }
        }
        item {
            DrawerRow(stringResource(R.string.console_all_hosts), current == Screen.Hosts, Modifier.testTag("drawer-hosts"), onClick = { navigate(model::back) })
            HorizontalDivider()
            DrawerRow(stringResource(R.string.settings_title), current == Screen.Settings, Modifier.testTag("settings-open"), onClick = { navigate(model::openSettings) })
        }
    }
}

@Composable
private fun DrawerRow(
    title: String,
    current: Boolean,
    modifier: Modifier = Modifier,
    guide: String? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { selected = current }.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(44.dp).background(if (current) MaterialTheme.colorScheme.primary else Color.Transparent))
        Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            guide?.let { Text(it, Modifier.padding(end = 8.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } // i18n-ok: decorative tree guides
            Text(title, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing()
        }
    }
}

/** Drawer seam for the shared Console status mark. */
@Composable
internal fun DrawerAgentStatus(status: AgentStatus?) {
    status ?: return
    AgentStatusMark(status)
}
