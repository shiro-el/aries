package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
    }

    private fun push(entries: List<ChatEntry>) {
        compose.waitForIdle() // finish navigation before delivering to its new ChatWatch
        core.pushChat(ChatSnapshot(entries, ChatStatus.LIVE, null))
        compose.waitForIdle()
    }

    @Test fun newRowsKeepTheReadingPositionAndLatestResumesFollowing() {
        open()
        compose.onNodeWithText("Reply 30\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(5)
        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertIsDisplayed()

        push(entries(32))

        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithText("Latest (2)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Reply 32\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithText("Latest (2)").assertDoesNotExist()
        push(entries(33))
        compose.onNodeWithText("Reply 33\nSecond line\nThird line").assertIsDisplayed()
    }

    @Test fun reopeningRestoresTheEntryAndPixelOffsetAfterAnEmptyInitialSnapshot() {
        open()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(5)
        compose.onNodeWithTag("chat-transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 25f) }
        push(entries(32))
        val before = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().boundsInRoot.top

        // A fresh ChatModel and composition must read the saved viewport.
        compose.runOnIdle { generation.value++ }
        push(emptyList())
        push(entries(33))

        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertIsDisplayed()
        val after = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 0.5f)
        compose.onNodeWithText("Latest (3)").assertIsDisplayed()
    }

    @Test fun streamingFollowsTheBottomOfAnOversizedRowButDoesNotInterruptHistoryReading() {
        open()
        fun streaming(lines: Int) = entries(29) + ChatEntry("a30", null,
            ChatEntryBody.Agent((1..lines).joinToString("\n") { "Streaming line $it" }))
        fun assertAtEnd(lines: Int) {
            val text = (streaming(lines).last().body as ChatEntryBody.Agent).text
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

        compose.onNodeWithTag("chat-transcript").performScrollToIndex(5)
        push(streaming(120))
        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithText("Latest").assertIsDisplayed()
        compose.onNodeWithText("Latest (1)").assertDoesNotExist()
    }

    @Test fun prependedHistoryPreservesTheAnchorAndManualReturnToTheBottomClearsTheCount() {
        open()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(5)
        val before = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().positionInRoot.y
        val older = ChatEntry("older", null, ChatEntryBody.Agent("An older reply"))
        push(listOf(older) + entries(32))
        val after = compose.onNodeWithText("Reply 6\nSecond line\nThird line").fetchSemanticsNode().positionInRoot.y
        assertEquals(before, after, 0.5f)
        compose.onNodeWithText("Latest (2)").assertIsDisplayed()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(32)
        compose.onNodeWithText("Latest (2)").assertDoesNotExist()
        push(listOf(older) + entries(33))
        compose.onNodeWithText("Reply 33\nSecond line\nThird line").assertIsDisplayed()
    }

    @Test fun readingPositionsAreIndependentForEachHostAndTerminal() {
        open()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(5)
        compose.runOnIdle { hostNumber.value = 1 }
        push(entries(30))
        compose.onNodeWithText("Reply 30\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(10)

        compose.runOnIdle { hostNumber.value = 0 }
        push(entries(30))
        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertIsDisplayed()
        compose.runOnIdle { terminal.value = agent.copy(address = "remote/t1") }
        push(entries(30))
        compose.onNodeWithText("Reply 30\nSecond line\nThird line").assertIsDisplayed()
        compose.runOnIdle { terminal.value = agent }
        push(entries(30))
        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertIsDisplayed()
    }

    @Test fun aRemovedAnchorFallsBackWithinTheRemainingConversation() {
        open()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(15)
        compose.runOnIdle { generation.value++ }
        push(listOf(ChatEntry("replacement", null, ChatEntryBody.Agent("New conversation"))))
        compose.onNodeWithText("New conversation").assertIsDisplayed()
        push(listOf(
            ChatEntry("replacement", null, ChatEntryBody.Agent("New conversation")),
            ChatEntry("replacement2", null, ChatEntryBody.Agent("New answer")),
        ))
        compose.onNodeWithText("New answer").assertIsDisplayed()
    }

    @Test fun incomingCallsAndTranscriptRowsShareTheLatestCount() {
        open()
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(5)
        compose.runOnIdle {
            core.sendCall(CallInfo("h1", "call1", 1000uL, "t1", true, "Review?", "Ready.",
                CallUrgency.CHECK, listOf("Yes", "Later"), null, true))
        }
        push(entries(31))
        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithText("Latest (2)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Review?").assertIsDisplayed()
    }
}
