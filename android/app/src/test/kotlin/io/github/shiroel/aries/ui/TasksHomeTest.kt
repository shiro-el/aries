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

/** Needs confirmation and foreground feed ownership at the Android UI/core seam. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksHomeTest {
    init {
        System.getenv("ARIES_TASKS_FONT")?.toFloatOrNull()?.let(org.robolectric.RuntimeEnvironment::setFontScale)
    }
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var model: HostsModel

    private val callTime = System.currentTimeMillis().toULong() - 60_000uL

    private fun call(id: String, terminal: String, urgency: CallUrgency) = CallInfo(
        "h1", id, callTime, terminal, true, id, "Decide $id", urgency, listOf("Continue", "Wait"), null, true)

    private fun show(core: AriesCoreInterface) {
        val prefs = context.getSharedPreferences("tasks-home-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ThemeSettings(prefs).apply {
            chooseLayout(AppLayout.TASKS)
            chooseMode(if (System.getenv("ARIES_TASKS_THEME") == "dark") ThemeMode.DARK else ThemeMode.LIGHT)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope).apply { start() } }
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

    // D57: leaving Chat or receiving a newer turn must not present its retained Tool/question as current.
    @Test fun homePreviewsRequireACaughtUpTranscriptForTheCurrentStatus() {
        val terminal = TerminalInfo("t1", "Review build", "", listOf("codex"), "/repo", "live",
            AgentStatus.Working, turn = 1uL, statusMs = 100uL)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(terminal)
        }
        show(core)
        lateinit var chat: ChatModel
        compose.runOnIdle {
            val board = (model.screen as Screen.Agents).board
            chat = model.taskQuestion(board, AgentKey("h1", "t1"))!!
            model.startTaskQuestion(chat)
            core.pushChat(ChatSnapshot(listOf(ChatEntry("tool", null,
                ChatEntryBody.Tool("Bash", "Running old build", "{}", ChatToolStatus.RUNNING, null))), ChatStatus.LIVE, null))
        }
        compose.waitUntil { chat.snapshot?.status == ChatStatus.LIVE }
        compose.runOnIdle { core.push(listOf(terminal.copy(title = "Current transcript"))) }
        compose.onNodeWithText("Running old build").assertExists()
        compose.runOnIdle { core.push(listOf(terminal.copy(turn = 2uL))) }
        compose.onNodeWithText("Running old build").assertDoesNotExist()
        compose.runOnIdle {
            core.pushChat(ChatSnapshot(listOf(ChatEntry("tool2", null,
                ChatEntryBody.Tool("Bash", "Running new build", "{}", ChatToolStatus.RUNNING, null))), ChatStatus.LIVE, null))
        }
        compose.onNodeWithText("Running new build").assertExists()
        compose.runOnIdle { chat.stop() }
        compose.onNodeWithText("Running new build").assertDoesNotExist()
        compose.runOnIdle {
            core.push(listOf(terminal.copy(turn = 2uL, statusMs = 200uL,
                agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, null))))
        }
        compose.runOnIdle {
            model.startTaskQuestion(chat)
            core.pushChat(ChatSnapshot(listOf(ChatEntry("q", null, ChatEntryBody.Question("request_user_input", "Question",
                listOf(ChatQuestionPart("Use the new build?", null, false, emptyList())), ChatToolStatus.RUNNING, null))), ChatStatus.LIVE, null))
        }
        compose.onNodeWithText("Use the new build?").assertExists()
        compose.runOnIdle { core.push(listOf(chat.terminal.copy(statusMs = 300uL))) }
        compose.onNodeWithText("Use the new build?").assertDoesNotExist()
        compose.runOnIdle { chat.stop() }
    }

    // D58: a delayed connection/reconnection must offer recovery in the card and retain its draft.
    @Test fun newTaskWaitsForItsHostAndKeepsTheDraftThroughReconnectAndRetry() {
        var gate = CompletableDeferred<Unit>()
        val fake = FakeCore().apply { addHost("home", Endpoint.Ssh("home.test", 22u, "me")) }
        val core = object : AriesCoreInterface by fake {
            override suspend fun connect(hostId: String): HostConnection {
                gate.await()
                return fake.connect(hostId)
            }
        }
        show(core)
        compose.onNodeWithTag("tasks-new-task").performClick()
        val inCard = hasAnyAncestor(hasTestTag("tasks-new-card"))
        compose.onNode(hasText(context.getString(R.string.reconnecting)) and inCard).assertExists()
        compose.onNodeWithText(context.getString(R.string.agents_empty_hosts_button)).assertDoesNotExist()
        compose.runOnIdle { gate.complete(Unit) }
        compose.onNodeWithTag("tasks-new-prompt").performTextInput("Keep my draft")
        compose.runOnIdle {
            gate = CompletableDeferred()
            val link = (model.screen as Screen.Agents).board.links.getValue("h1")
            link.lost(link.connection!!)
        }
        compose.onNode(hasText(context.getString(R.string.reconnecting)) and inCard).assertExists()
        compose.runOnIdle { fake.failWith = CoreException.AuthRejected(); gate.complete(Unit) }
        compose.onNode(hasText(context.getString(R.string.connect_retry_button)) and inCard).performClick()
        compose.runOnIdle { fake.failWith = null }
        compose.onNode(hasText(context.getString(R.string.connect_retry_button)) and inCard).performClick()
        compose.onNodeWithTag("tasks-new-prompt").assertTextContains("Keep my draft")
        compose.onNodeWithTag("tasks-new-task").performClick()
        compose.onNodeWithTag("tasks-new-task").performClick()
        compose.onNodeWithTag("tasks-new-prompt").assertTextContains("Keep my draft")
    }

    // D25: Needs must ignore helpers, unknown callers and stale feeds, including on biometric Hosts.
    @Test fun needsOnlyCountsLiveConfirmedTopLevelCallsOnTheForegroundHost() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            ensureKey(); setKeyProtection(KeyProtection.BIOMETRIC)
            callsOf["h1"] = listOf(call("review", "t1", CallUrgency.CHECK),
                call("helper", "t2", CallUrgency.CHECK).copy(topLevel = false, pushesByDefault = false),
                call("unknown", "t3", CallUrgency.CHECK).copy(terminal = null, pushesByDefault = false),
                call("record", "t4", CallUrgency.RECORD).copy(pushesByDefault = false))
        }
        show(core)
        compose.onNodeWithTag("task-call:h1/review").assertExists()
        compose.onNodeWithTag("task-call:h1/helper").assertDoesNotExist()
        compose.onNodeWithTag("task-call:h1/unknown").assertDoesNotExist()
        compose.onNodeWithTag("task-call:h1/record").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 1))
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithTag("tasks-needs-next").performClick()
        compose.onNodeWithTag("task-call:h1/review").assertExists()
        compose.runOnIdle { core.callListeners.getValue("h1").onState(CallFeedState.Reconnecting("offline")) }
        compose.onNodeWithTag("task-call:h1/review").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 0))
    }

    // D22: a key receipt is not resolution; the same Blocked question must expose Unconfirmed,
    // then advance only when the transcript confirms completion.
    @Test fun questionThenPermissionWaitForTranscriptConfirmation() {
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
        compose.onNode(hasText("Question task") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithText(context.getString(R.string.chat_question_unconfirmed)).assertExists()
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(question.copy(body =
            (question.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE))), ChatStatus.LIVE, null)) }
        compose.onNode(hasText("Permission task") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        val permission = ChatEntry("permission:current", null, ChatEntryBody.Question("permission", "Run build?",
            listOf(ChatQuestionPart("Run build?", null, false, listOf(ChatQuestionOption("Allow", null)))), ChatToolStatus.RUNNING, null))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(permission), ChatStatus.LIVE, null)) }
        screenshot("permission", "tasks-needs-sheet")
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, context.getString(R.string.chat_permission_allow))).performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertExists()
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(permission.copy(body =
            (permission.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE))), ChatStatus.LIVE, null)) }
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("t1" to "q1", "t2" to "permission:current"), core.answers.map { it.first to it.second })
        }
    }

    // D22: completing one question must not hide a new question with the same Terminal status/turn.
    @Test fun aNewQuestionOnTheSameBlockedTaskRemainsInNeeds() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(TerminalInfo("t1", "Question task", "", listOf("codex"), "/repo", "live",
                AgentStatus.Blocked(BlockedReason.QUESTION, "Color?")))
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        val first = ChatEntry("q1", null, ChatEntryBody.Question("request_user_input", "Color?",
            listOf(ChatQuestionPart("Color?", null, false, listOf(ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(first), ChatStatus.LIVE, null)) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Blue")).performClick()
        val second = first.copy(id = "q2")
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(first.copy(body =
            (first.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE)), second), ChatStatus.LIVE, null)) }
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Blue")).performClick()
        compose.runOnIdle { assertEquals(listOf("q1", "q2"), core.answers.map { it.second }) }
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
        compose.onNode(hasContentDescription(context.getString(R.string.back_button)) and
            hasAnyAncestor(hasTestTag("new-task-dialog"))).performClick()
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
        compose.onNodeWithTag("tasks-needs-sheet").assertExists()
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(question.copy(body =
            (question.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE))), ChatStatus.LIVE, null)) }
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
    // Regression D33: a Host-side answer/status change must advance the captured queue,
    // including Error needs, instead of leaving the sheet on an obsolete request.
    @Test fun anotherClientResolvingTheCurrentNeedAdvancesToTheNextAgent() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(
                TerminalInfo("t1", "First request", "", listOf("codex"), "/repo", "live", AgentStatus.Error("Build failed"), statusMs = 200uL),
                TerminalInfo("t2", "Second request", "", listOf("claude"), "/repo", "live", AgentStatus.Blocked(BlockedReason.QUESTION, "Color?"), statusMs = 100uL))
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNode(hasText("First request") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        compose.runOnIdle { core.push(core.terminals.map { if (it.address == "t1") it.copy(agentStatus = AgentStatus.Working) else it }) }
        compose.onNode(hasText("Second request") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        compose.runOnIdle { core.push(core.terminals.map { it.copy(agentStatus = AgentStatus.Working) }) }
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<Triple<String, String, List<List<UInt>>>>(), core.answers) }
    }

}
