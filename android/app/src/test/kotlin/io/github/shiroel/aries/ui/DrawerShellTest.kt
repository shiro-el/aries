package io.github.shiroel.aries.ui

import androidx.compose.ui.test.hasText

import androidx.compose.ui.test.hasAnyDescendant

import androidx.compose.ui.test.assert

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: navigate through the visible app shell against the core API boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class DrawerShellTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun drawerNavigatesToAgentsHostSettingsAndBackToHosts() {
        val core = FakeCore().apply { addHost("home", Endpoint.Ssh("home.test", 22u, "me")) }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNodeWithText(context.getString(R.string.agents_button)).performClick()
        compose.onNodeWithTag("console-drawer").assertIsNotDisplayed()
        compose.onNodeWithText(context.getString(R.string.agents_empty)).assertExists()
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNodeWithTag("drawer-host:h1").performClick()
        compose.onNodeWithTag("console-top-bar").assert(hasAnyDescendant(hasText("home")))
        val connection = core.connection!!
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNodeWithText(context.getString(R.string.theme_title)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.back_button)).performClick()
        // Settings returns to the retained Host; Hosts is a separate drawer destination.
        compose.onNodeWithTag("console-top-bar").assert(hasAnyDescendant(hasText("home")))
        compose.runOnIdle {
            assertSame(connection, core.connection)
            assertFalse(connection.disconnected)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-hosts").performClick()
        compose.onNodeWithText("me@home.test").assertExists()
        compose.runOnIdle { assertTrue(connection.disconnected) }
    }

    @Test fun liveAgentTreeCountsWaitingAndOpensTheCorrectHostsTerminal() {
        fun agent(name: String, status: AgentStatus) = TerminalInfo("t1", name, "", listOf(name), "/repo", "live", status)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            terminalsOf["h1"] = listOf(agent("home-agent", AgentStatus.Working))
            terminalsOf["h2"] = listOf(agent("work-agent", AgentStatus.Error("problem")))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.agents_button)).performClick()
        compose.openConsoleDrawer()
        compose.onNode(hasText(context.getString(R.string.agents_button)) and
            hasText(context.getString(R.string.console_agents_waiting, 1)) and
            hasAnyAncestor(hasTestTag("console-drawer"))).assertExists()
        compose.onNodeWithTag("drawer-agent:h1:t1").assertExists()
        compose.onNodeWithTag("drawer-agent:h2:t1").assertExists()
        core.push("h1", listOf(agent("home-agent", AgentStatus.Error("also waiting"))))
        compose.onNode(hasText(context.getString(R.string.agents_button)) and
            hasText(context.getString(R.string.console_agents_waiting, 2)) and
            hasAnyAncestor(hasTestTag("console-drawer"))).assertExists()
        compose.onNode(hasTestTag("agent-pin:h2:t1") and hasAnyAncestor(hasTestTag("console-drawer"))).performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("h2 t1 pinned=true"), core.labelWrites)
        compose.onNode(hasTestTag("agent-pin:h2:t1") and hasAnyAncestor(hasTestTag("console-drawer"))).assertIsSelected()
        compose.onNodeWithTag("drawer-agent:h2:t1").performClick()
        compose.onNode(hasTestTag("console-top-bar") and hasAnyDescendant(hasText("work-agent"))).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.back_button)).performClick()
        compose.onNodeWithTag("agent-row:h2:t1").assertExists()
        compose.onNodeWithTag("agent-row:h1:t1").assertExists()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-host:h1").performClick()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-host:h1").assertIsSelected()
        compose.onNodeWithTag("drawer-agent:h1:t1").assertExists()
        compose.onNodeWithTag("drawer-agent:h2:t1").assertDoesNotExist()
    }

    @Test fun leavingConnectingDoesNotLetALateConnectionReplaceTheDrawerDestination() {
        val core = FakeCore().apply { addHost("home", Endpoint.Ssh("home.test", 22u, "me")) }
        val ready = CompletableDeferred<Unit>()
        val delayed = object : AriesCoreInterface by core {
            override suspend fun connect(hostId: String): HostConnection {
                ready.await()
                return core.connect(hostId)
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(delayed, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        // Connecting offers Back; return to Hosts, then choose Settings.
        compose.onNodeWithContentDescription(context.getString(R.string.back_button)).performClick()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.runOnIdle { ready.complete(Unit) }
        compose.onNodeWithText(context.getString(R.string.theme_title)).assertExists()
    }

    @Test fun backClosesTheDrawerBeforeDisconnectingTheHost() {
        val core = FakeCore().apply { addHost("home", Endpoint.Ssh("home.test", 22u, "me")) }
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.openConsoleDrawer()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("console-drawer").assertIsNotDisplayed()
        compose.onNodeWithTag("console-top-bar").assert(hasAnyDescendant(hasText("home")))
        compose.runOnIdle { org.junit.Assert.assertFalse(core.connection!!.disconnected) }
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("me@home.test").assertExists()
        compose.runOnIdle { org.junit.Assert.assertTrue(core.connection!!.disconnected) }
    }
}
