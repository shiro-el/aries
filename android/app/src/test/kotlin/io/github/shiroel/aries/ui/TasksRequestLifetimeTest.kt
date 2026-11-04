package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D45 at the Chat UI/fake-core seam: request overlays must outlive streaming lazy rows. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class TasksRequestLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private val core = FakeCore()
    private val generation = androidx.compose.runtime.mutableIntStateOf(0)
    private val base = listOf(ChatEntry("prompt", null, ChatEntryBody.User("Review this task")))
    private fun open(status: AgentStatus = AgentStatus.Working) {
        val host = core.addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
        core.ensureKey()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val chat = remember { ChatModel(core, host,
                TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", status))
                .also { it.start(scope) } }
            val calls = remember { CallsModel(core, scope).also { it.watch(listOf(host), everyHost = true) } }
            DisposableEffect(chat) { onDispose { chat.stop(); calls.stopAll() } }
            val presentation = remember { TasksDetailPresentation(TasksChangesState(), TasksDetailTab.ACTIVITY, null, {}) }
            CompositionLocalProvider(LocalTasksDetail provides presentation) {
                androidx.compose.runtime.key(generation.intValue) { MaterialTheme { ChatScreen(chat, {}, calls = calls) } }
            }
        }
        compose.waitForIdle()
    }

    private fun push(entries: List<ChatEntry>) {
        core.pushChat(ChatSnapshot(entries, ChatStatus.LIVE, null))
        compose.waitForIdle()
    }

    // D105: the screen and its request overlay must emit a single transcript receipt.
    @Test fun requestOverlaySpeaksTranscriptConfirmationOnlyOnce() {
        open(AgentStatus.Blocked(BlockedReason.QUESTION, null))
        val question = ChatEntry("request", null, ChatEntryBody.Question("request_user_input", "Choose",
            listOf(ChatQuestionPart("Continue?", null, false, listOf(ChatQuestionOption("Continue", null)))), ChatToolStatus.RUNNING, null))
        push(base + question)
        val announcements = recordAccessibilityAnnouncements(compose)
        compose.onNodeWithContentDescription("Answer Continue").performClick()
        push(base + question.copy(body = (question.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE)))
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.runOnIdle { org.junit.Assert.assertEquals(1,
            announcements.count { it == context.getString(io.github.shiroel.aries.R.string.chat_question_sent) }) }
    }

    // D53: a second live request arriving behind an open sheet must open when that sheet closes.
    @Test fun nextLiveQuestionOpensAfterDismissingThePreviousRequest() {
        open(AgentStatus.Blocked(BlockedReason.QUESTION, null))
        val first = ChatEntry("first", null, ChatEntryBody.Question("request_user_input", "First request",
            listOf(ChatQuestionPart("First live question", null, false, listOf(ChatQuestionOption("North", null)))), ChatToolStatus.RUNNING, null))
        val second = first.copy(id = "second", body = (first.body as ChatEntryBody.Question).copy(
            title = "Second request", questions = listOf(ChatQuestionPart("Second live question", null, false,
                listOf(ChatQuestionOption("South", null))))))
        push(base + first)
        compose.onNodeWithText("First live question").assertIsDisplayed()
        push(base + first.copy(body = (first.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE)) + second)
        compose.onNodeWithText("First live question").assertIsDisplayed()
        compose.onNodeWithText("Second live question").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Second live question").assertIsDisplayed()
        compose.onNodeWithContentDescription("Answer South").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("second", core.answers.single().second) }
    }

    // D45: a historical question's sheet must pause follow and survive removal of the question row.
    @Test fun questionSheetSurvivesStreamingAndReplacementOfItsSource() {
        open()
        val request = ChatEntry("request", null, ChatEntryBody.Question("AskUserQuestion", "Choose a route",
            listOf(ChatQuestionPart("Which route should we use?", "Route", false,
                listOf(ChatQuestionOption("North", "Take the northern route")))), ChatToolStatus.DONE, null))
        push(base + request)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("tasks-question:request").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tasks-question:request").performClick()
        compose.onNodeWithText("Which route should we use?").assertIsDisplayed()
        val output = (1..40).map { ChatEntry("reply$it", null, ChatEntryBody.Agent("Progress $it\nMore output\nMore output")) }
        push(base + request + output)
        compose.onNodeWithText("Which route should we use?").assertIsDisplayed()
        push(base + output)
        compose.onNodeWithText("Which route should we use?").assertIsDisplayed()
    }

    // D45: the open Call sheet must outlive feed updates that recycle its originating trace.
    @Test fun callSheetSurvivesStreamingAndRemovalOfItsSource() {
        open()
        push(base)
        fun call(number: Int) = CallInfo("h1", "call$number", number.toULong(), "t1", true,
            "Review $number", "Details for review $number", CallUrgency.CHECK, listOf("Yes", "Later"), null, true)
        compose.runOnIdle { core.sendCall(call(1)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("tasks-call:h1/call1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tasks-call:h1/call1").performClick()
        compose.onNodeWithText("Details for review 1").assertIsDisplayed()
        // D101: a Call trace retains its original target when the Activity UI is recreated.
        compose.runOnIdle { generation.intValue++ }
        compose.onNodeWithText("Details for review 1").assertIsDisplayed()

        compose.runOnIdle { (2..40).forEach { core.sendCall(call(it)) } }
        compose.waitForIdle()
        compose.onNodeWithText("Details for review 1").assertIsDisplayed()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(emptyList()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("tasks-call:h1/call1").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Details for review 1").assertIsDisplayed()
    }
}
