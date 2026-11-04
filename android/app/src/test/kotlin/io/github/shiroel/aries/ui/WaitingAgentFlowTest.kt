package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.LastKnownState
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.robolectric.annotation.GraphicsMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaitingAgentFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun waitsForEveryHostsFirstListBeforeShowingAnEmptyState() {
        val hosts = listOf(
            Host("h1", "home", Endpoint.Ssh("home.test", 22u, "me"), null),
            Host("h2", "work", Endpoint.Ssh("work.test", 22u, "me"), null),
        )
        val board = AgentBoard(hosts)
        compose.setContent { MaterialTheme { AgentsScreen(board, {}, {}, { _, _ -> }) } }
        val empty = context.getString(R.string.agents_empty)
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle { board.update("h1", emptyList()) }
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle { board.update("h2", emptyList()) }
        compose.onNodeWithText(empty).assertExists()
    }

    private fun agent(address: String, name: String, status: AgentStatus, parent: String? = null, pinned: Boolean = false, ms: ULong = 0uL) =
        TerminalInfo(address, name, "", listOf("claude"), "/repo", "live", agentStatus = status, parent = parent, pinned = pinned, statusMs = ms)

    private fun show(core: FakeCore): () -> HostsModel {
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        return { model }
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_WAITING_RENDER_DIR")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun nextWaitingUsesSharedFamilyOrderAndKeepsTheOriginalListOnBack() {
        val current = agent("t1", "Current", AgentStatus.Blocked(null, null), ms = 100uL)
        val newer = agent("remote/t1", "Newer error", AgentStatus.Error(null), ms = 500uL)
        val parent = agent("t9", "Pinned parent", AgentStatus.Working, pinned = true)
        val child = agent("remote/t1", "Blocked child", AgentStatus.Blocked(null, null), parent = "t9")
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            terminalsOf["h1"] = listOf(current, newer)
            terminalsOf["h2"] = listOf(parent, child)
        }
        val model = show(core)
        compose.runOnIdle { model().openAgents() }
        compose.onNodeWithTag("agent-row:h1:t1").performClick()
        val board = (model().screen as Screen.Terminal).returnTo
        val oldView = core.connectionsOf.getValue("h1").views.single()
        val nextChild = context.getString(R.string.agents_next_waiting, "Blocked child", "work")
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(nextChild).performClick()
        compose.runOnIdle {
            val screen = model().screen as Screen.Terminal
            assertEquals(AgentKey("h2", "remote/t1"), model().currentAgentKey)
            assertSame(board, screen.returnTo)
            assertTrue(oldView.detached)
            assertFalse(core.connectionsOf.getValue("h1").disconnected)
        }
        screenshot("terminal-next")
        compose.onNodeWithTag("console-inbox").assertDoesNotExist()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithTag("agent-inbox-menu").performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.onNodeWithTag("call-inbox-scrim").performClick()
        core.push("h2", listOf(parent, child.copy(agentStatus = AgentStatus.Working)))
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.agents_next_waiting, "Newer error", "home")).performClick()
        compose.runOnIdle { assertEquals(AgentKey("h1", "remote/t1"), model().currentAgentKey) }
        compose.onNodeWithContentDescription(context.getString(R.string.agent_switch_chat_label)).performClick()
        compose.onNodeWithTag("console-inbox").assertDoesNotExist()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithTag("agent-inbox-menu").performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.onNodeWithTag("call-inbox-scrim").performClick()
        screenshot("chat-next")
        compose.onNodeWithTag("console-back").performClick()
        compose.runOnIdle { assertSame(board, model().screen) }
        compose.onNodeWithTag("agent-row:h1:t1").assertExists()
        compose.onNodeWithTag("agent-row:h2:t9").assertExists()
    }

    @Test fun nextWaitingNeverOffersAStoredAgentFromADisconnectedHost() {
        val waiting = agent("remote/t1", "Remote question", AgentStatus.Blocked(null, null))
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            terminalsOf["h1"] = listOf(agent("t1", "Current", AgentStatus.Working))
            terminalsOf["h2"] = listOf(waiting)
            storedStates["h2"] = LastKnownState("h2", listOf(waiting), System.currentTimeMillis().toULong())
        }
        val model = show(core)
        compose.onNodeWithText("home").performClick()
        val browse = model().screen
        val home = core.connectionsOf.getValue("h1")
        compose.onNodeWithText("Current").performClick()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.agents_next_waiting, "Remote question", "work")).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(model().nextWaitingAgent)
            assertEquals(AgentKey("h1", "t1"), model().currentAgentKey)
            assertFalse(home.disconnected)
            assertFalse(core.connectionsOf.containsKey("h2"))
            assertSame(browse, (model().screen as Screen.Terminal).returnTo)
        }
    }

    @Test fun homeAndDrawerUseStoredCountsUntilACompleteLiveListReplacesThem() {
        val parent = agent("t1", "Parent", AgentStatus.Working)
        val child = agent("t2", "Child", AgentStatus.Blocked(null, null), parent = "t1")
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            storedStates["h1"] = LastKnownState("h1", listOf(parent, child), System.currentTimeMillis().toULong() - 120_000uL)
            terminalsOf["h1"] = emptyList()
            terminalsOf["h2"] = listOf(agent("t3", "Error", AgentStatus.Error(null)))
        }
        val model = show(core)
        compose.onNodeWithTag("host-waiting:h1").assert(hasText(context.getString(R.string.console_agents_waiting, 1)))
        compose.onNodeWithTag("host-waiting:h2").assert(hasText(context.getString(R.string.agents_waiting_unknown)))
        screenshot("home-counts")
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.agents_waiting_partial, 1)).assertExists()
        compose.onNode(hasText(context.getString(R.string.surfaces_age_minutes, 2)) and hasAnyAncestor(hasTestTag("console-drawer"))).assertExists()
        compose.runOnIdle { runBlocking { core.snapshot("h2") } }
        compose.waitUntil { model().drawerAgents.any { it.host.id == "h2" } }
        compose.onNodeWithText(context.getString(R.string.console_agents_waiting, 2)).assertExists()
        screenshot("drawer-counts")
        compose.onNodeWithTag("drawer-host:h1").performClick()
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.console_agents_waiting, 1)).assertExists()
        compose.onNodeWithTag("drawer-agent:h1:t2").assertDoesNotExist()
        compose.onNodeWithTag("drawer-agent:h2:t3").assertExists()
    }

    @Test fun edgeSwipeOpensTheDrawerWithoutTakingTerminalCanvasGestures() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(agent("t1", "Agent", AgentStatus.Working))
        }
        show(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithTag("console-top-bar").performTouchInput { swipe(Offset(width * .5f, height * .5f), Offset(width * .9f, height * .5f)) }
        compose.onNodeWithTag("console-drawer").assertIsNotDisplayed()
        compose.onNodeWithTag("console-top-bar").performTouchInput { swipe(Offset(2f, height * .5f), Offset(width * .7f, height * .5f)) }
        compose.onNodeWithTag("console-drawer").assertIsDisplayed()
        compose.onNodeWithTag("drawer-agent:h1:t1").performClick()
        compose.onRoot().performTouchInput { swipe(Offset(2f, 300f), Offset(250f, 300f)) }
        compose.onNodeWithTag("console-drawer").assertIsNotDisplayed()
        compose.onNodeWithTag("console-top-bar").performTouchInput { swipe(Offset(2f, height * .5f), Offset(width * .7f, height * .5f)) }
        compose.onNodeWithTag("console-drawer").assertIsDisplayed()
    }

    // Regression: drawer entry must preserve Browse's live line and Back destination without unlocking other Hosts.
    @Test fun drawerAgentReusesBrowseWithoutConnectingOtherHosts() {
        val current = agent("remote/t1", "Current", AgentStatus.Working)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            terminalsOf["h1"] = listOf(current)
        }
        val model = show(core)
        compose.onNodeWithText("home").performClick()
        val browse = model().screen
        val connection = core.connectionsOf.getValue("h1")
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-agent:h1:remote/t1").performClick()
        compose.runOnIdle {
            assertFalse(connection.disconnected)
            assertSame(connection, core.connectionsOf.getValue("h1"))
            assertFalse(core.connectionsOf.containsKey("h2"))
            assertSame(browse, (model().screen as Screen.Terminal).returnTo)
        }
        screenshot("drawer-agent-reused")
        compose.onNodeWithTag("console-back").performClick()
        compose.runOnIdle { assertSame(browse, model().screen) }
    }

}
