package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host

/** The only layout implementation selector. Unimplemented layouts use the working Console UI.
 * Add a LayoutScreens subclass here as each layout ships; override only its UI entry points.
 * HostsModel owns navigation, connections, conversation drafts and task operations above this seam.
 * Never key a model, lifecycle effect or saveable-state owner by AppLayout.
 */
internal fun layoutScreensFor(layout: AppLayout): LayoutScreens = when (layout) {
    AppLayout.CONSOLE -> ConsoleScreens
    AppLayout.THREAD -> ThreadScreens
    AppLayout.TASKS -> TasksScreens
}

internal val LocalLayoutScreens = staticCompositionLocalOf<LayoutScreens> { ConsoleScreens }
private object ConsoleScreens : LayoutScreens()

/** Console defaults are reusable individually, without duplicating model or navigation logic. */
internal open class LayoutScreens {
    open val usesListPane: Boolean = true
    @Composable open fun Shell(model: HostsModel, content: @Composable () -> Unit) = ConsoleShell(model, content)

    @Composable open fun Header(model: HostsModel, screen: Screen) {
        ConsoleScreenHeader(model, screen, LocalConsoleMenu.current ?: {})
        if (screen == model.screen && screen !is Screen.Chat && screen !is Screen.Terminal) CallAttentionSlot()
    }

    @Composable fun Screen(model: HostsModel, settings: ThemeSettings, display: TerminalDisplaySettings, screen: Screen) {
        when (screen) {
            Screen.Hosts -> Hosts(model)
            Screen.Settings -> Settings(model, settings, display)
            is Screen.Connecting -> Connecting(model, screen)
            is Screen.Browse -> Home(model, screen.browser)
            is Screen.Agents -> Agents(model, screen.board)
            is Screen.Chat -> Chat(model, screen.chat)
            is Screen.Terminal -> Terminal(model, screen.viewer, display)
            is Screen.JumpHosts -> JumpHosts(model, screen)
        }
    }

    @Composable open fun Hosts(model: HostsModel) {
        Column(Modifier.fillMaxSize()) {
            ConsoleHomeAttention(model)
            Box(Modifier.weight(1f)) { HostListScreen(model) }
        }
    }

    @Composable open fun Settings(model: HostsModel, settings: ThemeSettings, display: TerminalDisplaySettings) {
        SettingsScreen(model, settings, onBack = model::settingsBack, showHeader = false,
            terminalDisplay = display,
            hostEditor = { host -> if (host.endpoint is Endpoint.Ssh) HostEditSection(model, host) })
    }

    @Composable open fun HostDetail(model: HostsModel, host: Host, editor: @Composable (Host) -> Unit) =
        HostSettingsScreen(model, host, editor)

    @Composable open fun Connecting(model: HostsModel, screen: Screen.Connecting) {
        val authorization: @Composable () -> Unit = {
            if ((screen.link.state as? LinkState.Down)?.error is CoreException.AuthRejected && screen.host.endpoint is Endpoint.Ssh) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostKeyAuthorization(model, screen.host)
                }
            }
        }
        val cached = screen.cachedBrowser
        if (cached != null) {
            BrowseScreen(cached, onBack = model::back, showHeader = false, hostSections = authorization)
        } else Column(if (screen.link.state == LinkState.Reconnecting) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ConnectionNotice(screen.link, firstLoad = true)
            authorization()
        }
    }

    @Composable open fun Home(model: HostsModel, browser: HostBrowser) {
        Column(Modifier.fillMaxSize()) {
            ConsoleHomeAttention(model, browser.host.id)
            Box(Modifier.weight(1f)) {
                BrowseScreen(browser, showHeader = false, onBack = model::back,
                    onOpen = { model.selectListedTerminal(browser, it) }, current = model.currentAgentKey,
                    onPin = model::pin, onNewTerminal = { model.newTerminal(browser) },
                    onRunSaved = { model.runSaved(browser, it) }, hostSections = {
                        if (model.beacon.ui == BeaconUi.Known(BeaconState.NotInstalled)) {
                            TextButton(onClick = { model.openHostSettings(browser.host) },
                                modifier = Modifier.testTag("browse-host-settings:${browser.host.id}")) {
                                Text(stringResource(R.string.settings_beacon_missing), maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                            }
                        }
                    })
            }
        }
    }

    @Composable open fun Agents(model: HostsModel, board: AgentBoard) {
        Column {
            if (model.pendingShare != null) {
                Text(stringResource(R.string.attachments_choose_agent), Modifier.padding(12.dp))
                TextButton(onClick = model::cancelShare) { Text(stringResource(R.string.cancel_button)) }
            }
            AgentsScreen(board, showHeader = false, onBack = model::back,
                onOpen = { model.selectListedAgent(board, it) }, current = model.currentAgentKey,
                onPin = { entry, pinned -> model.pin(board, entry, pinned) },
                onAddHost = { model.startAddingHost() }, onOpenHosts = model::back)
        }
    }

    @Composable open fun Chat(model: HostsModel, chat: ChatModel) {
        ChatScreen(chat, onBack = model::closeChat, onOpenTerminal = { model.openTerminal(chat.terminal) },
            onView = model::switchView, calls = model.calls)
    }

    @Composable open fun Terminal(model: HostsModel, viewer: TerminalViewer, display: TerminalDisplaySettings) {
        val context = LocalContext.current.applicationContext
        TerminalScreen(viewer, onBack = model::closeTerminal,
            onView = model::switchView.takeIf { viewer.terminal.agentStatus != null }, displaySettings = display,
            onSelectionSend = model::sendTerminalSelectionToDraft,
            onPreviewCapture = { content, cleanup -> model.attachPreviewToChat(context, content, cleanup) })
    }

    @Composable open fun JumpHosts(model: HostsModel, screen: Screen.JumpHosts) =
        JumpHostsScreen(model, screen.host, screen.jumps, screen.hostKeyId, showHeader = false)

    @Composable open fun NewTask(task: NewTask, onStart: () -> Unit, onOpenChat: () -> Unit, onDismiss: () -> Unit) =
        NewTaskDialog(task, onStart, onOpenChat, onDismiss)
}
