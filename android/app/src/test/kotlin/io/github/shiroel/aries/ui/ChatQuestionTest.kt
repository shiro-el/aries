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
        compose.onNodeWithText(str(R.string.chat_question_send)).assertDoesNotExist()
        compose.onNodeWithText("보라색").assertExists()
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
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
        compose.onNode(hasText("Blue") and isSelected()).assertExists()
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

    @Test fun hostAnswersStaySelectedBesideTheirPromptsAfterReopeningChat() {
        // Regression: no local picks exist after reopening; completed Host
        // answers used to lose their choices and appear as raw tool prose.
        val core = FakeCore()
        open(core, AgentStatus.Working)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("host-q", null, ChatEntryBody.Question(
            "AskUserQuestion", "Scope?", listOf(
                ChatQuestionPart("Scope?", "Scope", true, listOf(
                    ChatQuestionOption("Chat", null), ChatQuestionOption("Terminal", null), ChatQuestionOption("Calls", null))),
                ChatQuestionPart("Color?", "Color", false, listOf(ChatQuestionOption("Blue", null))),
            ), ChatToolStatus.DONE,
            "User has answered your questions: \"Scope?\"=\"Chat, Calls\", \"Color?\"=\"Purple\". You can now continue.",
        ))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNode(hasText("Chat") and isSelected()).assertExists()
        compose.onNode(hasText("Calls") and isSelected()).assertExists()
        compose.onNodeWithText("Purple").assertExists()
        compose.onNodeWithText(str(R.string.chat_question_send)).assertDoesNotExist()
        assertTrue(core.answers.isEmpty())
    }

    @Test fun fourRecordedCodexAnswersKeepMultilineOtherAndNotesWithTheRightPrompt() {
        // Regression: the old newline-flattened output cannot distinguish
        // multiline Other text from the next answer in a multi-question call.
        val core = FakeCore()
        open(core, AgentStatus.Working)
        val parts = listOf(
            ChatQuestionPart("First?", "First", false, listOf(ChatQuestionOption("Blue", null)),
                ChatQuestionAnswer(listOf(0u), null, null)),
            ChatQuestionPart("Second?", "Second", false, listOf(ChatQuestionOption("Green", null)),
                ChatQuestionAnswer(emptyList(), "Purple\nViolet", "Use the darker shade")),
            ChatQuestionPart("Third?", "Third", true, listOf(ChatQuestionOption("A, B", null), ChatQuestionOption("C", null)),
                ChatQuestionAnswer(listOf(0u, 1u), null, null)),
            ChatQuestionPart("Fourth?", "Fourth", false, listOf(ChatQuestionOption("D", null)),
                ChatQuestionAnswer(emptyList(), "Custom fourth answer", null)),
        )
        core.pushChat(ChatSnapshot(listOf(ChatEntry("codex-q", null, ChatEntryBody.Question(
            "request_user_input", "First?", parts, ChatToolStatus.DONE, "{\"answers\":{}}",
        ))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNode(hasText("Blue") and isSelected()).assertExists()
        compose.onNode(hasText("A, B") and isSelected()).assertExists()
        compose.onNode(hasText("C") and isSelected()).assertExists()
        compose.onNodeWithText("Purple\nViolet").assertExists()
        compose.onNodeWithText("Use the darker shade").assertExists()
        compose.onNodeWithText("Custom fourth answer").assertExists()
        compose.onNodeWithText(str(R.string.chat_question_send)).assertDoesNotExist()
        assertTrue(core.answers.isEmpty())
    }



    @Test fun waitingUserInputDoesNotRetireAnOpenQuestion() {
        val core = FakeCore()
        open(core)
        val waiting = ChatEntry("queued-user", null, ChatEntryBody.User("Inspect the other file too.", queued = true))
        core.pushChat(ChatSnapshot(listOf(question("q1"), waiting), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals("q1", core.answers.single().second)
        assertTrue(core.connection!!.typed.isEmpty())
    }
}
