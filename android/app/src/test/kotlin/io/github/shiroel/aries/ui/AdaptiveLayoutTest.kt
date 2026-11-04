package io.github.shiroel.aries.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: selecting from a visible list must replace detail without leaving the live list. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1000dp-h1000dp")
class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun foldingKeepsTheSameTerminalDraftScrollbackAndLiveStream() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(TerminalInfo("t1", "shell", "", listOf("sh"), "/repo", "live", null))
        }
        var width by mutableStateOf(1000.dp)
        var height by mutableStateOf(1000.dp)
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            Box(Modifier.requiredWidth(width).requiredHeight(height)) { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithTag("host-terminal:h1:t1").performClick()
        val terminal = model.screen
        val connection = core.connection!!
        val view = connection.views.single()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        view.push(TerminalUpdate.Screen(ScreenFrame(80u, 24u, true, emptyList(), null, null, 42u, 500u)))
        compose.onNodeWithContentDescription(context.getString(R.string.terminal_compose_button)).performClick()
        compose.onNodeWithTag("terminal-composer").performTextReplacement("unsent\n한국어 draft")
        compose.runOnIdle { width = 411.dp }
        compose.onNodeWithTag("host-terminal:h1:t1").assertDoesNotExist()
        compose.onNodeWithTag("terminal-composer").assertTextEquals("unsent\n한국어 draft")
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.terminal_scrolled_back, 42, 42)).assertExists()
        compose.runOnIdle { width = 900.dp; height = 360.dp }
        compose.onNodeWithTag("host-terminal:h1:t1").assertDoesNotExist()
        compose.onNodeWithTag("terminal-composer").assertTextEquals("unsent\n한국어 draft")
        compose.runOnIdle { width = 700.dp; height = 1000.dp }
        compose.onNodeWithTag("host-terminal:h1:t1").assertIsSelected()
        compose.onNodeWithTag("terminal-composer").assertTextEquals("unsent\n한국어 draft")
        view.push(TerminalUpdate.Screen(ScreenFrame(80u, 24u, true, emptyList(), null, null, 43u, 501u)))
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.terminal_scrolled_back, 43, 43)).assertExists()
        compose.runOnIdle {
            assertSame(terminal, model.screen)
            assertEquals(listOf("h1"), core.connectAttempts)
            assertEquals(1, connection.views.size)
            assertFalse(view.detached)
            assertTrue(view.sent.isEmpty())
        }
    }

    @Test fun hostCreationAndNavigationRemainUsableWhileDetailIsOpen() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(TerminalInfo("t1", "shell", "", listOf("sh"), "/repo", "live", null))
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithTag("host-terminal:h1:t1").performClick()
        val terminal = model.screen
        compose.onNodeWithContentDescription(context.getString(R.string.host_create_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.launch_open_button)).performClick()
        compose.runOnIdle {
            assertSame(terminal, model.screen)
            assertNotNull(model.activeLaunch)
            model.dismissLaunch()
        }
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNodeWithTag("settings-open").performClick()
        compose.runOnIdle { assertEquals(Screen.Settings, model.screen) }
    }

    @Test fun wideListReplacesDetailAndBackDeselectsBeforeLeavingTheList() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf("t1", "t2").map {
                TerminalInfo(it, "Agent $it", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
            }
        }
        lateinit var model: HostsModel
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.runOnIdle { model.openAgents() }
        compose.onNodeWithTag("agent-row:h1:t1").performClick()
        val list = (model.screen as Screen.Terminal).returnTo
        val connection = core.connection!!
        val previous = connection.views.single()
        // This second selection must work while the first Terminal is still open.
        compose.onNodeWithTag("agent-row:h1:t2").performClick()
        compose.runOnIdle {
            assertEquals(AgentKey("h1", "t2"), model.currentAgentKey)
            assertSame(list, (model.screen as Screen.Terminal).returnTo)
            assertTrue(previous.detached)
            assertFalse(connection.disconnected)
        }
        compose.onNodeWithTag("agent-row:h1:t2").assertIsSelected()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertSame(list, model.screen)
            assertNull(model.currentAgentKey)
            assertFalse(connection.disconnected)
            assertTrue(connection.views.last().detached)
        }
        compose.onNodeWithTag("agent-row:h1:t2").assertIsNotSelected()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(Screen.Hosts, model.screen)
            assertTrue(connection.disconnected)
        }
    }
}
