package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: failed subscriptions recover without navigation or replaying user input. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentLivenessTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: HostsModel
    private val core = FakeCore().apply {
        addHost("home", Endpoint.Ssh("home", 22u, "me"))
        terminalsOf["h1"] = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live",
            AgentStatus.Error("answer me"), true, 0uL))
    }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun start() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.runOnIdle { model.openAgents() }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        System.getenv("ARIES_LIVENESS_SHOTS")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    private val board get() = (model.screen as Screen.Agents).board

    @Test fun failedWatchCanRetryOnTheSameLineAndReceivesLaterChanges() {
        start()
        val connection = core.connectionsOf.getValue("h1")
        compose.runOnIdle { core.failTerminalWatch("h1", CoreException.Protocol("subscription timed out")) }
        compose.waitForIdle()
        capture("watch-stopped")
        compose.onNodeWithText(context.getString(R.string.connect_retry_button)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { core.push("h1", emptyList()) }
        compose.waitForIdle()
        assertTrue(board.agents.isEmpty())
        assertSame(connection, core.connectionsOf.getValue("h1"))
        assertFalse(connection.disconnected)
        capture("watch-recovered")
    }

    @Test fun reconnectRestartsAnInitiallyRejectedWatch() {
        core.watchFailures["h1"] = CoreException.Protocol("subscribe failed")
        start()
        compose.runOnIdle {
            core.watchFailures.clear()
            model.networkChanged()
        }
        compose.waitForIdle()
        compose.onNodeWithText("claude").assertExists()
        compose.runOnIdle { core.push("h1", emptyList()) }
        compose.waitForIdle()
        assertTrue(board.isEmpty)
    }

    @Test fun disconnectedErrorIsRetainedOutsideNeedsYou() {
        val active = core.addHost("studio", Endpoint.Ssh("studio", 22u, "me"))
        core.terminalsOf[active.id] = core.terminalsOf.getValue("h1").map {
            it.copy(name = "codex", argv = listOf("codex"), agentStatus = AgentStatus.Working)
        }
        listOf("travel", "build").forEach { name ->
            val host = core.addHost(name, Endpoint.Ssh(name, 22u, "me"))
            core.failFor[host.id] = CoreException.Unreachable("no route")
        }
        start()
        compose.runOnIdle {
            core.failFor["h1"] = CoreException.AuthRejected()
            model.networkChanged()
        }
        compose.waitForIdle()
        assertEquals(2, board.agents.size)
        assertFalse(agentGroups(board.agents, AgentListView.Priority).any { it.priority == 4 })
        compose.onNodeWithTag("agent-row:h1:t1").assertExists()
        compose.mainClock.advanceTimeBy(1_600)
        capture("offline-and-connecting")
        compose.onNodeWithTag("agent-row:h1:t1").performClick()
        compose.waitForIdle()
        assertTrue("stale rows must not open a dead Terminal", model.screen is Screen.Agents)
    }
    @Test fun failedBrowseWatchRetriesWithoutLeavingTheHost() {
        start()
        compose.runOnIdle {
            model.back()
            core.watchFailures["h1"] = CoreException.Protocol("subscribe failed")
            model.connect(core.hosts().single())
        }
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Browse)
        compose.runOnIdle { core.watchFailures.clear() }
        compose.onNodeWithText(context.getString(R.string.connect_retry_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").assertExists()
        compose.runOnIdle { core.push("h1", emptyList()) }
        compose.waitForIdle()
        assertTrue((model.screen as Screen.Browse).browser.terminals.isEmpty())
    }

    @Test fun failedTerminalAttachCanRetryWithoutLosingTheOpenAgent() {
        start()
        core.attachFailure = CoreException.Protocol("attach timed out")
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        val viewer = (model.screen as Screen.Terminal).viewer
        assertEquals("t1", viewer.terminal.address)
        capture("terminal-stopped")
        compose.runOnIdle { core.attachFailure = null }
        compose.onNodeWithText(context.getString(R.string.connect_retry_button)).performClick()
        compose.waitForIdle()
        assertSame(viewer, (model.screen as Screen.Terminal).viewer)
        val view = core.connectionsOf.getValue("h1").views.single()
        assertEquals("t1", view.address)
        assertTrue(view.options.resumed)
        assertTrue(core.connectionsOf.getValue("h1").typed.isEmpty())

        // Retrying this attach must not queue a retry for another healthy subscription.
        val origin = (model.screen as Screen.Terminal).returnTo as Screen.Agents
        compose.runOnIdle { core.failTerminalWatch("h1", CoreException.Protocol("later list failure")) }
        compose.waitForIdle()
        assertTrue(origin.board.watchErrors.containsKey("h1"))
        compose.runOnIdle { model.closeTerminal() }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.connect_retry_button)).performClick()
        compose.waitForIdle()
        assertFalse(origin.board.watchErrors.containsKey("h1"))
    }

    @Test fun statusChangesWaitUntilTheFingerHasLeftBeforeMovingFamilies() {
        val first = core.terminalsOf.getValue("h1").single().copy(agentStatus = AgentStatus.Working)
        val second = first.copy(address = "t2", name = "codex", agentStatus = AgentStatus.Idle)
        core.terminalsOf["h1"] = listOf(first, second)
        start()
        compose.mainClock.autoAdvance = false
        // Touch empty list space to observe release without activating a row.
        val list = compose.onRoot()
        list.performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            core.push("h1", listOf(first.copy(agentStatus = AgentStatus.Idle), second.copy(agentStatus = AgentStatus.Error("help"))))
        }
        compose.mainClock.advanceTimeByFrame()
        fun order() = compose.onAllNodes(hasTestTag("agent-row:h1:t1") or hasTestTag("agent-row:h1:t2"))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertEquals(listOf("agent-row:h1:t1", "agent-row:h1:t2"), order())
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("agent-row:h1:t1", "agent-row:h1:t2"), order())
        compose.mainClock.advanceTimeBy(700)
        assertEquals(listOf("agent-row:h1:t2", "agent-row:h1:t1"), order())
    }

}
