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
import org.junit.Assert.assertFalse
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
    private val generation = androidx.compose.runtime.mutableIntStateOf(0)
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
            androidx.compose.runtime.key(generation.intValue) { AriesApp(model, settings) }
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

    // D21: a newer permission in the same turn must invalidate the previous DONE snapshot.
    @Test fun secondPermissionRemainsInNeedsWhileItsTranscriptCatchesUp() {
        val terminal = TerminalInfo("t1", "Permission task", "", listOf("claude"), "/repo", "live",
            AgentStatus.Blocked(BlockedReason.PERMISSION, null), turn = 1uL, statusMs = 100uL)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(terminal)
        }
        show(core)
        lateinit var chat: ChatModel
        val first = ChatEntry("permission:first", null, ChatEntryBody.Question("permission", "Old permission",
            listOf(ChatQuestionPart("Old permission", null, false, emptyList())), ChatToolStatus.DONE, null))
        compose.runOnIdle {
            chat = model.taskQuestion((model.screen as Screen.Agents).board, AgentKey("h1", "t1"))!!
            model.startTaskQuestion(chat)
            core.pushChat(ChatSnapshot(listOf(first), ChatStatus.LIVE, null))
        }
        compose.onNodeWithTag("tasks-filter-needs").assertTextContains(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 0))
        compose.runOnIdle { core.push(listOf(terminal.copy(statusMs = 200uL))) }
        compose.onNodeWithTag("tasks-filter-needs").assertTextContains(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 1))
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithText("Old permission").assertDoesNotExist()
        val second = first.copy(id = "permission:second", body = (first.body as ChatEntryBody.Question).copy(
            title = "New permission", questions = listOf(ChatQuestionPart("New permission", null, false,
                listOf(ChatQuestionOption("Allow", null)))), status = ChatToolStatus.RUNNING))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(first, second), ChatStatus.LIVE, null)) }
        compose.onNode(hasText("New permission") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label,
            context.getString(R.string.chat_permission_allow))).performClick()
        compose.runOnIdle { assertEquals("permission:second", core.answers.single().second) }
    }

    // D21: completion received before the sheet ever saw RUNNING must still retire the need.
    @Test fun firstQuestionSnapshotAlreadyCompleteClosesTheObsoleteNeed() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(TerminalInfo("t1", "Question task", "", listOf("codex"), "/repo", "live",
                AgentStatus.Blocked(BlockedReason.QUESTION, null)))
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(ChatEntry("done", null,
            ChatEntryBody.Question("request_user_input", "Already answered", emptyList(), ChatToolStatus.DONE, null))), ChatStatus.LIVE, null)) }
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 0))
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

    // D25/D33: ignore helpers and unknown callers, but retain confirmed Calls during a feed outage.
    @Test fun needsRetainsConfirmedTopLevelCallsDuringAFeedOutage() {
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
        compose.onNodeWithTag("task-call:h1/review").assertExists()
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 1))
        compose.onNodeWithTag("task-call:h1/review").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, "review", "Continue")).assertDoesNotExist()
        compose.runOnIdle { core.callListeners.getValue("h1").onState(CallFeedState.Live) }
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(core.callsOf.getValue("h1")) }
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, "review", "Continue")).assertExists()
    }

    // D27: acknowledging an informational Call must remove it from Tasks across recreation.
    @Test fun informationalCallCanBeAcknowledgedFromTasks() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me")); ensureKey()
            callsOf["h1"] = listOf(call("review", "t1", CallUrgency.CHECK).copy(choices = emptyList()))
        }
        show(core)
        compose.onNodeWithTag("task-call:h1/review").performClick()
        compose.onNode(hasText(context.getString(R.string.ok_button)) and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).performClick()
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.onNodeWithTag("task-call:h1/review").assertDoesNotExist()
        compose.runOnIdle { generation.intValue++ }
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 0))
        compose.runOnIdle { assertEquals(emptyList<Triple<String, String, UInt>>(), core.replies) }
    }

    // D27: only fresh caller progress after the Call can clear an unanswered informational Call.
    @Test fun callerProgressAfterTheCallRemovesItFromNeeds() {
        val terminal = TerminalInfo("t1", "Review task", "", listOf("claude"), "/repo", "live",
            AgentStatus.Working, statusMs = callTime - 1uL)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me")); ensureKey()
            terminals = listOf(terminal)
            callsOf["h1"] = listOf(call("review", "t1", CallUrgency.CHECK).copy(choices = emptyList()))
        }
        show(core)
        compose.onNodeWithTag("task-call:h1/review").assertExists()
        compose.onNodeWithTag("task-call:h1/review").performClick()
        compose.runOnIdle { core.push(listOf(terminal.copy(agentStatus = AgentStatus.Done, statusMs = callTime + 1uL))) }
        compose.onNodeWithTag("task-call:h1/review").assertDoesNotExist()
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 0))
    }

    // D38: scoped Tasks rows, counts and banners must not leak Calls from another Host.
    @Test fun hostScopedTasksOnlyShowsThatHostsCalls() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me")); ensureKey()
            callsOf["h1"] = listOf(call("home review", "t1", CallUrgency.CHECK))
            callsOf["h2"] = listOf(call("work review", "t1", CallUrgency.CHECK).copy(hostId = "h2"))
        }
        show(core)
        compose.onNodeWithTag("task-call:h2/work review").assertExists()
        compose.runOnIdle { model.openHostWaiting(core.hosts().first()) }
        compose.onNodeWithTag("task-call:h1/home review").assertExists()
        compose.onNodeWithTag("task-call:h2/work review").assertDoesNotExist()
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 1))
        compose.runOnIdle { core.sendCall(call("new work call", "t1", CallUrgency.CHECK).copy(hostId = "h2")) }
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.onNodeWithTag("agents-show-all").performClick()
        compose.onNodeWithTag("tasks-filter-needs").assertTextEquals(context.getString(R.string.tasks_filter_count,
            context.getString(R.string.tasks_filter_needs), 3))
    }

    // D86: a list-only outage must not stop/reopen the sheet's independent Chat stream or lose its question.
    @Test fun listFreshnessChangesKeepTheOpenQuestionStreamAlive() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(TerminalInfo("t1", "Question task", "", listOf("codex"), "/repo", "live",
                AgentStatus.Blocked(BlockedReason.QUESTION, "Color?")))
            storedStates["h1"] = LastKnownState("h1", terminals, 1_000uL)
            watchFailures["h1"] = CoreException.Rpc("list_failed", "List failed")
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Pair<String, String>>(), core.chatsOpened)
            core.watchFailures.remove("h1")
            (model.screen as Screen.Agents).board.links.getValue("h1").retryNow()
        }
        compose.waitUntil { core.chatsOpened.isNotEmpty() }
        val question = ChatEntry("q1", null, ChatEntryBody.Question("request_user_input", "Color?",
            listOf(ChatQuestionPart("Color?", null, false, listOf(ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(question), ChatStatus.LIVE, null)) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Blue")).assertExists()
        compose.runOnIdle { core.failTerminalWatch("h1", CoreException.Rpc("list_failed", "List failed")) }
        compose.waitUntil { (model.screen as Screen.Agents).board.watchErrors.containsKey("h1") }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("The Chat stream survives a Terminal list failure", core.chatStopped) }
        compose.runOnIdle { (model.screen as Screen.Agents).board.links.getValue("h1").retryNow() }
        compose.waitUntil { (model.screen as Screen.Agents).board.agents.single().live }
        compose.runOnIdle {
            core.failWith = CoreException.Unreachable("offline")
            (model.screen as Screen.Agents).board.links.getValue("h1").networkChanged()
        }
        compose.waitUntil { (model.screen as Screen.Agents).board.links.getValue("h1").state == LinkState.Reconnecting }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("The Chat stream survives an SSH replacement", core.chatStopped)
            core.failWith = null
            (model.screen as Screen.Agents).board.links.getValue("h1").retryNow()
        }
        compose.waitUntil { (model.screen as Screen.Agents).board.agents.single().live }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Blue")).performClick()
        compose.runOnIdle {
            assertEquals(listOf("h1" to "t1"), core.chatsOpened)
            assertEquals(listOf(Triple("t1", "q1", listOf(listOf(0u)))), core.answers)
        }
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
        // D21/D34: q2 is already live when q1 completes; continue without closing/reopening.
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
        compose.onNodeWithContentDescription(context.getString(R.string.chat_message_input_label)).assertExists()
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
        // D101: rebuilding the UI must keep the captured order and already resolved cursor.
        compose.runOnIdle { generation.intValue++ }
        compose.onNode(hasText("Second request") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        compose.runOnIdle { core.push(core.terminals.map { it.copy(agentStatus = AgentStatus.Working) }) }
        compose.onNodeWithTag("tasks-needs-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<Triple<String, String, List<List<UInt>>>>(), core.answers) }
    }

    // D34: a newly blocked task already in the feed must follow the final original question.
    @Test fun resolvingTheLastOriginalItemContinuesToANewlyArrivedTask() {
        val firstTerminal = TerminalInfo("t1", "First task", "", listOf("codex"), "/repo", "live",
            AgentStatus.Blocked(BlockedReason.QUESTION, null), statusMs = 100uL)
        val secondTerminal = firstTerminal.copy(address = "t2", name = "Next task", agentStatus = AgentStatus.Working)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(firstTerminal, secondTerminal)
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        val question = ChatEntry("q1", null, ChatEntryBody.Question("request_user_input", "First question",
            listOf(ChatQuestionPart("First question", null, false, listOf(ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(question), ChatStatus.LIVE, null)) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Blue")).performClick()
        compose.runOnIdle { core.push(listOf(firstTerminal, secondTerminal.copy(
            agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, null), statusMs = 200uL))) }
        compose.waitForIdle()
        compose.runOnIdle { core.pushChat(ChatSnapshot(listOf(question.copy(body =
            (question.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE))), ChatStatus.LIVE, null)) }
        compose.onNode(hasText("Next task") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
    }

    // D101: reconstructing from a changed feed would reset skipped items or reorder this visit.
    @Test fun rotationKeepsCapturedNeedsOrderAndSkippedPrefixWhenTheFeedChanges() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = (1..3).map { TerminalInfo("t$it", "Request $it", "", listOf("claude"), "/repo", "live",
                AgentStatus.Error("Review failure"), statusMs = (400 - it * 100).toULong()) }
        }
        show(core)
        compose.onNodeWithTag("tasks-filter-needs").performClick()
        compose.onNodeWithTag("tasks-needs-next").performClick()
        compose.onNode(hasText("Request 2") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        compose.runOnIdle {
            core.sendCall(call("new urgent call", "t1", CallUrgency.URGENT))
            generation.intValue++
        }
        compose.onNode(hasText("Request 2") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
        compose.onNodeWithTag("tasks-needs-next").performClick()
        compose.onNode(hasText("Request 3") and hasAnyAncestor(hasTestTag("tasks-needs-sheet"))).assertExists()
    }

}
