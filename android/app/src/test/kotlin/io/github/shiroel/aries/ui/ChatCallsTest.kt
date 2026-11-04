package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.CallAnswer
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallReply
import io.github.shiroel.aries.core.CallReplyOutcome
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat/inbox behavior at the UI/core seam, including live replies made elsewhere. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h2000dp")
class ChatCallsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)
    private val call = CallInfo("h1", "call-secret-123", 1_000uL, "t1", true, "Merge?", "Tests pass.",
        CallUrgency.CHECK, listOf("Merge", "Wait"), null, true)

    private fun answer(state: CallAnswerState) = CallAnswer(1u, "Wait", state, 2_000uL, "reply detail")

    private fun core(entries: List<CallInfo> = listOf(call)) = FakeCore().apply {
        terminals = listOf(agent)
        savedView = AgentView.CHAT
        addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        ensureKey()
        callsOf["h1"] = entries
    }

    private fun openChat(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
    }

    private fun chooseWait() = compose.onNodeWithContentDescription(
        str(R.string.call_answer_choice_label, call.title, "Wait"),
    ).performClick()

    private fun openInboxFromChat() {
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-calls").performClick()
    }

    private fun assertNoCallId() {
        compose.onAllNodes(
            hasText("call-secret-", substring = true) or hasContentDescription("call-secret-", substring = true),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    // D128: answering a Call does not resolve the independent permission prompt in Terminal.
    @Test fun anAnswerableCallKeepsTheTerminalFallbackForABlockedAgent() {
        val core = core()
        openChat(core)
        core.pushChat(io.github.shiroel.aries.core.ChatSnapshot(emptyList(), io.github.shiroel.aries.core.ChatStatus.LIVE, null))
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Blocked(io.github.shiroel.aries.core.BlockedReason.PERMISSION, null))))
        compose.onNodeWithText(str(R.string.chat_blocked_permission)).assertIsDisplayed()
        chooseWait()
        compose.waitForIdle()
        assertEquals(listOf(Triple("h1", call.id, 1u)), core.replies)
    }

    @Test
    fun chatOmitsPastCallsButInboxKeepsTheirHistory() {
        val past = listOf(CallAnswerState.CLAIMED, CallAnswerState.DELIVERED).mapIndexed { index, state ->
            call.copy(id = "call-secret-$index", title = "Past $state", atMs = index.toULong(), answer = answer(state))
        } + call.copy(id = "call-secret-record", title = "Record only", choices = emptyList())
        openChat(core(past + call))
        compose.onNodeWithText(call.title).assertExists()
        past.forEach { compose.onNodeWithText(it.title).assertDoesNotExist() }
        compose.onNodeWithTag("call-inbox-past").assertDoesNotExist()
        openInboxFromChat()
        compose.onNodeWithTag("call-inbox-past").assertExists()
        past.forEach {
            compose.onNodeWithTag("call-inbox-list").performScrollToNode(hasText(it.title))
            compose.onNodeWithText(it.title).assertExists()
            assertNoCallId()
        }
        assertNoCallId()
    }

    @Test
    fun chatAndInboxDoNotExposeCallIdsInTextOrAccessibilityLabels() {
        openChat(core())
        compose.onNodeWithText(call.title).assertExists()
        assertNoCallId()
        compose.onNodeWithText(str(R.string.console_chat_call_label)).assertExists()
        openInboxFromChat()
        assertNoCallId()
    }

    @Test
    fun aReplyMadeElsewhereDisappearsOnTheLiveFeedUpdate() {
        val core = core()
        openChat(core)
        compose.onNodeWithText(call.title).assertExists()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call.copy(answer = answer(CallAnswerState.DELIVERED)))) }
        compose.onNodeWithText(call.title).assertDoesNotExist()
        assertEquals(emptyList<Triple<String, String, UInt>>(), core.replies)
        openInboxFromChat()
        compose.onNodeWithText(call.title).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Wait")).assertExists()
    }

    // Regression: a reply warning must leave Chat when the feed confirms its durable final answer.
    @Test
    fun confirmedFeedAnswerClearsAnEarlierUnknownReplyNoteFromChat() {
        val core = core().apply { replyResult = CallReply(CallReplyOutcome.UNKNOWN, null, null) }
        openChat(core)
        chooseWait()
        compose.onNodeWithText(str(R.string.call_reply_unknown)).assertExists()
        compose.runOnIdle {
            core.callListeners.getValue("h1").onCalls(listOf(call.copy(answer = answer(CallAnswerState.DELIVERED))))
        }
        compose.onNodeWithText(call.title).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.call_reply_unknown)).assertDoesNotExist()
        openInboxFromChat()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Wait")).assertExists()
    }

    @Test
    fun aCallThatBecomesUnanswerableDisappearsOnTheLiveFeedUpdate() {
        val core = core()
        openChat(core)
        compose.onNodeWithText(call.title).assertExists()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call.copy(choices = emptyList()))) }
        compose.onNodeWithText(call.title).assertDoesNotExist()
    }

    @Test
    fun anInFlightReplyStaysVisibleEvenWhenTheFeedAlreadyShowsAnAnswer() {
        val hold = CompletableDeferred<Unit>()
        val core = core().apply {
            replyHold = hold
            replyResult = CallReply(CallReplyOutcome.DELIVERED, answer(CallAnswerState.DELIVERED), null)
        }
        openChat(core)
        chooseWait()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call.copy(answer = answer(CallAnswerState.CLAIMED)))) }
        // A feed receipt must not remove the in-flight reply. The screen's stable status slot
        // owns speech; transcript rows stay quiet when scrolling back through history.
        compose.onNodeWithText(call.title).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_sending)).assertExists()
        hold.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(call.title).assertDoesNotExist()
        assertEquals(listOf(Triple("h1", call.id, 1u)), core.replies)
    }

    @Test
    fun aReplyThatNeverStartedShowsItsErrorAndCanBeRetried() {
        val failure = CoreException.Unreachable("no route")
        val core = core().apply { replyFailure = failure }
        openChat(core)
        chooseWait()
        compose.onNodeWithText(str(R.string.call_reply_not_sent, failure.message.orEmpty())).assertExists()
        core.replyResult = CallReply(CallReplyOutcome.DELIVERED, answer(CallAnswerState.DELIVERED), null)
        chooseWait()
        compose.onNodeWithText(call.title).assertDoesNotExist()
        assertEquals(2, core.replies.size)
    }

    @Test
    fun aFailedLocalReplyKeepsItsRecordedErrorVisible() {
        val hold = CompletableDeferred<Unit>()
        val core = core().apply {
            replyHold = hold
            replyResult = CallReply(CallReplyOutcome.FAILED, answer(CallAnswerState.FAILED), "reply detail")
        }
        openChat(core)
        chooseWait()
        // The feed can record failure before the reply RPC finishes.
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call.copy(answer = answer(CallAnswerState.FAILED)))) }
        hold.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(call.title).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_failed, "Wait")).assertExists()
        compose.onNodeWithText("reply detail").assertExists()
        compose.onNodeWithText(str(R.string.call_reply_unknown)).assertDoesNotExist()
        // D22: the durable FAILED feed receipt must keep the card and allow a new attempt.
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call.copy(answer = answer(CallAnswerState.FAILED)))) }
        compose.onNodeWithText(str(R.string.call_answer_failed, "Wait")).assertExists()
        core.replyResult = CallReply(CallReplyOutcome.DELIVERED, answer(CallAnswerState.DELIVERED), null)
        chooseWait()
        assertEquals(2, core.replies.size)
    }

    // D22: an UNCONFIRMED feed receipt must retain guidance without enabling duplicate typing.
    @Test
    fun anUnconfirmedLocalReplyKeepsItsRecordedResultVisible() {
        val core = core().apply { replyResult = CallReply(CallReplyOutcome.UNCONFIRMED, answer(CallAnswerState.UNCONFIRMED), "reply detail") }
        openChat(core)
        chooseWait()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call.copy(answer = answer(CallAnswerState.UNCONFIRMED)))) }
        compose.onNodeWithText(str(R.string.call_answer_unconfirmed, "Wait")).assertExists()
        compose.onNodeWithText("reply detail").assertExists()
        compose.onNodeWithContentDescription(str(R.string.call_answer_choice_label, call.title, "Wait")).assertDoesNotExist()
        // Explicit acknowledgement hides the warning without erasing the durable result or its recovery route.
        compose.onNodeWithText(str(R.string.call_reply_dismiss)).performClick()
        compose.onNodeWithText(call.title).assertDoesNotExist()
        openInboxFromChat()
        compose.onNodeWithText(str(R.string.call_answer_unconfirmed, "Wait")).assertExists()
        compose.onNodeWithText(str(R.string.call_open_chat)).performClick()
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
        compose.onNodeWithText(call.title).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_unconfirmed, "Wait")).assertExists()
        assertEquals(1, core.replies.size)
    }

    @Test
    fun aReplyRaceKeepsTheOutcomeVisibleAlongsideTheEarlierAnswer() {
        openChat(core().apply { replyResult = CallReply(CallReplyOutcome.ANSWERED, answer(CallAnswerState.DELIVERED), null) })
        chooseWait()
        compose.onNodeWithText(str(R.string.call_reply_answered)).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Wait")).assertExists()
    }
}
