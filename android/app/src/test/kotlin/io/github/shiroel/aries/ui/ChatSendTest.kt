package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatQuestionOption
import io.github.shiroel.aries.core.ChatQuestionPart
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.SendOutcome
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
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
        core.savedView = AgentView.CHAT // the Agent opens as its Chat
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
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
    fun sentMessagesCanBeSelectedEditedAndClearedFromInputHistory() {
        val conn = openChat(FakeCore())
        val input = compose.onNodeWithContentDescription(str(R.string.chat_input_label))
        input.performTextInput("first\n한국어")
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.onNodeWithContentDescription("Input history").performClick()
        compose.onNode(hasText("first\n한국어") and hasClickAction()).performClick()
        input.assertTextEquals("first\n한국어")
        assertEquals(listOf("t1" to "first\n한국어"), conn.typed)
        input.performTextReplacement("edited message")
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        assertEquals(listOf("t1" to "first\n한국어", "t1" to "edited message"), conn.typed)
        compose.onNodeWithContentDescription("Input history").performClick()
        compose.onNodeWithText("Clear history").performClick()
        compose.onNodeWithText("No input history yet.").assertExists()
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun enterAddsANewlineAndOnlyTheSendActionSubmits() {
        val conn = openChat(FakeCore())
        val input = compose.onNodeWithContentDescription(str(R.string.chat_input_label))
        input.performClick().performTextInput("첫 줄")
        input.performKeyInput { pressKey(Key.Enter) }
        input.performTextInput("second line\n\n마지막 줄")
        val message = "첫 줄\nsecond line\n\n마지막 줄"
        input.assertTextEquals(message)
        assertTrue(conn.typed.isEmpty())

        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to message), conn.typed)
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun inputAndSendShareOneLineHeightAndSendStaysAtTheBottom() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a1", null, ChatEntryBody.Agent("Latest reply"))), ChatStatus.LIVE, null))
        // Measure only after the background Markdown parse reaches the transcript.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Latest reply").fetchSemanticsNodes().isNotEmpty()
        }
        val input = compose.onNodeWithContentDescription(str(R.string.chat_input_label))
        val send = compose.onNodeWithText(str(R.string.chat_send_button))
        input.performTextInput("line 1")
        val one = input.fetchSemanticsNode().boundsInRoot
        val button = send.fetchSemanticsNode().boundsInRoot
        assertEquals(one.height, button.height, 0.5f)
        assertEquals(one.top, button.top, 0.5f)
        assertEquals(one.bottom, button.bottom, 0.5f)
        fun baseline(last: Boolean = false): Float {
            val coords = input.fetchSemanticsNode().layoutInfo.coordinates
            return coords.positionInRoot().y + coords[if (last) LastBaseline else FirstBaseline]
        }
        fun sendBaseline(): Float {
            val coords = send.fetchSemanticsNode().layoutInfo.coordinates
            return coords.positionInRoot().y + coords[FirstBaseline]
        }
        assertEquals(baseline(), sendBaseline(), 0.5f)

        input.performTextReplacement((1..6).joinToString("\n") { "line $it" })
        val six = input.fetchSemanticsNode().boundsInRoot
        val bottomButton = send.fetchSemanticsNode().boundsInRoot
        assertTrue(six.height > one.height)
        assertEquals(button.height, bottomButton.height, 0.5f)
        assertEquals(six.bottom, bottomButton.bottom, 0.5f)
        assertEquals(baseline(last = true), sendBaseline(), 0.5f)

        input.performTextReplacement((1..20).joinToString("\n") { "line $it" })
        assertEquals(six.height, input.fetchSemanticsNode().boundsInRoot.height, 0.5f)
        input.assertIsDisplayed()
        send.assertIsDisplayed()
        compose.onNodeWithText("Latest reply").assertIsDisplayed()
        // The capped input keeps the entire draft.
        assertEquals(
            AnnotatedString((1..20).joinToString("\n") { "line $it" }),
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText],
        )
    }

    @Test
    fun endedChatPreservesTheDraftAndNeverSendsItToTheShell() {
        val core = FakeCore()
        val conn = openChat(core)
        val input = compose.onNodeWithContentDescription(str(R.string.chat_input_label))
        input.performTextInput("please run the tests")

        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.ENDED, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        assertTrue(conn.typed.isEmpty())
        input.assertTextEquals("please run the tests")
        input.assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.chat_send_button)).assertIsNotEnabled()

        // A stale LIVE transcript must also be read-only once the pane is a shell.
        core.push(listOf(claude.copy(agentStatus = null)))
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        assertTrue(conn.typed.isEmpty())
        input.assertTextEquals("please run the tests")
        input.assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.terminal_open_button)).performClick()
        compose.waitForIdle()
        assertEquals("t1", conn.views.single().address)
    }

    @Test
    fun aMessageGoesIntoTheAgentsTerminal() {
        val core = FakeCore()
        val conn = openChat(core)
        compose.onNodeWithText(str(R.string.chat_send_button)).assertIsNotEnabled()

        type("now add a test")

        assertEquals(listOf("t1" to "now add a test"), conn.typed)
        compose.onNodeWithText("now add a test").assertExists()
        compose.onNodeWithText(str(R.string.chat_queue_agent)).assertExists()
    }

    @Test
    fun whileSomeoneTypesAtTheHostTheMessageIsShownHeld() {
        val core = FakeCore()
        val conn = openChat(core)
        val hold = CompletableDeferred<Unit>()
        conn.sendHold = hold

        type("after you")
        compose.onNodeWithText(str(R.string.chat_send_held)).assertExists()
        compose.onNodeWithText("after you").assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_queue_cancel_button)).assertDoesNotExist()
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
        compose.onNodeWithContentDescription("Input history").performClick()
        compose.onNodeWithText("No input history yet.").assertExists()
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()

        compose.onNodeWithText(str(R.string.terminal_open_button)).performClick()
        compose.waitForIdle()
        assertEquals("t1", conn.views.single().address)
        assertEquals(true, conn.views.single().options.observe)

        // Back from the Terminal is the Chat, still live.
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("console-top-bar") and hasAnyDescendant(hasText("claude"))).assertExists()
        compose.onNodeWithText("go on").assertExists()
    }

    @Test
    fun theToggleOnTheTerminalOfABlockedNoticeReturnsToTheSameChat() {
        val core = FakeCore()
        val conn = openChat(core)
        core.push(listOf(claude.copy(agentStatus = permission, needsAttention = true)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_open_button)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("console-top-bar") and hasAnyDescendant(hasText("claude"))).assertExists()
        assertEquals("the Chat stayed live, not reopened", 1, core.chatsOpened.size)
        assertEquals(true, conn.views.single().detached)

        // Back is the Host, not the Terminal again.
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").assertExists()
        compose.onNode(hasTestTag("console-top-bar") and hasAnyDescendant(hasText("claude"))).assertDoesNotExist()
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
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
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

    @Test
    fun aQuestionTheChatShowsIsAnsweredThereNotSentToTheTerminal() {
        val core = FakeCore()
        openChat(core)
        val asks = AgentStatus.Blocked(BlockedReason.QUESTION, "Which color?")
        core.push(listOf(claude.copy(agentStatus = asks, needsAttention = true)))
        compose.waitForIdle()
        // No open question in the Chat (yet): point to the Terminal.
        compose.onNodeWithText(str(R.string.chat_blocked_question)).assertExists()

        val question = ChatEntry(
            "toolu_q", null,
            ChatEntryBody.Question(
                "AskUserQuestion", "Which color?",
                listOf(ChatQuestionPart("Which color?", null, false, listOf(ChatQuestionOption("Red", null), ChatQuestionOption("Blue", null)))),
                ChatToolStatus.RUNNING, null,
            ),
        )
        core.pushChat(ChatSnapshot(listOf(question), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_blocked_question)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("t1", "toolu_q", listOf(listOf(1u)))), core.answers)
    }
}
