package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.hasContentDescription
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: every Host's Agents in one list, and the shared seen / pinned labels, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class AgentsAcrossHostsTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun agent(
        address: String,
        name: String,
        status: AgentStatus?,
        turn: ULong = 0uL,
        pinned: Boolean = false,
        attention: Boolean = false,
    ) = TerminalInfo(
        address, name, "", listOf(name, "--yes"), "/repo/$name", "live", status, attention, turn,
        pinned, address.substringBeforeLast('/', "").ifEmpty { null },
    )

    /** Two Hosts, each with a `t1`; home also has a Terminal on its machine `box`. */
    private fun twoHosts() = FakeCore().apply {
        addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        addHost("server", Endpoint.Ssh("10.0.0.3", 22u, "me"))
        terminalsOf["h1"] = listOf(
            agent("t1", "claude", AgentStatus.Working),
            agent("t2", "vim", null),
            agent("box/t1", "codex", AgentStatus.Blocked(BlockedReason.QUESTION, "Which color?"), attention = true),
        )
        terminalsOf["h2"] = listOf(agent("t1", "pi", AgentStatus.Done, turn = 3uL))
    }

    private lateinit var back: androidx.activity.OnBackPressedDispatcher

    private fun openAgents(core: FakeCore) {
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun showsEveryHostsAgentsInOneListWithoutMixingUpTheSameTerminalNumber() {
        openAgents(twoHosts())

        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithText("codex").assertExists()
        compose.onNodeWithText("pi").assertExists()
        // A program that does not report Agent status is not an Agent.
        compose.onNodeWithText("vim").assertDoesNotExist()
        // Both t1s and the machine's Terminal, each under its own Host, address whole.
        compose.onNodeWithContentDescription(str(R.string.console_agent_accessibility, "claude", str(R.string.terminal_subtitle, "home", "t1"))).assertExists()
        compose.onNodeWithContentDescription(str(R.string.console_agent_accessibility, "pi", str(R.string.terminal_subtitle, "server", "t1"))).assertExists()
        compose.onNodeWithContentDescription(str(R.string.console_agent_accessibility, "codex", str(R.string.terminal_subtitle, "home", "box/t1"))).assertExists()
        compose.onNodeWithText("Which color?").assertExists()
    }

    @Test
    fun pinningOnThePhoneWritesTheSharedLabelAndADesktopPinShowsLive() {
        val core = twoHosts()
        openAgents(core)
        compose.onNodeWithContentDescription(str(R.string.agent_pin_button_label, "codex", "home")).assertIsNotSelected()

        compose.onNodeWithContentDescription(str(R.string.agent_pin_button_label, "codex", "home")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 pinned=true"), core.labelWrites)
        compose.onNodeWithContentDescription(str(R.string.agent_unpin_button_label, "codex", "home")).assertIsSelected()

        // The desktop pins server's t1.
        core.push("h2", listOf(agent("t1", "pi", AgentStatus.Done, turn = 3uL, pinned = true)))
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodes(isSelected()).fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription(str(R.string.agent_unpin_button_label, "codex", "home")).performClick()
        compose.waitForIdle()
        assertEquals("h1 box/t1 pinned=false", core.labelWrites.last())
        assertEquals(1, compose.onAllNodes(isSelected()).fetchSemanticsNodes().size)
    }

    @Test
    fun openingADoneAgentDirectlyConfirmsTheDoneForEveryClient() {
        val core = twoHosts()
        openAgents(core)

        compose.onNodeWithText("pi").performClick()
        compose.waitForIdle()
        assertEquals("t1", core.connectionsOf.getValue("h2").views.single().address)
        compose.onNodeWithText(str(R.string.agent_open_button)).assertDoesNotExist()
        assertEquals(listOf("h2 t1 seen=3"), core.labelWrites)
        compose.onNodeWithText(str(R.string.agent_status_idle)).assertExists()

        // Viewing a Working Agent joins the shared seen rules even at turn zero.
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        assertEquals("t1", core.connectionsOf.getValue("h1").views.single().address)
        assertEquals(listOf("h2 t1 seen=3", "h1 t1 seen=0"), core.labelWrites)

        // A disappearing Agent must not leave a stale detail screen on Back.
        core.push("h1", emptyList())
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").assertDoesNotExist()
        compose.onNodeWithText("pi").assertExists()
    }

    @Test
    fun systemBackReturnsDirectlyToTheConnectedAgentsList() {
        val core = twoHosts()
        openAgents(core)
        compose.onNodeWithText("codex").performClick()
        compose.waitForIdle()
        val connection = core.connectionsOf.getValue("h1")
        assertEquals("box/t1", connection.views.single().address)

        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("codex").assertExists()
        compose.onNodeWithText("pi").assertExists()
        assertTrue(connection.views.single().detached)
        assertTrue(!connection.disconnected)
    }

    @Test
    fun aHostThatCannotBeReachedIsNamedWhileTheOthersStillShow() {
        val core = twoHosts().apply { failFor["h2"] = CoreException.Unreachable("timed out") }
        openAgents(core)

        compose.onNodeWithText(str(R.string.agents_host_unavailable, "server")).assertExists()
        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithText("pi").assertDoesNotExist()
    }

    @Test
    fun opensAnAgentsTerminalDirectlyAndBackReturnsToTheList() {
        val core = twoHosts()
        openAgents(core)

        compose.onNodeWithText("pi").performClick()
        compose.waitForIdle()

        // Attached on the Agent's own Host (server), not home: both have a t1.
        assertTrue(core.connectionsOf.getValue("h1").views.isEmpty())
        val view = core.connectionsOf.getValue("h2").views.single()
        assertEquals("t1", view.address)
        assertTrue("attaches as an observer", view.options.observe)
        view.push(
            TerminalUpdate.Screen(
                ScreenFrame(
                    80u, 1u, true,
                    listOf(ScreenLine(0u, listOf(CellRun(0u, 9u, "pi> ready", false, null, null, false, false, false, false, false, false)))),
                    null, null,
                ),
            ),
        )
        compose.waitForIdle()
        compose.onNode(hasContentDescription("pi> ready", substring = true)).assertExists()

        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(view.detached)
        assertTrue("the Agents list stays connected", !core.connectionsOf.getValue("h2").disconnected)
        compose.onNodeWithText("pi").assertExists()
        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithText(str(R.string.agent_open_button)).assertDoesNotExist()
    }
}
