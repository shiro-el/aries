package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ChatQuestionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun question(id: String, prompt: String = "Color?", name: String = "AskUserQuestion") = ChatEntry(id, null,
        ChatEntryBody.Question(name, prompt, listOf(ChatQuestionPart(prompt, null, false,
            listOf(ChatQuestionOption("Red", null), ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))

    private fun open(core: FakeCore, status: AgentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, null)) {
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", status, true, 0uL))
        core.savedView = AgentView.CHAT
        core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
    }

    @Test fun freeTextUsesTheQuestionApiAndCannotBeSentTwice() {
        val core = FakeCore()
        open(core)
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_text_label, "Color?")).performTextInput("보라색")
        compose.onNodeWithText(str(R.string.chat_question_send)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(listOf<String?>("보라색")), core.textAnswers.map { it.second })
        assertTrue(core.connection!!.typed.isEmpty())
        compose.onNodeWithText(str(R.string.chat_question_send)).assertIsNotEnabled()
    }

    @Test fun queuedCodexRequestsAdvanceAfterTheFirstGetsAResult() {
        val core = FakeCore()
        open(core)
        val first = question("q1", "First?", "request_user_input")
        val second = question("q2", "Second?", "request_user_input")
        core.pushChat(ChatSnapshot(listOf(first, second), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertCountEquals(1)
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals("q1", core.answers.single().second)
        val done = first.copy(body = (first.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE, output = "Blue"))
        core.pushChat(ChatSnapshot(listOf(done, second), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Red")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("q1", "q2"), core.answers.map { it.second })
    }

    @Test fun latestUnansweredHistoryUsesAMessageAndOlderQuestionsStayReadOnly() {
        val core = FakeCore()
        open(core, AgentStatus.Idle)
        core.pushChat(ChatSnapshot(listOf(question("old", "Old?"), question("latest")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.chat_question_history)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to "Blue"), core.connection!!.typed)
        assertTrue(core.answers.isEmpty())
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertIsNotEnabled()
    }
    @Test fun historyRefusedByANewPermissionShowsTheFailureAndTypesNothing() {
        val core = FakeCore()
        open(core, AgentStatus.Idle)
        core.connection!!.sendOutcome = SendOutcome.Blocked(BlockedReason.PERMISSION, "Allow Write?")
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertTrue(core.connection!!.typed.isEmpty())
        compose.onNodeWithText(str(R.string.chat_answer_gone)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
    }

}
