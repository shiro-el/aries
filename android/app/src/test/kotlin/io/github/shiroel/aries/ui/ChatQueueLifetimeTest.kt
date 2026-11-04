package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ChatQueueLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hosts: HostsModel
    private val core = FakeCore()
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)
    private fun open(openChat: Boolean = true): FakeConnection {
        core.terminals = listOf(agent)
        core.savedView = AgentView.CHAT
        core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope).also { hosts = it } }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        if (openChat) compose.onNodeWithText("claude").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        return core.connection!!
    }
    private fun send(text: String) {
        compose.runOnIdle { (hosts.screen as Screen.Chat).chat.apply { draft = text; send() } }
        compose.waitForIdle()
    }
    private fun status(status: AgentStatus, turn: ULong = 0uL) {
        core.push(listOf(agent.copy(agentStatus = status, turn = turn)))
        compose.waitForIdle()
    }

    // D03: no Chat screen is present when the Host delivers both queued instructions.
    @Test fun backToBrowseKeepsDrainingTheHostFifo() {
        val line = open()
        send("first")
        send("second")
        compose.runOnIdle { hosts.closeChat() }
        assertTrue(hosts.screen is Screen.Browse)
        status(AgentStatus.Idle)
        compose.waitForTypedMessages(line, 1)
        assertEquals(listOf("t1" to "first"), line.typed)
        status(AgentStatus.Working)
        status(AgentStatus.Idle, 1uL)
        compose.waitForTypedMessages(line, 2)
        assertEquals(listOf("t1" to "first", "t1" to "second"), line.typed)
    }

    // D03: a process-restored FIFO must run on Host connect without creating any Chat screen.
    @Test fun hostConnectionRecoversTheFifoWithoutEverOpeningChat() {
        core.saveChatOutbox("h1", "t1", ChatOutboxState("keep draft", emptyList(), listOf(
            QueuedChatMessage(draft = "first", text = "first", attachments = emptyList(), before = emptySet()),
            QueuedChatMessage(draft = "second", text = "second", attachments = emptyList(), before = emptySet()),
        )).encode())
        val line = open(openChat = false)
        assertTrue(hosts.screen is Screen.Browse)
        status(AgentStatus.Idle)
        compose.waitForTypedMessages(line, 1)
        assertEquals(listOf("t1" to "first"), line.typed)
        status(AgentStatus.Working)
        status(AgentStatus.Idle, 1uL)
        compose.waitForTypedMessages(line, 2)
        assertEquals(listOf("t1" to "first", "t1" to "second"), line.typed)
    }

    // D03: Terminal navigation cannot stop delivery on its still-live Host line.
    @Test fun terminalViewKeepsDrainingWithoutReopeningChat() {
        val line = open()
        send("first")
        compose.runOnIdle { hosts.switchView(AgentView.TERMINAL) }
        status(AgentStatus.Idle)
        compose.waitForTypedMessages(line, 1)
        assertEquals(listOf("t1" to "first"), line.typed)
    }

    // D04: only ambiguous delivery requires a user decision before newer input.
    @Test fun rejectedInputDoesNotBlockNewSendButUncertainInputDoes() {
        val line = open()
        val chat = (hosts.screen as Screen.Chat).chat
        compose.runOnIdle { ChatReplyRecovery.recover(core, AgentKey("h1", "t1"),
            ChatReplyRecovery.message("rejected", "old", "rejected", uncertain = false)) }
        status(AgentStatus.Idle)
        send("new")
        compose.waitForTypedMessages(line, 1)
        assertEquals(listOf("t1" to "new"), line.typed)
        status(AgentStatus.Working)
        status(AgentStatus.Idle, 1uL)
        compose.runOnIdle { ChatReplyRecovery.recover(core, AgentKey("h1", "t1"),
            ChatReplyRecovery.message("lost", "unknown", "unknown", uncertain = true)) }
        send("waiting")
        assertEquals(listOf("t1" to "new"), line.typed)
        assertEquals(QueuedMessagePhase.Waiting, chat.queuedMessages.last().phase)
        compose.onNodeWithText("Check the earlier message before sending").assertExists()
    }

    // D07: Idle while the Stop dialog is open must not turn Stop into a Send.
    @Test fun stopConfirmationFreezesTheQueueEvenIfTheTurnEnds() {
        val line = open()
        send("return this")
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        status(AgentStatus.Idle)
        assertEquals(emptyList<Pair<String, String>>(), line.typed)
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals("return this", (hosts.screen as Screen.Chat).chat.draft)
        assertEquals(emptyList<Pair<String, String>>(), line.typed)
    }

    // D09: extra Idle snapshots before Working must never release the next instruction.
    @Test fun theNextMessageWaitsForWorkingThenIdleEvenAfterTranscriptAcknowledgement() {
        val line = open()
        send("first")
        send("second")
        status(AgentStatus.Idle)
        compose.waitForTypedMessages(line, 1)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User("first"))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        status(AgentStatus.Idle)
        assertEquals(listOf("t1" to "first"), line.typed)
        status(AgentStatus.Working)
        status(AgentStatus.Idle, 1uL)
        compose.waitForTypedMessages(line, 2)
        assertEquals(listOf("t1" to "first", "t1" to "second"), line.typed)
    }

    // D20: an independently reconnecting send line must not reuse the old Chat LIVE flag.
    @Test fun replacementSendLineWaitsForANewTranscriptSnapshot() {
        val first = open()
        send("waiting")
        val chat = (hosts.screen as Screen.Chat).chat
        compose.runOnIdle { chat.link!!.lost(first) }
        compose.waitUntil(5_000) { core.connection !== first }
        compose.waitForIdle()
        status(AgentStatus.Idle)
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.waitForTypedMessages(core.connection!!, 1)
        assertEquals(listOf("t1" to "waiting"), core.connection!!.typed)
    }

    // D15: answering history uses the same queue as a send awaiting its RPC reply.
    @Test fun historicalAnswersCannotOvertakeAnInFlightMessage() {
        val line = open()
        val question = ChatEntryBody.Question("AskUserQuestion", "Choose", listOf(
            ChatQuestionPart("Which?", "", false, listOf(ChatQuestionOption("Red", "")))
        ), ChatToolStatus.RUNNING, null)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("q", null, question)), ChatStatus.LIVE, null))
        status(AgentStatus.Idle)
        line.sendReply = CompletableDeferred()
        send("first")
        compose.runOnIdle { (hosts.screen as Screen.Chat).chat.pick("q", question, 0, 0) }
        compose.waitForIdle()
        compose.waitForSendAttempts(line, 1)
        assertEquals(1, line.sendListeners.size)
        line.sendReply!!.complete(Unit)
        compose.waitForIdle()
        compose.waitForTypedMessages(line, 1)
        assertEquals(listOf("t1" to "first"), line.typed)
        status(AgentStatus.Working)
        status(AgentStatus.Idle, 1uL)
        compose.waitForTypedMessages(line, 2)
        assertEquals(listOf("t1" to "first", "t1" to "Red"), line.typed)
    }
}
