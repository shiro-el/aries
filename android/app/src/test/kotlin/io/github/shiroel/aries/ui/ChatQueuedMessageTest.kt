package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    private var generation by mutableStateOf(0)
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private fun str(id: Int) = context.getString(id)

    private fun open(core: FakeCore, initialSnapshot: ChatSnapshot? = ChatSnapshot(emptyList(), ChatStatus.LIVE, null)): FakeConnection {
        core.terminals = listOf(agent)
        core.savedView = AgentView.CHAT
        core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            key(generation) {
                val scope = rememberCoroutineScope()
                val model = remember { HostsModel(core, scope).also { hosts = it } }
                MaterialTheme { AriesApp(model) }
            }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        initialSnapshot?.let(core::pushChat)
        compose.waitForIdle()
        return core.connection!!
    }

    private fun send(text: String) {
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput(text)
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
    }

    // D12: transcript rendering of bash and uploaded paths must resolve a lost send reply.
    @Test fun bashAndAttachmentRenderingCanResolveAnUnconfirmedMessage() {
        val core = FakeCore()
        val connection = open(core)
        val chat = compose.runOnIdle { (hosts.screen as Screen.Chat).chat }
        val uri = Uri.parse("content://documents/note.txt")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("notes".toByteArray()))
        compose.runOnIdle { chat.attach(context, SharedContent(listOf(uri))) }
        compose.waitUntil(5_000) { !chat.uploading }
        connection.sendFailWith = CoreException.Closed()
        send("!echo hello")
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(QueuedMessagePhase.Uncertain, chat.queuedMessages.single().phase)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("bash", null,
            ChatEntryBody.User("<bash-input>echo hello /host/it's file.jpg</bash-input>"))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        assertEquals(emptyList<QueuedChatMessage>(), chat.queuedMessages)
    }

    // D12: changed transcript text and completed turns must not leave permanent receipt bubbles.
    @Test fun enteredNotesRetireOnUserProgressOrTurnCompletionAndCanBeDismissed() {
        val core = FakeCore()
        val connection = open(core)
        val chat = compose.runOnIdle { (hosts.screen as Screen.Chat).chat }
        send("first")
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("different", null, ChatEntryBody.User("[Image #1]"))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        assertEquals(emptyList<QueuedChatMessage>(), chat.queuedMessages)
        send("second")
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Done, turn = 1uL)))
        compose.waitForIdle()
        assertEquals(emptyList<QueuedChatMessage>(), chat.queuedMessages)
        send("third")
        compose.onNodeWithContentDescription(str(R.string.chat_delivery_dismiss_button)).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<QueuedChatMessage>(), chat.queuedMessages)
        assertEquals(emptyList<QueuedChatMessage>(), ChatModel(core, chat.host, chat.terminal).queuedMessages)
        assertEquals(listOf("t1" to "first", "t1" to "second", "t1" to "third"), connection.typed)
    }

    // D12: a new User that matches a newer send still retires older Terminal receipts.
    @Test fun receiptCleanupAlsoCoversOlderNotesWhenTheNewUserMatchesAnotherSend() {
        val core = FakeCore()
        val connection = open(core)
        send("older image instruction")
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        send("newer instruction")
        core.pushChat(ChatSnapshot(listOf(ChatEntry("newer", null, ChatEntryBody.User("newer instruction"))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        assertEquals(emptyList<QueuedChatMessage>(), (hosts.screen as Screen.Chat).chat.queuedMessages)
        assertEquals(listOf("t1" to "older image instruction", "t1" to "newer instruction"), connection.typed)
    }

    // D10 fallback: returning rows to the composer must leave a durable explanation across edits/restart.
    @Test fun endedAgentsReturnWaitingInOrderWithANoticeThatSurvivesEditingAndRecreation() {
        val core = FakeCore()
        val connection = open(core)
        send("first")
        send("second")
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.ENDED, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("first\n\nsecond")
        compose.onNodeWithText(str(R.string.chat_queue_returned)).assertExists()
        compose.runOnIdle { (hosts.screen as Screen.Chat).chat.draft += " edited" }
        compose.runOnIdle { generation++ }
        compose.waitForIdle()
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("first\n\nsecond edited")
        compose.onNodeWithText(str(R.string.chat_queue_returned)).assertExists()
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)
    }

    // D10: process death preserves the two unsent rows and their FIFO on the replacement line.
    @Test fun processRecreationSendsWaitingRowsInOrderOnlyAfterANewLiveSnapshot() {
        val core = FakeCore()
        val original = open(core)
        send("first")
        send("second")
        compose.runOnIdle { generation++ }
        compose.waitForIdle()
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        val replacement = core.connection!!
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), replacement.typed)
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        assertEquals(listOf("t1" to "first"), replacement.typed)
        core.push(listOf(agent))
        compose.waitForIdle()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Done, turn = 1uL)))
        compose.waitForIdle()
        assertEquals(listOf("t1" to "first", "t1" to "second"), replacement.typed)
        assertEquals(emptyList<Pair<String, String>>(), original.typed)
    }

    // D09: a foreign-thread callback must not read/write the queue during an owner edit.
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    @Test fun heldCallbacksAreOwnedAndLateCallbacksCannotReviveCompletedOrCancelledRows() {
        val core = FakeCore()
        val connection = open(core)
        val chat = compose.runOnIdle { (hosts.screen as Screen.Chat).chat }
        connection.sendReply = CompletableDeferred()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        send("first")
        val callback = connection.sendListeners.single()
        compose.runOnIdle {
            kotlin.concurrent.thread { callback.onHeld() }.join()
            // Main is still performing this edit: the native callback must be queued behind it.
            assertEquals(ChatModel.Sending.Sending, chat.sending)
            chat.draft = "cancelled second"
            chat.send()
            chat.cancelQueuedMessage(chat.queuedMessages.last().id)
            chat.draft = "third"
            chat.send()
        }
        compose.waitForIdle()
        assertEquals(listOf("first", "third"), chat.queuedMessages.map { it.text })
        assertEquals(ChatModel.Sending.Held, chat.sending)
        connection.sendReply!!.complete(Unit)
        compose.waitForIdle()
        kotlin.concurrent.thread { callback.onHeld() }.join()
        compose.waitForIdle()
        assertEquals(false, chat.queuedMessages.first().heldForTyping)
        assertEquals(listOf("first", "third"), chat.queuedMessages.map { it.text })
        assertEquals(listOf("t1" to "first"), connection.typed)
        assertEquals("", chat.draft)
    }

    // D13: an old identical transcript row cannot acknowledge a send before the first snapshot.
    @Test fun sendingBeforeTheFirstSnapshotWaitsAndNeverClaimsAnOldIdenticalEntry() {
        val core = FakeCore()
        val connection = open(core, initialSnapshot = null)
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        connection.sendReply = CompletableDeferred()
        send("repeat")
        assertEquals(0, connection.calls.count { it == "sendMessage" })
        val baseline = ChatSnapshot(listOf(ChatEntry("old", null, ChatEntryBody.User("repeat"))), ChatStatus.LIVE, null)
        core.pushChat(baseline)
        compose.waitForIdle()
        assertEquals(1, connection.calls.count { it == "sendMessage" })
        core.pushChat(baseline)
        compose.waitForIdle()
        connection.sendFailWith = CoreException.Closed()
        connection.sendReply!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_queue_uncertain)).assertExists()
    }

    // D13: history arriving while Waiting must be included in the dispatch baseline.
    @Test fun aWaitingMessageDoesNotClaimHistoryThatArrivedBeforeItsDispatch() {
        val core = FakeCore()
        val connection = open(core)
        send("repeat")
        val baseline = ChatSnapshot(listOf(ChatEntry("old", null, ChatEntryBody.User("repeat"))), ChatStatus.LIVE, null)
        core.pushChat(baseline)
        compose.waitForIdle()
        connection.sendReply = CompletableDeferred()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        core.pushChat(baseline)
        compose.waitForIdle()
        connection.sendFailWith = CoreException.Closed()
        connection.sendReply!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_queue_uncertain)).assertExists()
    }

    // D20: a lost reply must stop both existing and newly enqueued follow-ups.
    @Test fun uncertainDeliveryBlocksTheRestOfTheFifoUntilExplicitlyResolved() {
        val core = FakeCore()
        val connection = open(core)
        send("first")
        send("second")
        connection.sendReply = CompletableDeferred()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        connection.sendFailWith = CoreException.Closed()
        connection.sendReply!!.complete(Unit)
        compose.waitForIdle()
        send("third")
        assertEquals(1, connection.calls.count { it == "sendMessage" })
        val chat = compose.runOnIdle { (hosts.screen as Screen.Chat).chat }
        assertEquals(listOf(QueuedMessagePhase.Uncertain, QueuedMessagePhase.Waiting, QueuedMessagePhase.Waiting), chat.queuedMessages.map { it.phase })
        connection.sendFailWith = null
        compose.runOnIdle { chat.retryMessage(chat.queuedMessages.first().id) }
        compose.waitForIdle()
        assertEquals(listOf("t1" to "first"), connection.typed)
        assertEquals(listOf("second", "third"), chat.queuedMessages.filter { it.phase == QueuedMessagePhase.Waiting }.map { it.text })
    }

    // D11: a status update during Stop must never drain queued follow-up instructions.
    @Test fun confirmedStopReturnsWaitingMessagesInOrderWithoutTypingIntoTheStoppedAgent() {
        val core = FakeCore()
        val connection = open(core)
        send("first instruction")
        send("second instruction")
        val chat = compose.runOnIdle { (hosts.screen as Screen.Chat).chat }
        connection.interruptHold = CompletableDeferred()
        compose.runOnIdle {
            chat.draft = "newer draft"
            chat.interrupt()
        }
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        connection.interruptHold!!.complete(Unit)
        compose.waitForIdle()
        assertEquals("first instruction\n\nsecond instruction\n\nnewer draft", chat.draft)
        assertEquals(emptyList<QueuedChatMessage>(), chat.queuedMessages)
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
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
        compose.onNodeWithText("Entered in Terminal").assertExists()
        core.pushChat(ChatSnapshot(listOf(
            ChatEntry("u1", null, ChatEntryBody.User("next step", emptyList(), false)),
            ChatEntry("u2", null, ChatEntryBody.User("next step", emptyList(), false)),
        ), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Entered in Terminal").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("next step").fetchSemanticsNodes().size == 2
        }
        assertEquals(listOf("t1" to "next step", "t1" to "next step"), connection.typed)
        screenshot("delivered-once")
    }

    @Test fun reopeningChatKeepsUnsentMessagesQueuedAndSendsOnTheNewLine() {
        val core = FakeCore()
        val connection = open(core)
        send("recover this draft")
        compose.runOnIdle {
            // A lost connection preserves proof that Waiting has never been attempted.
            (hosts.screen as Screen.Chat).chat.link!!.close()
            hosts.back()
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
        assertEquals(listOf("t1" to "recover this draft"), core.connection!!.typed)
    }

    @Test fun reconnectSendsOnlyUnsentMessagesAndNeverRetriesUnconfirmedMessages() {
        val core = FakeCore()
        val originalConnection = open(core)
        send("check delivery")
        // The sending line and the Chat watch have separate SSH connections.
        // The replacement line can send rows proven never to have been attempted.
        compose.runOnIdle { (hosts.screen as Screen.Chat).chat.link!!.lost(originalConnection) }
        compose.waitUntil(5_000) { core.connection !== originalConnection }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        val connection = core.connection!!
        connection.sendHold = CompletableDeferred()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
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

    // D16/D19: navigation cannot type Waiting rows or journal them as attempted.
    @Test fun leavingChatPreservesWaitingMessagesWithoutHandoff() {
        val core = FakeCore()
        val connection = open(core)
        send("first queued instruction")
        send("second queued instruction")
        val chat = compose.runOnIdle { (hosts.screen as Screen.Chat).chat }
        compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.runOnIdle { hosts.back() }
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
        assertEquals(listOf(QueuedMessagePhase.Waiting, QueuedMessagePhase.Waiting), chat.queuedMessages.map { it.phase })
        val recovered = ChatModel(core, chat.host, agent)
        assertEquals(listOf("first queued instruction", "second queued instruction"), recovered.queuedMessages.map { it.draft })
        assertEquals("", recovered.draft)
    }

    @Test fun editingAndSwitchingViewsDuringAHeldSendKeepsOnlyTheNewDraft() {
        val core = FakeCore()
        val connection = open(core)
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        connection.sendHold = CompletableDeferred()
        send("submitted prefix")
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertIsEnabled()
            .performTextInput("new draft")
        compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
        compose.runOnIdle { hosts.sendTerminalSelectionToDraft("selected output") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label))
            .assertTextEquals("new draft\n\n```\nselected output\n```")
        screenshot("held-with-edited-draft")
        connection.sendHold!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label))
            .assertTextEquals("new draft\n\n```\nselected output\n```")
        assertEquals(listOf("t1" to "submitted prefix"), connection.typed)
        compose.onNodeWithText("Entered in Terminal").assertExists()
        screenshot("editable-held-draft")
    }

    @Test fun reopeningWhileAReplyIsPendingKeepsItsResultAndTheNewerDraft() {
        val core = FakeCore()
        val connection = open(core)
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        connection.sendReply = CompletableDeferred()
        send("possibly delivered")
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("keep editing")
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.onNodeWithText("claude").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        connection.sendFailWith = CoreException.Closed()
        connection.sendReply!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("keep editing")
        compose.onNodeWithText(str(R.string.chat_queue_uncertain)).assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_send_failed, connection.sendFailWith!!.message))
            .assertDoesNotExist()
        assertEquals(1, connection.calls.count { it == "sendMessage" })
        screenshot("reopened-delivery-unconfirmed")
    }

    @Test fun processRecreationRecoversChatAttachmentsQuestionsAndTerminalComposer() {
        val core = FakeCore()
        val connection = open(core)
        val question = ChatEntryBody.Question("AskUserQuestion", "Choose a direction",
            listOf(ChatQuestionPart("First?", "First", true, listOf(ChatQuestionOption("Red", ""), ChatQuestionOption("Blue", ""))),
                ChatQuestionPart("Second?", "Second", false, emptyList())), ChatToolStatus.RUNNING, null)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("q", null, question)), ChatStatus.LIVE, null))
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, "Choose a direction"))))
        compose.waitForIdle()
        val file = File(context.cacheDir, "draft-owner.txt").apply { writeText("notes") }
        try {
            compose.runOnIdle {
                val chat = (hosts.screen as Screen.Chat).chat
                chat.draft = "unsent text"
                chat.pick("q", question, 0, 1)
                chat.writeAnswer("q", question, 1, "my own answer")
                chat.attach(context, SharedContent(listOf(Uri.fromFile(file))))
            }
            compose.waitUntil(5_000) { connection.uploads.isNotEmpty() }
            compose.waitUntil(5_000) { !(hosts.screen as Screen.Chat).chat.uploading }
            compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
            compose.runOnIdle {
                val viewer = (hosts.screen as Screen.Terminal).viewer
                viewer.openComposer()
                viewer.draft = "unsent Terminal command"
            }
            // Dispose the UI scope without calling back()/stop(): process death has no cleanup callback.
            compose.runOnIdle { generation++ }
            compose.waitForIdle()
            compose.onNodeWithText("home").performClick()
            compose.onNodeWithText("claude").performClick()
            compose.onNodeWithTag("terminal-composer").assertTextEquals("unsent Terminal command")
            screenshot("restored-terminal-composer")
            compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
            core.pushChat(ChatSnapshot(listOf(ChatEntry("q", null, question)), ChatStatus.LIVE, null))
            compose.waitForIdle()
            compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("unsent text")
            compose.runOnIdle {
                val chat = (hosts.screen as Screen.Chat).chat
                assertEquals("/host/it's file.jpg", chat.attachments.single().path)
                assertEquals(listOf(setOf(1), emptySet<Int>()), chat.picks["q"])
                assertEquals(listOf("", "my own answer"), chat.questionText["q"])
            }
            assertEquals(emptyList<Pair<String, String>>(), connection.typed)
            assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)
            screenshot("restored-composers")
        } finally { file.delete() }
    }

    @Test fun selectingChatHistoryAppendsWithoutReplacingTheCurrentDraftOrSending() {
        val core = FakeCore()
        val connection = open(core)
        core.rememberInput("h1", "t1", "previous input")
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("current draft")
        compose.onNodeWithContentDescription(str(R.string.input_history_title)).performClick()
        compose.onNodeWithText("previous input").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label))
            .assertTextEquals("current draft\n\nprevious input")
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
    }

    @Test fun notificationSwitchKeepsSeparateDraftsForTheSameTerminalAddressOnDifferentHosts() {
        val core = FakeCore().apply {
            addHost("other", Endpoint.Ssh("other", 22u, "me"))
            terminalsOf["h1"] = listOf(agent)
        }
        open(core) // home is h2; both Hosts expose address t1.
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("home draft")
        compose.runOnIdle { hosts.openPushedAgent("h1", "t1", openChat = true) }
        compose.waitForIdle()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.onNodeWithContentDescription(str(R.string.chat_input_label))
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("")))
            .performTextInput("other draft")
        compose.runOnIdle { hosts.openPushedAgent("h2", "t1", openChat = true) }
        compose.waitForIdle()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("home draft")
        compose.runOnIdle { hosts.openPushedAgent("h1", "t1", openChat = true) }
        compose.waitForIdle()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("other draft")
    }

    @Test fun upgradingAnExistingOutboxRestoresDraftAndUnconfirmedSendWithoutResending() {
        val core = FakeCore()
        core.saveChatOutbox("h1", "t1", """{
            "version":1,"draft":"older draft","attachments":[],"messages":[{
                "id":"old-send","draft":"older send","text":"older send","attachments":[],
                "before":[],"phase":"Agent","confirmed":false
            }]
        }""")
        val connection = open(core)
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("older draft")
        compose.onNodeWithText("older send").assertExists()
        compose.onNodeWithText(str(R.string.chat_queue_uncertain)).assertExists()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
    }

    @Test fun reopeningADraftAfterBackgroundReleaseStartsAFreshTranscriptWatch() {
        val core = FakeCore()
        open(core)
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("saved before lock")
        compose.runOnIdle {
            hosts.backgrounded(retainConnections = false)
            hosts.back()
            hosts.resumeScreens()
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("resumed", null, ChatEntryBody.Agent("Reply after unlocking"))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reply after unlocking").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("saved before lock")
    }

    @Test fun everyDurableCheckpointOfQueueCancellationRecoversTheTextAndAttachmentTogether() {
        val core = FakeCore()
        val connection = open(core)
        val file = File(context.cacheDir, "atomic-draft.txt").apply { writeText("notes") }
        try {
            compose.runOnIdle { (hosts.screen as Screen.Chat).chat.attach(context, SharedContent(listOf(Uri.fromFile(file)))) }
            compose.waitUntil(5_000) { connection.uploads.isNotEmpty() }
            compose.waitUntil(5_000) { !(hosts.screen as Screen.Chat).chat.uploading }
            compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("review notes")
            core.outboxWrites.clear()
            compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
            compose.onNodeWithContentDescription(str(R.string.chat_queue_cancel_button)).performClick()
            compose.waitForIdle()
            // Each acknowledged storage write is a possible process-death boundary.
            // Recovery must never combine only half of the draft/queue transition.
            org.junit.Assert.assertTrue(core.outboxWrites.isNotEmpty())
            for ((hostId, terminal, content) in core.outboxWrites.toList()) {
                val restarted = FakeCore()
                val host = restarted.addHost("home", Endpoint.Ssh("host", 22u, "me"))
                restarted.saveChatOutbox(hostId, terminal, content)
                val restored = ChatModel(restarted, host, agent)
                assertEquals(listOf("review notes"), listOf(restored.draft).filter { it.isNotEmpty() } + restored.queuedMessages.map { it.draft })
                assertEquals(listOf("/host/it's file.jpg"), (restored.attachments + restored.queuedMessages.flatMap { it.attachments }).map { it.path })
            }
        } finally { file.delete() }
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
