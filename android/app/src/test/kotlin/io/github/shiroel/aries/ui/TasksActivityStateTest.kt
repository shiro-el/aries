package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat UI/fake-core seam: Tasks overlays and plan activity must follow current state. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class TasksActivityStateTest {
    @get:Rule val compose = createComposeRule()
    private val core = FakeCore()
    private lateinit var chat: ChatModel
    private val restoration = StateRestorationTester(compose)
    private val question = ChatEntry("request", null, ChatEntryBody.Question("AskUserQuestion", "Choose a route",
        listOf(ChatQuestionPart("Which route should we use?", "Route", false,
            listOf(ChatQuestionOption("North", null), ChatQuestionOption("South", null)))), ChatToolStatus.RUNNING, null))
    private val blocked = AgentStatus.Blocked(BlockedReason.QUESTION, null)

    private fun open(status: AgentStatus = blocked) {
        val host = core.addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
        core.ensureKey()
        val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", status)
        val connection = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null), core = core)
        restoration.setContent {
            val scope = rememberCoroutineScope()
            val link = remember { HostLink(host, connection, scope, { connection }) }
            // Like the Activity's ViewModel, this model outlives composition restoration.
            if (!::chat.isInitialized) chat = ChatModel(core, host, terminal, link)
            DisposableEffect(scope) { chat.start(scope); onDispose { chat.stop() } }
            val presentation = remember { TasksDetailPresentation(TasksChangesState(), TasksDetailTab.ACTIVITY, null, {}) }
            CompositionLocalProvider(LocalTasksDetail provides presentation) {
                MaterialTheme { ChatScreen(chat, {}) }
            }
        }
        compose.waitForIdle()
    }

    private fun push(entries: List<ChatEntry>, status: ChatStatus = ChatStatus.LIVE) {
        core.pushChat(ChatSnapshot(entries, status, null))
        compose.waitForIdle()
    }

    // D46: dismissing a live question must survive row recycling, reconnect and rotation.
    @Test fun dismissedQuestionStaysClosedAfterScrollingReconnectAndCompositionRestoration() {
        open()
        push(listOf(question))
        compose.onNodeWithText("Which route should we use?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.waitForIdle()
        val output = (1..40).map { ChatEntry("reply$it", null, ChatEntryBody.Agent("Progress $it\nMore output\nMore output")) }
        push(listOf(question) + output)
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(0)
        compose.onNodeWithText("Which route should we use?").assertDoesNotExist()
        push(listOf(question), ChatStatus.RECONNECTING)
        push(listOf(question))
        compose.onNodeWithText("Which route should we use?").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Which route should we use?").assertDoesNotExist()
        compose.onNodeWithTag("tasks-question:request").performClick()
        compose.onNodeWithText("Which route should we use?").assertIsDisplayed()
    }

    // D46: restoring a dispatched answer must show recovery rather than auto-opening an empty answer sheet.
    @Test fun unconfirmedAnswerShowsRecoveryWithoutAutomaticallyOpeningTheQuestion() {
        core.saveChatOutbox("h1", "t1", ChatOutboxState("", emptyList(), emptyList(),
            picks = mapOf("request" to listOf(setOf(0))), answers = mapOf("request" to "sending")).encode())
        open()
        push(listOf(question))
        compose.onNodeWithText("Which route should we use?").assertDoesNotExist()
        compose.onNodeWithTag("tasks-question:request").performClick()
        compose.onNodeWithText("Which route should we use?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Answer North").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertTrue(core.answers.isEmpty()) }
    }

    // D49: successive matches in one closed request must expose the answer and notes, with active spans.
    @Test fun searchRevealsRecordedAnswerAndNotesWithoutOpeningARequestSheet() {
        open(AgentStatus.Idle)
        val body = question.body as ChatEntryBody.Question
        val done = question.copy(body = body.copy(status = ChatToolStatus.DONE, questions = body.questions.map {
            it.copy(answer = ChatQuestionAnswer(emptyList(), "Scenic route answer", "Scenic route notes"))
        }))
        push(listOf(done))
        compose.onNodeWithText("Scenic route answer").assertDoesNotExist()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithContentDescription("Search Chat").performClick()
        compose.onNodeWithContentDescription("Search cached Chat").performTextInput("Scenic")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 of 2 matches").fetchSemanticsNodes().isNotEmpty() }
        fun assertActiveMatch(text: String) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            val rendered = node.config[SemanticsProperties.Text].single()
            org.junit.Assert.assertTrue(rendered.getStringAnnotations("chat-search", 0, 6).any { it.item == "active" })
        }
        assertActiveMatch("Scenic route answer")
        compose.onNodeWithContentDescription("Next").performClick()
        assertActiveMatch("Scenic route notes")
        compose.onNodeWithText("Which route should we use?").assertDoesNotExist()
    }

    // D50: stale plan steps must stop animating on Agent/connection changes, and resume only while live.
    @Test fun planAnimationFollowsAgentAndConnectionRatherThanRecordedStepStatus() {
        open(AgentStatus.Working)
        val plan = ChatEntry("plan", null, ChatEntryBody.Tool("update_plan", "Review plan",
            """{"plan":[{"step":"Inspect source","status":"completed"},{"step":"Run checks","status":"in_progress"}]}""",
            ChatToolStatus.DONE, null))
        push(listOf(plan))
        fun assertAnimating(count: Int) {
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate) and
                hasAnyAncestor(hasTestTag("tasks-plan"))).assertCountEquals(count)
            compose.onNodeWithText("Run checks").assertIsDisplayed()
        }
        val terminal = chat.terminal
        assertAnimating(1)
        for (status in listOf(AgentStatus.Done, AgentStatus.Idle, AgentStatus.Error("Failed"), blocked)) {
            compose.runOnIdle { chat.update(listOf(terminal.copy(agentStatus = status))) }
            assertAnimating(0)
        }
        compose.runOnIdle { chat.update(listOf(terminal)) }
        assertAnimating(1)
        push(listOf(plan), ChatStatus.RECONNECTING)
        assertAnimating(0)
        push(listOf(plan))
        assertAnimating(1)
        compose.runOnIdle { chat.update(emptyList()) }
        assertAnimating(0)
        compose.runOnIdle { chat.update(listOf(terminal)); chat.link!!.close() }
        assertAnimating(0)
    }
}
