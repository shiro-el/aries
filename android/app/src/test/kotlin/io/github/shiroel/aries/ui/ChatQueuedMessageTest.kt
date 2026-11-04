package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.net.Uri
import java.io.ByteArrayInputStream
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatQueuedMessageTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hosts: HostsModel
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private fun str(id: Int) = context.getString(id)

    private fun open(core: FakeCore): FakeConnection {
        core.terminals = listOf(agent)
        core.savedView = AgentView.CHAT
        core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope).also { hosts = it } }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        return core.connection!!
    }

    private fun send(text: String) {
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput(text)
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
    }

    @Test fun cancellingAHeldMessageRestoresItsTextAndNeverTypesItWhenTheAgentFinishes() {
        val core = FakeCore()
        val connection = open(core)
        val uri = Uri.parse("content://documents/note.txt")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("notes".toByteArray()))
        compose.runOnIdle { (hosts.screen as Screen.Chat).chat.attach(context, SharedContent(listOf(uri))) }
        compose.waitUntil(5_000) { connection.uploads.isNotEmpty() }
        compose.waitForIdle()
        send("review the patch")
        compose.onNodeWithText("review the patch").assertIsDisplayed()
        screenshot("waiting")
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("keep the newer draft")
        compose.runOnIdle {
            val chat = (hosts.screen as Screen.Chat).chat
            chat.input = chat.input.copy(selection = TextRange(8), composition = TextRange(5, 8))
        }
        compose.onNodeWithContentDescription("Cancel queued message").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("review the patch\n\nkeep the newer draft")
        compose.runOnIdle {
            val input = (hosts.screen as Screen.Chat).chat.input
            assertEquals(TextRange(26), input.selection)
            assertEquals(TextRange(23, 26), input.composition)
        }
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
        assertEquals(0, connection.calls.count { it == "sendMessage" })
        compose.runOnIdle { assertEquals("/host/it's file.jpg", (hosts.screen as Screen.Chat).chat.attachments.single().path) }
        // Editing and explicitly sending the recovered draft includes its file once.
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to "review the patch\n\nkeep the newer draft '/host/it'\"'\"'s file.jpg'"), connection.typed)
    }

    @Test fun deliveredMessagesReplaceTheirBubblesOnceEvenWhenTheTextIsRepeated() {
        val core = FakeCore()
        val connection = open(core)
        send("next step")
        send("next step")
        compose.onAllNodesWithText("next step").assertCountEquals(2)
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)

        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        core.push(listOf(agent))
        compose.waitForIdle()
        screenshot("agent-handoff")
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User("next step", emptyList(), true))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        // One transcript row and the second phone-local message, not three rows.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("next step").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithContentDescription("Cancel queued message").assertExists()
        assertEquals(listOf("t1" to "next step"), connection.typed)

        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        core.push(listOf(agent))
        // Repeating the first update must not also consume the second identical send.
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User("next step", emptyList(), false))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText("Passed to Agent · waiting for its turn").assertExists()
        core.pushChat(ChatSnapshot(listOf(
            ChatEntry("u1", null, ChatEntryBody.User("next step", emptyList(), false)),
            ChatEntry("u2", null, ChatEntryBody.User("next step", emptyList(), false)),
        ), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Passed to Agent · waiting for its turn").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("next step").fetchSemanticsNodes().size == 2
        }
        assertEquals(listOf("t1" to "next step", "t1" to "next step"), connection.typed)
        screenshot("delivered-once")
    }

    @Test fun reopeningChatRecoversUnsentMessagesAsADraftWithoutAutomaticallySending() {
        val core = FakeCore()
        val connection = open(core)
        send("recover this draft")
        compose.runOnIdle {
            // A lost connection cannot accept a final handoff; preserve its draft.
            (hosts.screen as Screen.Chat).chat.link!!.close()
            hosts.back()
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("recover this draft")
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)
    }

    @Test fun reconnectDoesNotRetryEitherUnsentOrUnconfirmedMessages() {
        val core = FakeCore()
        val originalConnection = open(core)
        send("check delivery")
        // The sending line and the Chat watch have separate SSH connections.
        // Losing only HostLink must not replay a local queue on its replacement.
        compose.runOnIdle { (hosts.screen as Screen.Chat).chat.link!!.lost(originalConnection) }
        compose.waitUntil(5_000) { core.connection !== originalConnection }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("check delivery")
        val connection = core.connection!!
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(0, connection.calls.count { it == "sendMessage" })

        connection.sendHold = CompletableDeferred()
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Cancel queued message").assertDoesNotExist()
        screenshot("held-for-host-typing")
        connection.sendFailWith = CoreException.Closed()
        connection.sendHold!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Delivery unconfirmed · check the conversation before resending").assertExists()
        screenshot("delivery-unconfirmed")
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(1, connection.calls.count { it == "sendMessage" })
        // A lost reply may still have delivered. Only the transcript resolves it.
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User("check delivery", emptyList(), false))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Delivery unconfirmed · check the conversation before resending").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun aQueuedMessageCanStillBeCancelledAfterReadingOlderHistory() {
        val core = FakeCore()
        val connection = open(core)
        core.pushChat(ChatSnapshot((1..30).map {
            ChatEntry("a$it", null, ChatEntryBody.Agent("Reply $it\nSecond line\nThird line"))
        }, ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reply 30\nSecond line\nThird line").fetchSemanticsNodes().isNotEmpty() }
        send("keep this queued message")
        compose.onNodeWithText("keep this queued message").assertIsDisplayed()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(0)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(str(R.string.chat_latest_button)).fetchSemanticsNodes().isNotEmpty() }
        // The history provider must include the same pending row and cancel action.
        compose.onNodeWithTag("chat-transcript").performScrollToNode(hasText("keep this queued message"))
        compose.onNodeWithContentDescription("Cancel queued message").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("keep this queued message")
        assertEquals(0, connection.calls.count { it == "sendMessage" })
    }

    @Test fun stoppingChatHandsOffWaitingMessagesInOrderBeforeDisconnecting() {
        val core = FakeCore()
        val connection = open(core)
        send("first queued instruction")
        send("second queued instruction")
        val chat = compose.runOnIdle { (hosts.screen as Screen.Chat).chat }
        val hold = CompletableDeferred<Unit>()
        connection.sendHold = hold
        compose.runOnIdle { hosts.back() }
        compose.waitForIdle()
        assertEquals(1, connection.calls.count { it == "sendMessage" })
        compose.runOnIdle {
            assertEquals("", chat.draft)
            assertEquals(listOf(QueuedMessagePhase.Agent, QueuedMessagePhase.Agent), chat.queuedMessages.map { it.phase })
            assertEquals(false, connection.disconnected)
        }
        hold.complete(Unit)
        compose.waitUntil(5_000) { connection.typed.size == 2 && connection.disconnected }
        assertEquals(listOf("t1" to "first queued instruction", "t1" to "second queued instruction"), connection.typed)
        assertEquals(2, connection.calls.count { it == "sendMessage" })
        compose.runOnIdle { assertEquals("", chat.draft) }
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_QUEUE_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
