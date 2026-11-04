package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
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
    private val compositionGeneration = mutableStateOf(0)
    private val hostNumber = mutableStateOf(0)
    private val terminal = mutableStateOf(agent)
    private lateinit var currentChat: ChatModel
    private fun entries(count: Int) = (1..count).map {
        ChatEntry("a$it", null, ChatEntryBody.Agent("Reply $it\nSecond line\nThird line"))
    }

    private fun open(connected: Boolean = false, thread: Boolean = false) {
        val hosts = listOf(
            core.addHost("home", Endpoint.Ssh("home.test", 22u, "me")),
            core.addHost("other", Endpoint.Ssh("other.test", 22u, "me")),
        )
        core.ensureKey()
        compose.setContent {
            key(generation.value, hostNumber.value, terminal.value.address) {
                val scope = rememberCoroutineScope()
                val chat = remember {
                    val host = hosts[hostNumber.value]
                    val first = FakeConnection(emptyList(), io.github.shiroel.aries.core.HostStructure(emptyList(), emptyList(), null))
                    val link = if (connected) HostLink(host, first, scope, { first }) else null
                    ChatModel(core, host, terminal.value, link).also { currentChat = it; it.start(scope) }
                }
                val calls = remember { CallsModel(core, scope).also { it.watch(hosts, everyHost = true) } }
                DisposableEffect(chat) { onDispose { chat.stop(); calls.stopAll() } }
                val threadModel = remember { if (thread) HostsModel(core, scope) else null }
                CompositionLocalProvider(LocalThreadChat provides threadModel) {
                    key(compositionGeneration.value) { ChatScreen(chat, {}, calls = calls) }
                }
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

    // Regression: expanding the latest tool must not make follow mode scroll its header to the top.
    @Test fun expandingTheLatestToolKeepsItsReadingPosition() {
        open()
        val tool = ChatEntry("long-tool", null, ChatEntryBody.Tool(
            "Bash", "gradle lint", "{}", io.github.shiroel.aries.core.ChatToolStatus.DONE,
            (1..100).joinToString("\n") { "Output line $it" },
        ))
        push(entries(30) + tool)
        val label = context.getString(io.github.shiroel.aries.R.string.chat_tool_expand_label, "Bash")
        val header = compose.onNode(hasToolAction(label))
        header.assertIsDisplayed()
        val before = header.fetchSemanticsNode().positionInRoot.y

        header.performClick()
        compose.waitForIdle()

        header.assertIsDisplayed()
        assertEquals(before, header.fetchSemanticsNode().positionInRoot.y, 0.5f)
        compose.onNodeWithText((tool.body as ChatEntryBody.Tool).output!!).assertExists()
        // The Latest overlay is centered; tap the leading disclosure itself.
        header.performTouchInput { click(androidx.compose.ui.geometry.Offset(24f, height / 2f)) }
        compose.waitForIdle()
        header.assertIsDisplayed()
        compose.onNodeWithText((tool.body as ChatEntryBody.Tool).output!!).assertDoesNotExist()
        assertEquals(before, header.fetchSemanticsNode().positionInRoot.y, 0.5f)
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

    // D42: data delivered during the Latest animation must retarget the jump and resume follow.
    @Test fun latestKeepsFollowingWhenRowsArriveDuringItsAnimation() {
        open()
        readReply(6)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Latest").performClick()
        compose.mainClock.advanceTimeByFrame()
        push(entries(60))
        compose.mainClock.advanceTimeBy(32)
        push(entries(90))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertReply(90)
        compose.onNodeWithContentDescription("Latest (60)").assertDoesNotExist()
        push(entries(91))
        assertReply(91)
    }

    // D43: a user-input scroll cancelling prepend correction must not kill unread/Latest for this visit.
    @Test fun draggingDuringAPrependKeepsTheScrollObserverAlive() {
        open()
        readReply(16)
        compose.onNodeWithTag("chat-transcript").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 100f))
        }
        val older = ChatEntry("older", null, ChatEntryBody.Agent("Earlier history"))
        push(listOf(older) + entries(32))
        compose.onNodeWithTag("chat-transcript").performTouchInput { up() }
        compose.waitForIdle()
        push(listOf(older) + entries(33))

        awaitLatest("Latest (3)")
        compose.onNodeWithContentDescription("Latest (3)").performClick()
        assertReply(33)
        push(listOf(older) + entries(34))
        assertReply(34)
    }

    // Regression: reopening a read Chat must discard its old viewport/count and follow new output.
    @Test fun reopeningStartsAtLatestAfterAnEmptyInitialSnapshot() {
        open()
        readReply(6)
        push(entries(32))
        awaitLatest("Latest (2)")

        compose.runOnIdle { generation.value++ }
        push(emptyList())
        push(entries(33))

        assertReply(33)
        compose.onNodeWithContentDescription("Latest (3)").assertDoesNotExist()
        compose.onNodeWithContentDescription("Latest").assertDoesNotExist()
        push(entries(34))
        assertReply(34) // following is true without tapping Latest
    }

    @Test fun reopeningAtThePreviousEndStartsAtTheNewEndAndFollows() {
        open()
        compose.runOnIdle { generation.value++ }
        push(entries(33))

        assertReply(33)
        compose.onNodeWithContentDescription("Latest (3)").assertDoesNotExist()
        push(entries(34))
        assertReply(34)
    }

    @Test fun streamingFollowsTheBottomOfAnOversizedRowButDoesNotInterruptHistoryReading() {
        open()
        fun streaming(lines: Int) = entries(29) + ChatEntry("a30", null,
            ChatEntryBody.Agent((1..lines).joinToString("\n") { "Streaming line $it" }))
        fun assertAtEnd(lines: Int) {
            val text = "Streaming line $lines"
            // Long prose is split into lazy blocks; observe the final line's block.
            compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNodeWithText(text, substring = true).fetchSemanticsNode()
            val viewport = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
            val bottom = node.positionInRoot.y + node.size.height
            assertTrue("End of streaming text ($bottom) should be visible in viewport ($viewport)",
                bottom <= viewport.bottom && bottom >= viewport.top)
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
        compose.onNodeWithContentDescription("Latest").performTouchInput { click() }
        assertAtEnd(120)
        compose.onNodeWithContentDescription("Latest").assertDoesNotExist()
        push(streaming(130))
        assertAtEnd(130)
    }

    @Test fun latestReachesTheLastChoiceOfAnOversizedQuestion() {
        open()
        readReply(6)
        val question = ChatEntry("question", null, ChatEntryBody.Question(
            "AskUserQuestion", "Choose a destination",
            listOf(io.github.shiroel.aries.core.ChatQuestionPart(
                "Choose a destination", "Destination", false,
                (1..20).map { io.github.shiroel.aries.core.ChatQuestionOption("Destination $it", "Go there") },
            )), io.github.shiroel.aries.core.ChatToolStatus.RUNNING, null,
        ))
        push(entries(30) + question)
        awaitLatest("Latest (1)")

        compose.onNodeWithContentDescription("Latest (1)").performTouchInput { click() }

        awaitText("Destination 20")
        compose.onNodeWithText("Destination 20").assertIsDisplayed()
        compose.onNodeWithContentDescription("Latest (1)").assertDoesNotExist()
        push(entries(30) + question + ChatEntry("after", null, ChatEntryBody.Agent("The latest answer")))
        awaitText("The latest answer")
        compose.onNodeWithText("The latest answer").assertIsDisplayed()
    }

    @Test fun editingAVisibleHistoryEntryUpdatesItsTextWithoutChangingRowKeys() {
        open()
        readReply(6)
        assertReply(6)

        val updated = entries(30).map {
            if (it.id == "a6") it.copy(body = ChatEntryBody.Agent("Corrected reply\nSecond line\nThird line")) else it
        }
        push(updated)

        awaitText("Corrected reply\nSecond line\nThird line")
        compose.onNodeWithText("Corrected reply\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithText("Reply 6\nSecond line\nThird line").assertDoesNotExist()
        compose.onNodeWithContentDescription("Latest").assertIsDisplayed()
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

    @Test fun switchingHostsAndTerminalsAlwaysOpensAtLatest() {
        open()
        readReply(6)
        compose.runOnIdle { hostNumber.value = 1 }
        push(entries(30))
        assertReply(30)
        readReply(11)

        compose.runOnIdle { hostNumber.value = 0 }
        push(entries(30))
        assertReply(30)
        compose.runOnIdle { terminal.value = agent.copy(address = "remote/t1") }
        push(entries(30))
        assertReply(30)
        compose.runOnIdle { terminal.value = agent }
        push(entries(30))
        assertReply(30)
    }

    @Test fun aRemovedAnchorFallsBackWithinTheRemainingConversation() {
        open()
        readReply(16)
        compose.runOnIdle { compositionGeneration.value++ }
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

    // D44: a status footer must neither add unread content nor hide Calls inserted before it.
    @Test fun workingFooterDoesNotCountOrMaskNewCalls() {
        terminal.value = agent.copy(agentStatus = AgentStatus.Done)
        open(connected = true)
        readReply(6)
        compose.runOnIdle { currentChat.update(listOf(agent)) }
        awaitLatest("Latest")
        compose.onNodeWithContentDescription("Latest (1)").assertDoesNotExist()
        compose.runOnIdle {
            core.sendCall(CallInfo("h1", "call1", 1000uL, "t1", true, "Review?", "Ready.",
                CallUrgency.CHECK, listOf("Yes", "Later"), null, true))
        }
        awaitLatest("Latest (1)")
        push(entries(31))
        awaitLatest("Latest (2)")
        compose.onNodeWithContentDescription("Latest (2)").performClick()
        compose.onNodeWithText("Review?").assertIsDisplayed()
    }

    // D74's unread portion: a growing Thread tool group is one unread activity, regardless of tool count.
    @Test fun threadCountsEachToolGroupOnceAsItGrows() {
        open(thread = true)
        readReply(6)
        fun tool(id: String) = ChatEntry(id, null, ChatEntryBody.Tool("Read", id, "{}",
            io.github.shiroel.aries.core.ChatToolStatus.DONE, "Read successfully"))
        push(entries(30) + tool("tool1") + tool("tool2"))
        awaitLatest("Latest (1)")
        push(entries(30) + tool("tool1") + tool("tool2") + tool("tool3"))
        awaitLatest("Latest (1)")
        push(entries(30) + tool("tool1") + tool("tool2") + tool("tool3") +
            ChatEntry("after-tools", null, ChatEntryBody.Agent("Finished reading")) + tool("tool4"))
        awaitLatest("Latest (3)")
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
        compose.runOnIdle { compositionGeneration.value++ }
        push(entries(30) + message + ChatEntry("a32", null, ChatEntryBody.Agent("Another response")))

        awaitText("Paragraph to keep reading")
        compose.onNodeWithText("Paragraph to keep reading").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("Paragraph to keep reading").fetchSemanticsNode().positionInRoot.y, 0.5f)
        awaitLatest("Latest (2)")
        compose.onNodeWithContentDescription("Latest (2)").assertIsDisplayed()
    }
}
