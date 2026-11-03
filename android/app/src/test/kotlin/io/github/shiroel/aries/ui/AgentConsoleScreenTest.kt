package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CompletableDeferred

/** Console presentation at the existing app UI/core boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class AgentConsoleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)

    @Test
    fun switchingViewsKeepsTheConsoleHeaderAndMetadataInPlace() {
        val core = FakeCore().apply {
            terminals = listOf(agent)
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithTag("console-top-bar").assertExists()
        compose.onNodeWithText("/repo").assertExists()
        compose.onNodeWithText(str(R.string.agent_view_terminal)).assertIsSelected()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle, cwd = "/updated")))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_status_idle)).assertExists()
        compose.onNodeWithText("/updated").assertExists()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
        compose.onNodeWithText(str(R.string.agent_view_chat)).assertIsSelected()
        compose.onNodeWithText("/updated").assertExists()
    }

    @Test
    fun chatShowsOnlyThisAgentsCallsAndAnswersANumberedChoiceInPlace() {
        val hold = CompletableDeferred<Unit>()
        val core = FakeCore().apply {
            terminals = listOf(agent)
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            addHost("other", Endpoint.Ssh("10.0.0.3", 22u, "me"))
            ensureKey()
            val call = CallInfo("h1", "c1", 1_000uL, "t1", true, "Merge?", "Tests pass.",
                CallUrgency.CHECK, listOf("Merge", "Wait"), null, true)
            callsOf["h1"] = listOf(call, call.copy(id = "c2", terminal = "box/t1", title = "Other Terminal"))
            callsOf["h2"] = listOf(call.copy(hostId = "h2", title = "Other Host"))
            replyHold = hold
            replyResult = CallReply(CallReplyOutcome.DELIVERED,
                CallAnswer(1u, "Wait", CallAnswerState.DELIVERED, 2_000uL, null), null)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithText("Merge?").assertExists()
        compose.onNodeWithText("Other Terminal").assertDoesNotExist()
        compose.onNodeWithText("Other Host").assertDoesNotExist()
        compose.onNodeWithText("2").assertExists()
        val label = context.getString(R.string.call_answer_choice_label, "Merge?", "Wait")
        compose.onNodeWithContentDescription(label).performClick()
        compose.onNodeWithText(str(R.string.call_answer_sending)).assertExists()
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
        hold.complete(Unit)
        compose.waitForIdle()
        assertEquals(listOf(Triple("h1", "c1", 1u)), core.replies)
        compose.onNodeWithText("Merge?").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.call_answer_delivered, "Wait")).assertDoesNotExist()
    }
}
