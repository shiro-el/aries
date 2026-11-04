package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CallAnswer
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallReply
import io.github.shiroel.aries.core.CallReplyOutcome
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: answering a Call with a Choice from the inbox, against a fake core. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class CallReplyTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun call(answer: CallAnswer? = null, terminal: String? = "t3") = CallInfo(
        "h1", "c1", 1_000uL, terminal, true, "Merge?", "Tests pass.", CallUrgency.CHECK,
        listOf("Merge", "Wait"), answer, terminal != null,
    )

    private fun answer(text: String, state: CallAnswerState, detail: String? = null) =
        CallAnswer(if (text == "Merge") 0u else 1u, text, state, 2_000uL, detail)

    private fun core(call: CallInfo = call()) = FakeCore().apply {
        addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        ensureKey()
        callsOf["h1"] = listOf(call)
    }

    private fun openCalls(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.waitForIdle()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-calls").performClick()
        compose.waitForIdle()
    }

    private fun choice(text: String) =
        compose.onNodeWithContentDescription(str(R.string.call_answer_choice_label, "Merge?", text))

    @Test
    fun tappingAChoiceAnswersTheCallAndShowsItAnswered() {
        val hold = CompletableDeferred<Unit>()
        val core = core().apply {
            replyHold = hold
            replyResult = CallReply(CallReplyOutcome.DELIVERED, answer("Wait", CallAnswerState.DELIVERED), null)
        }
        openCalls(core)

        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertExists()
        choice("Wait").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("h1", "c1", 1u)), core.replies)
        compose.onNodeWithText(str(R.string.call_answer_sending)).assertExists()
        choice("Merge").assertDoesNotExist()

        hold.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Wait")).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_sending)).assertDoesNotExist()
        choice("Merge").assertDoesNotExist()
        choice("Wait").assertDoesNotExist()
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("call-inbox-past").assertExists()
    }

    @Test
    fun lateClaimAndStaleFeedCannotUndoTheDeliveredAnswer() {
        val hold = CompletableDeferred<Unit>()
        val core = core().apply {
            replyHold = hold
            replyResult = CallReply(CallReplyOutcome.ANSWERED, answer("Wait", CallAnswerState.CLAIMED), null)
        }
        openCalls(core)
        choice("Merge").performClick()
        compose.runOnIdle {
            core.callListeners.getValue("h1").onCalls(listOf(call(answer("Wait", CallAnswerState.DELIVERED))))
        }
        hold.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Wait")).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_claimed, "Wait")).assertDoesNotExist()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call())) }
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Wait")).assertExists()
        choice("Merge").assertDoesNotExist()
    }

    @Test
    fun aReplyThatNeverReachedTheHostSaysNotSentAndCanBeRetried() {
        val failure = CoreException.Unreachable("no route to host")
        val core = core().apply { replyFailure = failure }
        openCalls(core)

        choice("Merge").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_reply_not_sent, failure.message.orEmpty())).assertExists()

        core.replyResult = CallReply(CallReplyOutcome.DELIVERED, answer("Merge", CallAnswerState.DELIVERED), null)
        choice("Merge").performClick()
        compose.waitForIdle()
        assertEquals(2, core.replies.size)
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Merge")).assertExists()
    }

    @Test
    fun aReplyWhoseResultWasLostSaysItMayHaveBeenDelivered() {
        val core = core().apply {
            replyResult = CallReply(CallReplyOutcome.UNKNOWN, null, "the connection ended before the Host answered")
        }
        openCalls(core)

        choice("Merge").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_reply_unknown)).assertExists()
        // Trying again is safe: the Host's claim stops a second typing.
        choice("Merge").assertExists()
    }

    // D26: a pre-claim failure has no answer record to carry its diagnostic.
    @Test
    fun aFailureBeforeClaimStillShowsItsDetail() {
        openCalls(core().apply { replyResult = CallReply(CallReplyOutcome.FAILED, null, "answer lock unavailable") })
        choice("Merge").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("answer lock unavailable", substring = true).assertExists()
        choice("Wait").assertExists()
    }

    @Test
    fun aFailedAnswerShowsWhy() {
        val core = core().apply {
            replyResult = CallReply(
                CallReplyOutcome.FAILED,
                answer("Merge", CallAnswerState.FAILED, "Terminal t3 is gone"),
                "Terminal t3 is gone",
            )
        }
        openCalls(core)

        choice("Merge").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_answer_failed, "Merge")).assertExists()
        compose.onNodeWithText("Terminal t3 is gone").assertExists()
        choice("Wait").assertDoesNotExist()
    }

    @Test
    fun anAlreadyAnsweredCallSaysSoAndShowsTheEarlierAnswer() {
        val core = core().apply {
            replyResult = CallReply(CallReplyOutcome.ANSWERED, answer("Wait", CallAnswerState.DELIVERED), null)
        }
        openCalls(core)

        choice("Merge").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_reply_answered)).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Wait")).assertExists()
    }

    @Test
    fun answeredAndUnconfirmedCallsOfferNoChoices() {
        openCalls(core(call(answer = answer("Merge", CallAnswerState.UNCONFIRMED))))
        compose.onNodeWithText(str(R.string.call_answer_unconfirmed, "Merge")).assertExists()
        choice("Wait").assertDoesNotExist()
    }

    @Test
    fun aCallFromAnUnconfirmedCallerOffersNoChoices() {
        openCalls(core(call(terminal = null)))
        choice("Merge").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.call_choices, "Merge · Wait")).assertExists()
    }
}
