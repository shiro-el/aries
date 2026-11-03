package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.SendOutcome
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Sending from an Agent's Chat into its Terminal, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class ChatSendTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val claude = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle, false, 0uL)
    private val permission = AgentStatus.Blocked(BlockedReason.PERMISSION, "Write: /repo/hello.txt")

    private fun openChat(core: FakeCore, agent: TerminalInfo = claude): FakeConnection {
        core.terminals = listOf(agent)
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_open_label, "claude")).performClick()
        compose.waitForIdle()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        return core.connection!!
    }

    private fun type(text: String) {
        compose.onNodeWithText(str(R.string.chat_input_label)).performTextInput(text)
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun aMessageGoesIntoTheAgentsTerminal() {
        val core = FakeCore()
        val conn = openChat(core)
        compose.onNodeWithText(str(R.string.chat_send_button)).assertIsNotEnabled()

        type("now add a test")

        assertEquals(listOf("t1" to "now add a test"), conn.typed)
        compose.onNodeWithText("now add a test").assertDoesNotExist()
    }

    @Test
    fun whileSomeoneTypesAtTheHostTheMessageIsShownHeld() {
        val core = FakeCore()
        val conn = openChat(core)
        val hold = CompletableDeferred<Unit>()
        conn.sendHold = hold

        type("after you")
        compose.onNodeWithText(str(R.string.chat_send_held)).assertExists()
        compose.onNodeWithText(str(R.string.chat_send_button)).assertIsNotEnabled()
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)

        hold.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_send_held)).assertDoesNotExist()
        assertEquals(listOf("t1" to "after you"), conn.typed)
    }

    @Test
    fun aBlockedAgentRefusesTheMessageAndPointsToTheTerminal() {
        val core = FakeCore()
        val conn = openChat(core)
        conn.sendOutcome = SendOutcome.Blocked(BlockedReason.PERMISSION, "Write: /repo/hello.txt")

        type("go on")

        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
        compose.onNodeWithText(str(R.string.chat_send_refused)).assertExists()
        compose.onNodeWithText(str(R.string.chat_blocked_permission)).assertExists()
        compose.onNodeWithText("Write: /repo/hello.txt").assertExists()
        // The message stays, to send again once the Agent is free.
        compose.onNodeWithText("go on").assertExists()

        compose.onNodeWithText(str(R.string.terminal_open_button)).performClick()
        compose.waitForIdle()
        assertEquals("t1", conn.views.single().address)
        assertEquals(true, conn.views.single().options.observe)

        // Back from the Terminal is the Chat, still live.
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_title, "claude")).assertExists()
        compose.onNodeWithText("go on").assertExists()
    }

    @Test
    fun aPermissionRequestIsShownButOnlyAnsweredInTheTerminal() {
        val core = FakeCore()
        val conn = openChat(core)
        compose.onNodeWithText(str(R.string.chat_blocked_permission)).assertDoesNotExist()

        core.push(listOf(claude.copy(agentStatus = permission, needsAttention = true)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_blocked_permission)).assertExists()
        compose.onNodeWithText("Write: /repo/hello.txt").assertExists()
        compose.onNodeWithText(str(R.string.chat_send_refused)).assertDoesNotExist()

        compose.onNodeWithText(str(R.string.terminal_open_button)).performClick()
        compose.waitForIdle()
        assertEquals("t1", conn.views.single().address)
        assertEquals(emptyList<String>(), conn.calls.filter { it == "sendMessage" })

        // Approved at the Host: the Agent works again and the notice goes.
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        core.push(listOf(claude.copy(agentStatus = AgentStatus.Working)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_blocked_permission)).assertDoesNotExist()
    }

    @Test
    fun aFailedSendSaysWhy() {
        val core = FakeCore()
        val conn = openChat(core)
        conn.sendFailWith = CoreException.Closed()

        type("hello")

        compose.onNodeWithText("hello").assertExists()
        compose.onNodeWithText(str(R.string.chat_send_failed, ""), substring = true).assertExists()
    }
}
