package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun openAgents(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
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
        compose.onNodeWithText(str(R.string.terminal_subtitle, "home", "t1")).assertExists()
        compose.onNodeWithText(str(R.string.terminal_subtitle, "server", "t1")).assertExists()
        compose.onNodeWithText(str(R.string.terminal_subtitle, "home", "box/t1")).assertExists()
        compose.onNodeWithText("Which color?").assertExists()
    }

    @Test
    fun pinningOnThePhoneWritesTheSharedLabelAndADesktopPinShowsLive() {
        val core = twoHosts()
        openAgents(core)
        compose.onNodeWithText(str(R.string.agent_pinned_label)).assertDoesNotExist()

        compose.onNodeWithContentDescription(str(R.string.agent_pin_button_label, "codex", "home")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 pinned=true"), core.labelWrites)
        compose.onNodeWithText(str(R.string.agent_pinned_label)).assertExists()

        // The desktop pins server's t1.
        core.push("h2", listOf(agent("t1", "pi", AgentStatus.Done, turn = 3uL, pinned = true)))
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithText(str(R.string.agent_pinned_label)).fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription(str(R.string.agent_unpin_button_label, "codex", "home")).performClick()
        compose.waitForIdle()
        assertEquals("h1 box/t1 pinned=false", core.labelWrites.last())
        assertEquals(1, compose.onAllNodesWithText(str(R.string.agent_pinned_label)).fetchSemanticsNodes().size)
    }

    @Test
    fun openingADoneAgentShowsItsDetailAndConfirmsTheDoneForEveryClient() {
        val core = twoHosts()
        openAgents(core)

        compose.onNodeWithText("pi").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_detail_host, "server")).assertExists()
        compose.onNodeWithText(str(R.string.agent_detail_address, "t1")).assertExists()
        compose.onNodeWithText(str(R.string.agent_detail_folder, "/repo/pi")).assertExists()
        assertEquals(listOf("h2 t1 seen=3"), core.labelWrites)
        compose.onNodeWithText(str(R.string.agent_status_idle)).assertExists()

        // Back to the list; opening an Agent that is not Done writes nothing.
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_detail_address, "t1")).assertExists()
        compose.onNodeWithText(str(R.string.agent_detail_host, "home")).assertExists()
        assertEquals(listOf("h2 t1 seen=3"), core.labelWrites)

        // The Agent goes away while its detail is open.
        core.push("h1", emptyList())
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_detail_gone)).assertExists()
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
    fun opensAnAgentsTerminalFromItsDetailAndBackReturnsToTheDetail() {
        val core = twoHosts()
        openAgents(core)

        compose.onNodeWithText("pi").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_open_button)).performClick()
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
        compose.onNodeWithText(str(R.string.agent_detail_host, "server")).assertExists()
    }
}
