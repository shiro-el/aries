package io.github.shiroel.aries.ui

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.PushTarget
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D103 at the UI/core seam: a fresh owner receives only Android's saved IDs. */
@RunWith(RobolectricTestRunner::class)
class SettingsNavigationRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val generation = mutableIntStateOf(0)
    private var saved: Bundle? = null
    private var target: PushTarget? = null
    private lateinit var model: HostsModel
    private val terminal = TerminalInfo("box/t1", "agent", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private val core = FakeCore().apply {
        addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
        terminals = listOf(terminal)
        structure = HostStructure(listOf(SpaceInfo("s1", "repo", "/repo", listOf(
            TabInfo("tab1", "first", listOf(PaneInfo("p1", terminal.address, terminal))),
        ))), emptyList(), null)
        savedView = AgentView.CHAT
    }

    private fun show(api: AriesCoreInterface = core) {
        compose.setContent {
            key(generation.intValue) {
                val scope = rememberCoroutineScope()
                model = remember { HostsModel(api, scope).also { it.restoreSettingsState(saved) } }
                LaunchedEffect(model) { model.start(target) }
            }
        }
        compose.waitForIdle()
    }

    private fun restart(push: PushTarget? = null) {
        compose.runOnIdle {
            saved = model.saveSettingsState()
            target = push
            generation.intValue++
        }
        compose.waitForIdle()
    }

    // Regression: process death must preserve Host/jump subpages and the Chat -> Tab Back path;
    // the return connection must wait for normal SSH authentication instead of reusing stale owners.
    @Test fun hostSubpageAndJumpsKeepTheirChatReturnPathThroughAuthentication() {
        var authorization: CompletableDeferred<Unit>? = null
        val api = object : AriesCoreInterface by core {
            override suspend fun connect(hostId: String): HostConnection {
                authorization?.await()
                return core.connect(hostId)
            }
        }
        show(api)
        compose.runOnIdle { model.connect(model.hosts.single()) }
        compose.waitForIdle()
        compose.runOnIdle {
            model.openTerminal(terminal)
            model.openHostSettings(model.hosts.single())
            model.editJumps(model.hosts.single())
        }
        restart()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.JumpHosts)
            assertEquals("h1", model.settingsHost?.id)
            model.settingsBack()
            assertEquals(Screen.Settings, model.screen)
            model.settingsBack()
            assertNull(model.settingsHost)
            authorization = CompletableDeferred()
            model.settingsBack()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.screen is Screen.Connecting) }
        compose.runOnIdle { authorization!!.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("box/t1", (model.screen as Screen.Chat).chat.terminal.address)
            model.closeChat()
            assertEquals("tab1", (model.screen as Screen.Browse).browser.tab?.id)
        }
    }

    // Regression: keys and diagnostics must restore their own subpage rather than top-level Settings.
    @Test fun keyAndDiagnosticsSubpagesSurviveNewOwners() {
        show()
        for (keys in listOf(true, false)) {
            compose.runOnIdle { if (keys) model.openKeySettings() else model.openDiagnostics() }
            restart()
            compose.runOnIdle {
                assertEquals(Screen.Settings, model.screen)
                assertEquals(keys, model.settingsKeys)
                assertEquals(!keys, model.settingsDiagnostics)
                model.settingsBack()
                model.settingsBack()
                assertEquals(Screen.Hosts, model.screen)
            }
        }
    }

    // Regression: a fresh notification has precedence over the saved Settings path.
    @Test fun notificationSupersedesSavedSettings() {
        show()
        compose.runOnIdle { model.openKeySettings() }
        restart(PushTarget("h1", terminal.address, chat = true))
        compose.runOnIdle { assertEquals(terminal.address, (model.screen as Screen.Chat).chat.terminal.address) }
    }
    // Regression: a detail opened over Chat must retain both Back steps after process death.
    @Test fun aTerminalOpenedOverChatKeepsBothReturnSteps() {
        show()
        compose.runOnIdle { model.connect(model.hosts.single()) }
        compose.waitForIdle()
        compose.runOnIdle {
            model.openTerminal(terminal)
            model.openTerminal(terminal)
            model.openKeySettings()
        }
        restart()
        compose.runOnIdle { model.settingsBack(); model.settingsBack() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Terminal)
            model.closeTerminal()
            assertTrue(model.screen is Screen.Chat)
            model.closeChat()
            assertTrue(model.screen is Screen.Browse)
        }
    }

    // Regression: restoring an Agent-list origin must not change Back to Host Browse or the default view.
    @Test fun settingsReturnsToTheSameScopedAgentListAndSavedChatView() {
        core.addHost("other", Endpoint.Ssh("other.test", 22u, "me"))
        show()
        compose.runOnIdle { model.openPushedAgent("h1", terminal.address, openChat = true) }
        compose.waitForIdle()
        compose.runOnIdle { model.openKeySettings(); core.setAgentView(AgentView.TERMINAL) }
        restart()
        compose.runOnIdle { model.settingsBack(); model.settingsBack() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Chat)
            model.closeChat()
            assertEquals(listOf("h1"), (model.screen as Screen.Agents).board.hosts.map { it.id })
        }
    }

    // D116: a fresh Settings owner must return to the home selected after process restoration.
    @Test fun restoredSettingsRemapsHomeInBothLayoutDirections() {
        show()
        compose.runOnIdle { model.openSettings() }
        restart()
        compose.runOnIdle {
            model.homeLayoutChanged(AppLayout.CONSOLE, AppLayout.THREAD)
            model.settingsBack()
            assertTrue(model.screen is Screen.Agents)
            model.openSettings()
        }
        restart()
        compose.runOnIdle {
            model.homeLayoutChanged(AppLayout.THREAD, AppLayout.CONSOLE)
            model.settingsBack()
            assertEquals(Screen.Hosts, model.screen)
        }
    }

    // D124: process restoration must keep the Add Host method and the saved Host's authorization step.
    @Test fun addHostProgressSurvivesSavedStateRestoration() {
        show()
        compose.runOnIdle { model.startAddingHost(HostAddMethod.QR) }
        restart()
        compose.runOnIdle {
            assertEquals(HostAddMethod.QR, model.hostAddMethod)
            assertEquals(Screen.Hosts, model.screen)
            model.startAddingHost(HostAddMethod.MANUAL)
            model.addHost("work", "work.test", 22, "me")
        }
        restart()
        compose.runOnIdle {
            assertEquals(HostAddMethod.MANUAL, model.hostAddMethod)
            assertEquals("work", model.addedHost?.name)
            assertEquals(model.addedHost?.id, model.setupHostId)
        }
    }

}
