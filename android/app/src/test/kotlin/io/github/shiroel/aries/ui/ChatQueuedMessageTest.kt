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

    private fun open(core: FakeCore): FakeConnection {
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

    private fun screenshot(name: String) {
        System.getenv("ARIES_QUEUE_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
