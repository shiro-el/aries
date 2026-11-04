package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat viewport behavior through the UI and fake core, with enough rows to scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h800dp")
class ChatScrollTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core = FakeCore()
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private val generation = mutableStateOf(0)
    private val hostNumber = mutableStateOf(0)
    private val terminal = mutableStateOf(agent)
    private fun entries(count: Int) = (1..count).map {
        ChatEntry("a$it", null, ChatEntryBody.Agent("Reply $it\nSecond line\nThird line"))
    }

    private fun open() {
        context.getSharedPreferences("chat_reading_positions", Context.MODE_PRIVATE).edit().clear().commit()
        val hosts = listOf(
            core.addHost("home", Endpoint.Ssh("home.test", 22u, "me")),
            core.addHost("other", Endpoint.Ssh("other.test", 22u, "me")),
        )
        core.ensureKey()
        compose.setContent {
            key(generation.value, hostNumber.value, terminal.value.address) {
                val scope = rememberCoroutineScope()
                val chat = remember { ChatModel(core, hosts[hostNumber.value], terminal.value).also { it.start(scope) } }
                val calls = remember { CallsModel(core, scope).also { it.watch(hosts, everyHost = true) } }
                DisposableEffect(chat) { onDispose { chat.stop(); calls.stopAll() } }
                ChatScreen(chat, {}, calls = calls)
            }
        }
        push(entries(30))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Reply 30\nSecond line\nThird line").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun readReply(number: Int) {
        compose.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Reply $number\nSecond line\nThird line"))
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitLatest(description: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertReply(number: Int) {
        val text = "Reply $number\nSecond line\nThird line"
        awaitText(text)
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun push(entries: List<ChatEntry>) {
        compose.waitForIdle() // finish navigation before delivering to its new ChatWatch
        core.pushChat(ChatSnapshot(entries, ChatStatus.LIVE, null))
        compose.waitForIdle()
    }

    @Test fun newRowsKeepTheReadingPositionAndLatestResumesFollowing() {
        open()
        assertReply(30)
        readReply(6)
        assertReply(6)

        push(entries(32))

        assertReply(6)
        awaitLatest("Latest (2)")
        compose.onNodeWithContentDescription("Latest (2)").assertIsDisplayed().performClick()
        assertReply(32)
        compose.onNodeWithContentDescription("Latest (2)").assertDoesNotExist()
        push(entries(33))
        assertReply(33)
    }

    @Test fun reopeningRestoresTheEntryAndPixelOffsetAfterAnEmptyInitialSnapshot() {
        open()
        readReply(6)
        compose.onNodeWithTag("chat-transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 25f) }
        push(entries(32))
        val before = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().boundsInRoot.top

        // A fresh ChatModel and composition must read the saved viewport.
        compose.runOnIdle { generation.value++ }
        push(emptyList())
        push(entries(33))

        assertReply(6)
        val after = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 0.5f)
        awaitLatest("Latest (3)")
        compose.onNodeWithContentDescription("Latest (3)").assertIsDisplayed()
    }

    @Test fun reopeningAtThePreviousEndDoesNotSkipRowsThatArrivedWhileAway() {
        open()
        val before = compose.onNodeWithText("Reply 30\nSecond line\nThird line").fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { generation.value++ }
        push(entries(33))

        assertReply(30)
        val after = compose.onNodeWithText("Reply 30\nSecond line\nThird line").fetchSemanticsNode().positionInRoot.y
        assertEquals(before, after, 0.5f)
        awaitLatest("Latest (3)")
        compose.onNodeWithContentDescription("Latest (3)").assertIsDisplayed()
    }

    @Test fun streamingFollowsTheBottomOfAnOversizedRowButDoesNotInterruptHistoryReading() {
        open()
        fun streaming(lines: Int) = entries(29) + ChatEntry("a30", null,
            ChatEntryBody.Agent((1..lines).joinToString("\n") { "Streaming line $it" }))
        fun assertAtEnd(lines: Int) {
            val text = (streaming(lines).last().body as ChatEntryBody.Agent).text
            awaitText(text)
            val node = compose.onNodeWithText(text).fetchSemanticsNode()
            val viewport = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
            val bottom = node.positionInRoot.y + node.size.height
            assertTrue("End of streaming text ($bottom) should be at viewport bottom (${viewport.bottom})",
                bottom <= viewport.bottom && bottom >= viewport.bottom - 40f)
        }
        push(streaming(80))
        assertAtEnd(80)
        push(streaming(100))
        assertAtEnd(100)

        readReply(6)
        push(streaming(120))
        assertReply(6)
        awaitLatest("Latest")
        compose.onNodeWithContentDescription("Latest").assertIsDisplayed()
        compose.onNodeWithContentDescription("Latest (1)").assertDoesNotExist()
    }

    @Test fun prependedHistoryPreservesTheAnchorAndManualReturnToTheBottomClearsTheCount() {
        open()
        readReply(6)
        val before = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().positionInRoot.y
        val older = ChatEntry("older", null, ChatEntryBody.Agent("An older reply"))
        push(listOf(older) + entries(32))
        val after = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().positionInRoot.y
        assertEquals(before, after, 0.5f)
        awaitLatest("Latest (2)")
        compose.onNodeWithContentDescription("Latest (2)").assertIsDisplayed()
        readReply(32)
        compose.onNodeWithTag("chat-transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.onNodeWithContentDescription("Latest (2)").assertDoesNotExist()
        push(listOf(older) + entries(33))
        assertReply(33)
    }

    @Test fun readingPositionsAreIndependentForEachHostAndTerminal() {
        open()
        readReply(6)
        compose.runOnIdle { hostNumber.value = 1 }
        push(entries(30))
        assertReply(30)
        readReply(11)

        compose.runOnIdle { hostNumber.value = 0 }
        push(entries(30))
        assertReply(6)
        compose.runOnIdle { terminal.value = agent.copy(address = "remote/t1") }
        push(entries(30))
        assertReply(30)
        compose.runOnIdle { terminal.value = agent }
        push(entries(30))
        assertReply(6)
    }

    @Test fun aRemovedAnchorFallsBackWithinTheRemainingConversation() {
        open()
        readReply(16)
        compose.runOnIdle { generation.value++ }
        push(listOf(ChatEntry("replacement", null, ChatEntryBody.Agent("New conversation"))))
        awaitText("New conversation")
        compose.onNodeWithText("New conversation").assertIsDisplayed()
        push(listOf(
            ChatEntry("replacement", null, ChatEntryBody.Agent("New conversation")),
            ChatEntry("replacement2", null, ChatEntryBody.Agent("New answer")),
        ))
        awaitText("New answer")
        compose.onNodeWithText("New answer").assertIsDisplayed()
    }

    @Test fun incomingCallsAndTranscriptRowsShareTheLatestCount() {
        open()
        readReply(6)
        compose.runOnIdle {
            core.sendCall(CallInfo("h1", "call1", 1000uL, "t1", true, "Review?", "Ready.",
                CallUrgency.CHECK, listOf("Yes", "Later"), null, true))
        }
        push(entries(31))
        assertReply(6)
        awaitLatest("Latest (2)")
        compose.onNodeWithContentDescription("Latest (2)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Review?").assertIsDisplayed()
    }

    @Test fun markdownBlocksShareOneMessageCountAndRestoreTheBlockBeingRead() {
        open()
        readReply(6)
        val message = ChatEntry("md31", null, ChatEntryBody.Agent(
            "# A heading\n\nParagraph to keep reading\n\n```kotlin\n" +
                (1..80).joinToString("\n") { "val line$it = $it" } + "\n```\n\nEnd of reply",
        ))
        push(entries(30) + message)
        awaitLatest("Latest (1)")
        awaitLatest("Latest (1)")
        compose.onNodeWithContentDescription("Latest (1)").assertIsDisplayed()
        compose.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Paragraph to keep reading"))
        val before = compose.onNodeWithText("Paragraph to keep reading").fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { generation.value++ }
        push(entries(30) + message + ChatEntry("a32", null, ChatEntryBody.Agent("Another response")))

        awaitText("Paragraph to keep reading")
        compose.onNodeWithText("Paragraph to keep reading").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("Paragraph to keep reading").fetchSemanticsNode().positionInRoot.y, 0.5f)
        awaitLatest("Latest (2)")
        compose.onNodeWithContentDescription("Latest (2)").assertIsDisplayed()
    }
}
