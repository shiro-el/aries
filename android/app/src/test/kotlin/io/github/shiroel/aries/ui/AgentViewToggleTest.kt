package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One entry for an Agent: it opens in the view picked last (Terminal by
 * default), a toggle on both screens switches it, and back returns to
 * where it was opened. Against a fake core.
 */
@RunWith(RobolectricTestRunner::class)
class AgentViewToggleTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val claude = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)
    private val vim = TerminalInfo("t2", "vim", "", listOf("vim"), "/repo", "live", null, false, 0uL)

    private lateinit var model: HostsModel

    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.waitForIdle()
    }

    private fun browseHome(core: FakeCore) {
        core.terminals = listOf(claude, vim)
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        show(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    @Test
    fun anAgentOpensAsItsTerminalByDefaultAndTheToggleSwitchesWithoutStacking() {
        val core = FakeCore()
        browseHome(core)
        // No separate Chat entry next to the Agent.
        compose.onNodeWithText(str(R.string.agent_view_chat)).assertDoesNotExist()

        click("claude")
        assertTrue(model.screen is Screen.Terminal)
        val conn = core.connection!!
        val view = conn.views.single()
        assertEquals("t1", view.address)
        compose.onNodeWithTag(AGENT_VIEW_TOGGLE).assertExists()

        click(str(R.string.agent_view_chat))
        compose.onNodeWithText(str(R.string.console_agent_host_prefix, "home") + "claude").assertExists()
        assertEquals(listOf("h1" to "t1"), core.chatsOpened)
        assertTrue("the Terminal let go", view.detached)
        assertEquals(AgentView.CHAT, core.savedView)
        compose.onNodeWithTag(AGENT_VIEW_TOGGLE).assertExists()

        click(str(R.string.agent_view_terminal))
        assertTrue(model.screen is Screen.Terminal)
        assertTrue("the Chat stopped", core.chatStopped)
        assertEquals(2, conn.views.size)
        assertEquals(AgentView.TERMINAL, core.savedView)

        // Back skips the Terminal/Chat round trip: it is the Host again.
        click(str(R.string.back_button))
        assertTrue(model.screen is Screen.Browse)
        assertTrue(conn.views.last().detached)
        compose.onNodeWithText("vim").assertExists()
    }

    @Test
    fun theSavedChatViewOpensTheAgentFromItsDetailAsTheChat() {
        val core = FakeCore().apply {
            savedView = AgentView.CHAT // picked before the app restarted
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            terminalsOf["h1"] = listOf(claude)
        }
        show(core)
        compose.openConsoleDrawer()
        click(str(R.string.agents_button))
        click("claude")
        compose.onNodeWithText(str(R.string.agent_view_chat)).assertDoesNotExist()

        click(str(R.string.agent_open_button))
        assertTrue(model.screen is Screen.Chat)
        assertEquals(listOf("h1" to "t1"), core.chatsOpened)
        assertTrue(core.connectionsOf.getValue("h1").views.isEmpty())

        click(str(R.string.back_button))
        compose.onNodeWithText(str(R.string.agent_detail_host, "home")).assertExists()
    }

    @Test
    fun aTerminalThatIsNoAgentOpensAsATerminalWithoutAToggle() {
        val core = FakeCore().apply { savedView = AgentView.CHAT }
        browseHome(core)
        click("vim")
        assertTrue(model.screen is Screen.Terminal)
        compose.onNodeWithTag(AGENT_VIEW_TOGGLE).assertDoesNotExist()
        assertTrue(core.chatsOpened.isEmpty())
    }

    @Test
    fun aNotificationTapOpensTheSavedView() {
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            terminalsOf["h1"] = listOf(claude)
        }
        show(core)
        compose.runOnIdle { model.openPushedAgent("h1", "t1") }
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Chat)
        assertEquals(listOf("h1" to "t1"), core.chatsOpened)

        click(str(R.string.agent_view_terminal))
        assertTrue(model.screen is Screen.Terminal)
        click(str(R.string.back_button))
        compose.onNodeWithText(str(R.string.agent_detail_address, "t1")).assertExists()
    }

    @Test
    fun aCallFromAnAgentOpensTheSavedViewAndBackIsCallHistory() {
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            ensureKey()
            terminalsOf["h1"] = listOf(claude)
            callsOf["h1"] = listOf(
                CallInfo("h1", "c1", 1_000uL, "t1", true, "Review ready", "body", CallUrgency.CHECK, emptyList(), null, true),
            )
        }
        show(core)
        click(str(R.string.calls_button))
        compose.onNodeWithContentDescription(str(R.string.call_open_terminal_label, "t1", "Review ready")).performClick()
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Chat)
        assertFalse(core.chatsOpened.isEmpty())

        click(str(R.string.back_button))
        compose.onNodeWithText(str(R.string.calls_title)).assertExists()
    }
}
