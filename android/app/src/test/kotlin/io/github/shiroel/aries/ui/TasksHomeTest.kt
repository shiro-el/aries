package io.github.shiroel.aries.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression: successful answers advance once; the last success closes the queue. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksHomeTest {
    init {
        System.getenv("ARIES_TASKS_FONT")?.toFloatOrNull()?.let(org.robolectric.RuntimeEnvironment::setFontScale)
    }
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val callTime = System.currentTimeMillis().toULong() - 60_000uL

    private fun call(id: String, terminal: String, urgency: CallUrgency) = CallInfo(
        "h1", id, callTime, terminal, true, id, "Decide $id", urgency, listOf("Continue", "Wait"), null, true)

    private fun show(core: FakeCore) {
        val prefs = context.getSharedPreferences("tasks-home-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ThemeSettings(prefs).apply {
            chooseLayout(AppLayout.TASKS)
            chooseMode(if (System.getenv("ARIES_TASKS_THEME") == "dark") ThemeMode.DARK else ThemeMode.LIGHT)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope).apply { start() } }
            AriesApp(model, settings)
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String, tag: String? = null) {
        System.getenv("ARIES_TASKS_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                (if (tag == null) compose.onRoot() else if (tag == "tasks-needs-sheet") compose.onNode(isDialog()) else compose.onNodeWithTag(tag)).captureToImage()
                    .asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    // Regression: a shared question owner must not stop at the first answer or replay it
    // when the Terminal list remains Blocked while the Host catches up.
    @Test fun questionThenPermissionUseCheckedAnswersAndDoNotReappearFromAStaleList() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(
                TerminalInfo("t1", "Question task", "", listOf("codex"), "/repo", "live", AgentStatus.Blocked(BlockedReason.QUESTION, "Choose color"), statusMs = System.currentTimeMillis().toULong() - 60_000uL),
                TerminalInfo("t2", "Permission task", "", listOf("claude"), "/repo", "live", AgentStatus.Blocked(BlockedReason.PERMISSION, "Run build"), statusMs = System.currentTimeMillis().toULong() - 120_000uL),
                TerminalInfo("t3", "Run checks", "", listOf("codex"), "/repo", "live", AgentStatus.Working, statusMs = System.currentTimeMillis().toULong()),
                TerminalInfo("t4", "Finished migration", "", listOf("claude"), "/repo", "live", AgentStatus.Done),
            )
        }
        show(core)
        screenshot("home")
        compose.onNodeWithTag("tasks-view-toggle").performClick()
        screenshot("hosts-grouped")
        compose.onNodeWithTag("tasks-view-toggle").performClick()
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        val question = ChatEntry("q1", null, ChatEntryBody.Question("request_user_input", "Color?",
            listOf(ChatQuestionPart("Color?", null, false, listOf(ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(question), ChatStatus.LIVE, null)) }
        screenshot("question", "tasks-needs-sheet")
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Blue")).performClick()
        compose.onNode(hasText("Permission task") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        val permission = ChatEntry("permission:current", null, ChatEntryBody.Question("permission", "Run build?",
            listOf(ChatQuestionPart("Run build?", null, false, listOf(ChatQuestionOption("Allow", null)))), ChatToolStatus.RUNNING, null))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(permission), ChatStatus.LIVE, null)) }
        screenshot("permission", "tasks-needs-sheet")
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, context.getString(R.string.chat_permission_allow))).performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("t1" to "q1", "t2" to "permission:current"), core.answers.map { it.first to it.second })
        }
    }

    // Regression: uncertain/failed delivery must not consume a need and silently move to another one.
    @Test fun uncertainReplyStaysOnTheCurrentNeedUntilASuccessfulRetry() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            ensureKey()
            callsOf["h1"] = listOf(call("first", "t1", CallUrgency.CHECK), call("second", "t2", CallUrgency.CHECK))
            replyResult = CallReply(CallReplyOutcome.UNKNOWN, null, "Lost acknowledgement")
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        val choice = context.getString(R.string.call_answer_choice_label, "first", "Continue")
        compose.onNodeWithContentDescription(choice).performClick()
        compose.onNodeWithContentDescription(choice).assertExists()
        compose.onNodeWithText(context.getString(R.string.call_reply_unknown)).assertExists()
        compose.runOnIdle { core.replyResult = CallReply(CallReplyOutcome.DELIVERED,
            CallAnswer(0u, "Continue", CallAnswerState.DELIVERED, 2000uL, null), null) }
        compose.onNodeWithContentDescription(choice).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, "second", "Continue")).assertExists()
    }

    // Regression: inline start has no dialog visibility flag; it must still open the created Chat
    // without relaunching when the expanded editor is replaced by the full-screen form.
    @Test fun inlineDraftExpandsWithoutLossAndStartOpensTheCreatedChatOnce() {
        val core = FakeCore().apply { addHost("home", Endpoint.Ssh("home.test", 22u, "me")) }
        show(core)
        compose.onNodeWithTag("tasks-new-task").performClick()
        compose.onNodeWithTag("tasks-new-prompt").performTextInput("Fix the queue")
        screenshot("inline-new-task")
        compose.onNodeWithTag("tasks-new-expand").performClick()
        compose.onNodeWithTag("new-task-prompt").performScrollTo().assertTextContains("Fix the queue")
        screenshot("fullscreen-new-task", "new-task-dialog")
        compose.onNodeWithText(context.getString(R.string.cancel_button)).performClick()
        compose.onNodeWithTag("tasks-new-prompt").assertTextContains("Fix the queue")
        compose.onNodeWithTag("tasks-new-start").performClick()
        compose.runOnIdle { core.push(core.terminals.map { it.copy(agentStatus = AgentStatus.Idle) }) }
        compose.waitUntil { core.connection!!.typed.isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_input_label)).assertExists()
        compose.runOnIdle {
            assertEquals(1, core.connection!!.launched.size)
            assertEquals(listOf("t101" to "Fix the queue"), core.connection!!.typed)
        }
    }

    // Regression: dismissing the sheet during a submitted answer must not cancel it and
    // leave the retained question permanently Sending when its sheet is reopened.
    @Test fun dismissingAnInFlightAnswerKeepsItsModelOwnedDelivery() {
        val hold = CompletableDeferred<Unit>()
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            foregroundAnswerHold = hold
            terminals = listOf(TerminalInfo("t1", "Question task", "", listOf("codex"), "/repo", "live",
                AgentStatus.Blocked(BlockedReason.QUESTION, "Color?")))
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        val question = ChatEntry("q1", null, ChatEntryBody.Question("request_user_input", "Color?",
            listOf(ChatQuestionPart("Color?", null, false, listOf(ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(question), ChatStatus.LIVE, null)) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Blue")).performClick()
        compose.onNodeWithTag("tasks-needs-next").performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertExists()
        compose.runOnIdle { hold.complete(Unit) }
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, core.answers.size) }
    }

    @Test fun successfulRepliesAdvanceInUrgencyOrderAndCloseAfterTheLast() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            ensureKey()
            callsOf["h1"] = listOf(call("regular", "t1", CallUrgency.CHECK), call("urgent", "t2", CallUrgency.URGENT))
            replyResult = CallReply(CallReplyOutcome.DELIVERED, CallAnswer(0u, "Continue", CallAnswerState.DELIVERED, 2000uL, null), null)
        }
        show(core)
        screenshot("calls-home")
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertExists()
        screenshot("urgent-call", "tasks-needs-sheet")
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, "urgent", "Continue")).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, "regular", "Continue")).performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("urgent", "regular"), core.replies.map { it.second }) }
    }
}
