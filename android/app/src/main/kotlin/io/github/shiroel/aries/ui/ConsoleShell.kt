package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import kotlinx.coroutines.launch

/** Minimum bar/touch metrics shared by screen and full-screen tool headers. Text can grow. */
internal val ConsoleBarHeight = 48.dp
internal val LocalConsoleHeaderMeasured = compositionLocalOf<(Int) -> Unit> { {} }

@Composable
internal fun ConsoleBar(modifier: Modifier = Modifier, reportHeight: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val measured = LocalConsoleHeaderMeasured.current
    Row(modifier.fillMaxWidth().heightIn(min = ConsoleBarHeight).onSizeChanged { if (reportHeight) measured(it.height) },
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Shared header with room for enlarged text. Ticket 05 can supply Agent actions; ticket 04 owns [inbox]. */
@Composable
fun ConsoleTopBar(
    title: String,
    onMenu: () -> Unit,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    inbox: @Composable RowScope.() -> Unit = { ConsoleInboxAction() },
    showMark: Boolean = true,
    showNavigationActions: Boolean = true,
    reportHeight: Boolean = false,
    backEnabled: Boolean = true,
    backTag: String? = null,
    titleContent: @Composable RowScope.() -> Unit = {
        ConsoleTitle(title, Modifier.weight(1f))
    },
) {
    ConsoleBar(Modifier.testTag("console-top-bar"), reportHeight = reportHeight) {
        val navigationLabel = stringResource(if (onBack == null) R.string.console_open_drawer else R.string.back_button)
        Box(
            Modifier.size(48.dp).testTag(backTag ?: if (onBack == null) "console-menu" else "console-back")
                .clickable(enabled = backEnabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onBack ?: onMenu),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (onBack == null) R.drawable.ic_menu else R.drawable.ic_arrow_left),
                contentDescription = navigationLabel,
                modifier = Modifier.size(20.dp),
            )
        }
        if (showMark) ConsoleMark()
        titleContent()
        if (showNavigationActions) {
            QuickOpenButton()
            inbox()
        }
        actions()
    }
    HorizontalDivider()
}

@Composable
internal fun ConsoleTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
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
internal fun ConsoleShell(
    model: HostsModel,
    content: @Composable () -> Unit,
    drawerContent: (@Composable ((() -> Unit) -> Unit) -> Unit)? = null,
    onHome: (() -> Unit)? = null,
    isHome: (Screen) -> Boolean = { it == Screen.Hosts },
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val screen = model.screen
    CallAttentionEffect(model.calls)
    SeenAgentEffect(
        screen,
        enabled = drawer.isClosed && drawer.targetValue == DrawerValue.Closed &&
            !model.callInboxOpen && !model.quickOpenVisible && model.prompt == null,
    )
    val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp - 16.dp).coerceAtMost(420.dp)
    val density = LocalDensity.current
    val edgeWidth = with(density) { 24.dp.toPx() }
    var measuredHeaderHeight by remember { mutableIntStateOf(0) }
    val terminalHeaderHeight = measuredHeaderHeight.toFloat().takeIf { it > 0 } ?: with(density) { ConsoleBarHeight.toPx() }
    val swipeThreshold = with(density) { 48.dp.toPx() }
    val navigation = model.overlayNavigationVersion
    var observedNavigation by remember(model) { mutableLongStateOf(navigation) }
    var drawerOpenedAtNavigation by remember(model) { mutableLongStateOf(navigation) }
    LaunchedEffect(navigation) {
        if (observedNavigation != navigation) {
            observedNavigation = navigation
            // A freshly opened overlay belongs to this destination, even if
            // the previous destination's dismissal effect has not run yet.
            if (!drawer.isClosed && drawerOpenedAtNavigation != navigation) drawer.close()
            if (model.callInboxOpenedAtNavigation != navigation) model.closeCallInbox()
        }
    }
    LaunchedEffect(model.callInboxOpen) { if (model.callInboxOpen) drawer.close() }
    val navigate: (() -> Unit) -> Unit = { action ->
        scope.launch { try { drawer.close() } finally { action() } }
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
                if (drawer.isOpen || drawer.targetValue == DrawerValue.Open) {
                    if (drawerContent != null) drawerContent(navigate) else ConsoleDrawer(model, navigate)
                }
            }
        },
    ) {
        val openDrawer: () -> Unit = {
            model.interactWithHome()
            model.closeCallInbox()
            scope.launch { drawer.open() }
        }
        CompositionLocalProvider(LocalCallAttentionEnabled provides (drawer.isClosed && drawer.targetValue == DrawerValue.Closed),
            LocalConsoleHeaderMeasured provides { measuredHeaderHeight = it }, LocalConsoleMenu provides openDrawer, LocalQuickOpen provides {
            navigate(model::openQuickOpen)
        }, LocalCallInbox provides CallInboxAction(model.calls, model.callInboxOpen) {
            navigate { if (model.callInboxOpen) model.closeCallInbox() else model.openCallInbox() }
        }, LocalNextWaitingAgent provides model.nextWaitingAgent?.let { NextWaitingAction(it, model::openNextWaitingAgent) }) {
            Box(Modifier.fillMaxSize().systemGestureExclusion { coordinates ->
                Rect(0f, 0f, edgeWidth, minOf(coordinates.size.height.toFloat(), terminalHeaderHeight))
            }.pointerInput(screen, drawer.isClosed, model.callInboxOpen, model.quickOpenVisible, terminalHeaderHeight) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Observe at the parent so normal edge taps still reach header buttons.
                    // Reserve only the header edge, leaving list and canvas gestures alone.
                    if (!drawer.isClosed || model.callInboxOpen || model.quickOpenVisible ||
                        down.position.x > edgeWidth || down.position.y > terminalHeaderHeight) return@awaitEachGesture
                    var distance = 0f
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        if (over > 0) { change.consume(); distance = over }
                    }
                    if (drag != null && horizontalDrag(drag.id) { change ->
                            distance += change.position.x - change.previousPosition.x
                            change.consume()
                        } && distance > swipeThreshold) {
                        model.interactWithHome()
                        openDrawer()
                    }
                }
            }) {
                val inboxVisible = model.callInboxOpen && drawer.isClosed && drawer.targetValue == DrawerValue.Closed
                Box(Modifier.fillMaxSize().semantics { if (inboxVisible) hideFromAccessibility() }) { content() }
                if (inboxVisible) CallInboxOverlay(model)
            }
        }
    }
    if (model.quickOpenVisible) QuickOpenDialog(model)
    // One owner avoids screen handlers registered later taking precedence over overlays.
    BackHandler(drawer.isOpen || model.callInboxOpen || !isHome(screen)) {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else if (model.callInboxOpen) model.closeCallInbox()
        else if (onHome != null && screen !is Screen.Chat && screen !is Screen.Terminal && screen != Screen.Settings && screen !is Screen.JumpHosts) onHome()
        else consoleBack(model)?.invoke() ?: model.back()
    }
}

/** Pane headers stay with their list, including while an Agent occupies detail. */
internal val LocalConsoleMenu = compositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun ConsoleScreenHeader(model: HostsModel, screen: Screen, onMenu: () -> Unit) {
    if (screen is Screen.Terminal || screen is Screen.Chat) return
    val browser = when (screen) {
        is Screen.Browse -> screen.browser
        is Screen.Connecting -> screen.cachedBrowser
        else -> null
    }
    ConsoleTopBar(consoleTitle(screen), onMenu = onMenu, reportHeight = true,
        onBack = if (screen == Screen.Settings || screen is Screen.JumpHosts) model::settingsBack
            else if (screen is Screen.Connecting && browser == null) model::back else null,
        actions = {
            if (screen is Screen.Agents) AgentActions(screen.board)
            if (browser != null) HostActions(browser,
                onRunSaved = { model.runSaved(browser, it) }) { model.newTerminal(browser) }
        }, showNavigationActions = screen != Screen.Settings && screen !is Screen.JumpHosts,
        titleContent = {
            if (browser != null) HostContextTitle(browser, Modifier.weight(1f))
            else ConsoleTitle(consoleTitle(screen), Modifier.weight(1f))
        })
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
    Screen.Settings, is Screen.JumpHosts -> model::settingsBack
    is Screen.Terminal -> model::closeTerminal
    is Screen.Chat -> model::closeChat
    is Screen.Agents -> null
    is Screen.Browse -> null
    else -> model::back
}

@Composable
private fun ConsoleDrawer(model: HostsModel, navigate: (() -> Unit) -> Unit) {
    val agents = model.drawerAgents
    val folds = rememberTreeFolds("agents")
    val current = model.screen
    val currentAgent = when (current) {
        is Screen.Terminal -> current.viewer.link?.host?.id?.let { AgentKey(it, current.viewer.terminal.address) }
        is Screen.Chat -> AgentKey(current.chat.host.id, current.chat.terminal.address)
        else -> null
    }
    val hostId = when (current) {
        is Screen.Browse -> current.browser.host.id
        is Screen.Connecting -> current.host.id
        else -> null
    }
    LazyColumn {
        item {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ConsoleMark()
                Text(stringResource(R.string.app_name), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider()
            DrawerRow(
                stringResource(R.string.agents_button), current is Screen.Agents,
                onClick = { navigate(model::openAgents) },
            ) {
                WaitingSummary(model.quickOpenSources, agents)
            }
            DrawerRow(stringResource(R.string.calls_title), model.callInboxOpen, Modifier.testTag("drawer-calls"),
                onClick = { navigate(model::openCallInbox) })
            Text(stringResource(R.string.hosts_title), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        model.hosts.forEach { host ->
            item(key = "host:${host.id}") {
                DrawerRow(host.name, hostId == host.id, Modifier.testTag("drawer-host:${host.id}"), onClick = { navigate { model.openHost(host) } })
            }
            // The home board stays live behind Browse; selecting a Host scopes only this tree.
            val hostAgents = agents.filter { it.host.id == host.id && (hostId == null || hostId == host.id) }
            agentTreeItems(hostAgents, folds,
                onOpen = { entry -> navigate { model.openDrawerAgent(entry) } },
                onPin = model::pin, tag = "drawer-agent", current = currentAgent, sectionNamespace = host.id,
                sections = false, showHost = false, canPin = model::canPin)
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
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp).semantics { selected = current }.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (current) MaterialTheme.colorScheme.primary else Color.Transparent))
        Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
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
