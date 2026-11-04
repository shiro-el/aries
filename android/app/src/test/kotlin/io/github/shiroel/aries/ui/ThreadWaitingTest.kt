package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ThreadWaitingTest {
    @get:Rule val compose = createComposeRule()

    // D103: a question's status-only receipt must survive automatic Thread target replacement.
    @Test fun waitingQuestionSpeaksConfirmationBeforeItsTargetDisappears() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val waiting = TerminalInfo("t2", "Question target", "", listOf("codex"), "/repo", "live",
            AgentStatus.Blocked(BlockedReason.QUESTION, null), statusMs = 100uL)
        val fake = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me")); ensureKey(); savedView = AgentView.CHAT
            terminals = listOf(waiting.copy(address = "t1", name = "Current", agentStatus = AgentStatus.Working), waiting,
                waiting.copy(address = "t3", name = "Next target", agentStatus = AgentStatus.Error("Check build"), statusMs = 200uL))
        }
        val preview = FakeCore()
        val core = object : AriesCoreInterface by fake {
            override fun watchChat(hostId: String, terminal: String, cursor: String?): ChatWatch =
                if (terminal == "t2") preview.watchChat(hostId, terminal, cursor) else fake.watchChat(hostId, terminal, cursor)
        }
        val settings = ThemeSettings(context.getSharedPreferences("thread-question-receipt", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        compose.runOnIdle { preview.pushChat(ChatSnapshot(listOf(ChatEntry("q", null,
            ChatEntryBody.Question("request_user_input", "Continue?", listOf(ChatQuestionPart("Continue?", null, false,
                listOf(ChatQuestionOption("Continue", null)))), ChatToolStatus.RUNNING, null))), ChatStatus.LIVE, null)) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Continue")).performClick()
        val announcements = recordAccessibilityAnnouncements(compose)
        compose.runOnIdle { fake.push(fake.terminals.map { if (it.address == "t2") it.copy(agentStatus = AgentStatus.Working) else it }) }
        assertQueueHeading("Next target")
        compose.runOnIdle { assertEquals(1, announcements.count { it == context.getString(R.string.chat_question_sent) }) }
    }

    // D23: local results must not swap another Agent's Choice into the button just tapped.
    @Test fun waitingSheetKeepsLocalResultsUntilNextButAdvancesForAnExternalAnswer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = CallInfo("h1", "a", 1000uL, "t2", true, "First Call", "", CallUrgency.CHECK, listOf("Allow A"), null, true)
        val b = a.copy(id = "b", atMs = 2000uL, terminal = "t3", title = "Second Call", choices = listOf("Allow B"))
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me")); ensureKey(); savedView = AgentView.CHAT
            terminals = listOf("Current", "Agent A", "Agent B").mapIndexed { i, name ->
                TerminalInfo("t${i + 1}", name, "", listOf("claude"), "/repo", "live", AgentStatus.Working)
            }
            callsOf["h1"] = listOf(a, b)
        }
        val settings = ThemeSettings(context.getSharedPreferences("waiting-results", Context.MODE_PRIVATE)).apply { chooseLayout(AppLayout.THREAD) }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        val announcements = recordAccessibilityAnnouncements(compose)
        for (state in listOf(CallAnswerState.FAILED, CallAnswerState.UNCONFIRMED)) {
            val at = if (state == CallAnswerState.FAILED) 3000uL else 5000uL
            val answer = CallAnswer(0u, "Allow A", state, at, "Host result")
            val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
            core.replyHold = hold
            core.replyResult = CallReply(if (state == CallAnswerState.FAILED) CallReplyOutcome.FAILED else CallReplyOutcome.UNCONFIRMED, answer, "Host result")
            compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, a.title, "Allow A")).performClick()
            compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(a.copy(answer = answer.copy(state = CallAnswerState.CLAIMED, atMs = at - 1u)), b)) }
            compose.onNodeWithText("First Call").assertExists()
            compose.onNodeWithText(context.getString(R.string.call_answer_sending)).assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, b.title, "Allow B")).assertDoesNotExist()
            hold.complete(Unit)
            compose.waitForIdle()
            compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(a.copy(answer = answer), b)) }
            compose.onNodeWithText(context.getString(if (state == CallAnswerState.FAILED) R.string.call_answer_failed else R.string.call_answer_unconfirmed, "Allow A")).assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, b.title, "Allow B")).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.thread_next_waiting_button)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, b.title, "Allow B")).assertExists()
        compose.runOnIdle {
            core.callListeners.getValue("h1").onCalls(listOf(a.copy(answer = CallAnswer(0u, "Allow A", CallAnswerState.DELIVERED, 6000uL, null)),
                b.copy(answer = CallAnswer(0u, "Allow B", CallAnswerState.DELIVERED, 6000uL, null))))
        }
        compose.onNodeWithText("Second Call").assertDoesNotExist()
        // D103: the selected result must survive automatic target removal, without replay on navigation.
        compose.runOnIdle { assertEquals(1, announcements.count {
            it == context.getString(R.string.call_answer_delivered, "Allow B")
        }) }
    }

    // D22: checking uncertain delivery must open the calling Terminal, even with Chat as the default view.
    @Test fun uncertainDeliveryOpensTheCallingTerminalWithoutChangingTheSavedView() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me")); ensureKey(); savedView = AgentView.CHAT
            terminals = listOf("Current", "Review").mapIndexed { i, name ->
                TerminalInfo("t${i + 1}", name, "", listOf("claude"), "/repo", "live", AgentStatus.Working)
            }
            callsOf["h1"] = listOf(CallInfo("h1", "uncertain", 1000uL, "t2", true, "Check delivery", "", CallUrgency.CHECK,
                listOf("Allow"), CallAnswer(0u, "Allow", CallAnswerState.UNCONFIRMED, 2000uL, null), true))
        }
        val settings = ThemeSettings(context.getSharedPreferences("waiting-terminal-recovery", Context.MODE_PRIVATE)).apply { chooseLayout(AppLayout.THREAD) }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        compose.onNodeWithText(context.getString(R.string.terminal_open_button)).performClick()
        compose.waitUntil(5_000) { model.screen is Screen.Terminal }
        compose.runOnIdle {
            assertEquals("t2", (model.screen as Screen.Terminal).viewer.terminal.address)
            assertEquals(AgentView.CHAT, core.agentView())
        }
    }

    // D04: a failed list watch must remove cached Agents from live waiting and drawer priority sections.
    @Test fun failedHostListKeepsLastKnownAgentsOutOfLiveWaiting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            terminalsOf["h1"] = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
            terminalsOf["h2"] = listOf(
                TerminalInfo("t2", "Old question", "", listOf("claude"), "/repo", "live", AgentStatus.Blocked(BlockedReason.QUESTION, null)),
                TerminalInfo("t3", "Old work", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
            storedStates["h2"] = LastKnownState("h2", terminalsOf.getValue("h2"), 1_000uL)
        }
        lateinit var model: HostsModel
        val settings = ThemeSettings(context.getSharedPreferences("thread-freshness", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope).apply { start(layout = AppLayout.THREAD) } }
            AriesApp(model, settings)
        }
        compose.waitUntil { model.drawerAgents.size == 3 && model.quickOpenSources.all { it.live } }
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.onNodeWithTag("thread-waiting-pill").assertExists()
        compose.runOnIdle { core.failTerminalWatch("h2", CoreException.Rpc("list_failed", "List failed")) }
        compose.waitUntil { model.quickOpenSources.any { it.host.id == "h2" && !it.live } }
        compose.onNodeWithTag("thread-waiting-pill").assertDoesNotExist()
        compose.runOnIdle {
            val cached = model.drawerAgents.filter { it.host.id == "h2" }
            assertEquals(2, cached.size)
            assertTrue(cached.all { !it.live && it.lastUpdatedMs == 1_000L })
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.thread_section_needs)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.thread_section_working)).onParent()
            .assert(hasAnyDescendant(hasText("1")))
        compose.onNodeWithText(context.getString(R.string.thread_section_earlier)).assertExists()
        compose.onNodeWithTag("thread-agent:h2:t2").assertExists()
        compose.onNodeWithTag("thread-agent:h2:t3").assertExists()
    }

    // Regression: opening a preview must retire its old stream and keep the newly adopted Chat stream alive.
    @Test fun openingTheWaitingConversationKeepsItsChatStreamAlive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = FakeCore().apply {
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(
                TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working),
                TerminalInfo("t2", "Waiting", "", listOf("claude"), "/repo", "live", AgentStatus.Blocked(BlockedReason.QUESTION, null)),
            )
        }
        class Stream(val terminal: String) : ChatWatch(NoHandle) {
            var stopped = false
            override suspend fun next(): ChatSnapshot = awaitCancellation()
            override suspend fun nextUpdate(): ChatUpdate = awaitCancellation()
            override fun stop() { stopped = true }
            override fun close() {}
            override fun pause() {}
            override fun resume() {}
            override fun reconnect() {}
            override fun retryNow() {}
            override fun image(id: String): ByteArray? = null
        }
        val streams = mutableListOf<Stream>()
        val core = object : AriesCoreInterface by fake {
            override fun watchChat(hostId: String, terminal: String, cursor: String?): ChatWatch =
                Stream(terminal).also { streams += it }
        }
        lateinit var model: HostsModel
        val settings = ThemeSettings(context.getSharedPreferences("thread-waiting-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { streams.any { it.terminal == "t2" } }
        compose.onNodeWithText(context.getString(R.string.thread_open_conversation)).performClick()
        compose.waitUntil(5_000) { (model.screen as? Screen.Chat)?.chat?.terminal?.address == "t2" }
        compose.waitForIdle()
        compose.runOnIdle {
            val targetStreams = streams.filter { it.terminal == "t2" }
            assertFalse("The visible Chat owns the latest stream", targetStreams.last().stopped)
            assertTrue("The preview releases every superseded stream", targetStreams.dropLast(1).all { it.stopped })
        }
    }
    // Regression: a Call without Choices must not permanently hide the Agent's checked question-answer route.
    @Test fun closingACallWithoutChoicesRestoresTheQuestionAnswerRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            ensureKey()
            terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live",
                AgentStatus.Blocked(BlockedReason.QUESTION, null)))
        }
        val settings = ThemeSettings(context.getSharedPreferences("thread-call-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.runOnIdle {
            core.pushChat(ChatSnapshot(listOf(ChatEntry("q1", null, ChatEntryBody.Question("AskUserQuestion", "Scope?",
                listOf(ChatQuestionPart("Which scope?", null, false, listOf(ChatQuestionOption("Chat", null)))),
                ChatToolStatus.RUNNING, null))), ChatStatus.LIVE, null))
            core.sendCall(CallInfo("h1", "notice", 1000uL, "t1", true, "Review the result", "No Choice is required.",
                CallUrgency.URGENT, emptyList(), null, true))
        }
        compose.onNode(hasText(context.getString(R.string.thread_call_dismiss)) and
            hasAnyAncestor(hasTestTag("thread-call-panel"))).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_question_option_label, "Chat")).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("t1", "q1", listOf(listOf(0u)))), core.answers)
            assertTrue(core.replies.isEmpty())
        }
    }

    // Regression D08: closing an informational Call survives navigation and recreated UI,
    // while its transcript row remains an explicit route back to the panel.
    @Test fun closedCallStaysOutOfWaitingUntilItsTranscriptRowIsOpened() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            ensureKey()
            terminals = listOf(
                TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working),
                TerminalInfo("t2", "Other", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
            callsOf["h1"] = listOf(CallInfo("h1", "notice", 1000uL, "t1", true,
                "Review the result", "No Choice is required.", CallUrgency.CHECK, emptyList(), null, true))
        }
        val settings = ThemeSettings(context.getSharedPreferences("thread-call-retained", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        val generation = mutableIntStateOf(0)
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            key(generation.intValue) { AriesApp(model, settings) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.onNode(hasText(context.getString(R.string.thread_call_dismiss)) and
            hasAnyAncestor(hasTestTag("thread-call-panel"))).performClick()
        // D48: acknowledging an informational Call also clears the inbox attention count.
        compose.runOnIdle { model.openCallInbox() }
        compose.onNodeWithText(context.getString(R.string.call_inbox_label, 0)).assertExists()
        compose.runOnIdle { model.closeCallInbox() }
        compose.runOnIdle { model.openPushedAgent("h1", "t2", openChat = true) }
        compose.onNodeWithTag("thread-waiting-pill").assertDoesNotExist()
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.onNodeWithTag("thread-call-panel").assertDoesNotExist()
        compose.runOnIdle { generation.intValue++ }
        compose.onNodeWithTag("thread-call-panel").assertDoesNotExist()
        compose.onNodeWithTag("thread-call:notice").performScrollTo().performClick()
        compose.onNodeWithTag("thread-call-panel").assertExists()
        compose.onNode(hasText(context.getString(R.string.thread_call_dismiss)) and
            hasAnyAncestor(hasTestTag("thread-call-panel"))).performClick()
        compose.runOnIdle { model.openPushedAgent("h1", "t2", openChat = true) }
        compose.onNodeWithTag("thread-waiting-pill").assertDoesNotExist()
    }

    // Regression D33: Error participates in Thread waiting, and external resolution moves
    // the open sheet to the next live need rather than retaining its stale target.
    @Test fun waitingSheetIncludesErrorsAndAdvancesAfterExternalResolution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            savedView = AgentView.CHAT
            terminals = listOf(
                TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working),
                TerminalInfo("t2", "Build failure", "", listOf("claude"), "/repo", "live", AgentStatus.Error("Build failed"), statusMs = 100uL),
                TerminalInfo("t3", "Next request", "", listOf("codex"), "/repo", "live", AgentStatus.Blocked(BlockedReason.QUESTION, "Color?"), statusMs = 200uL),
                TerminalInfo("t4", "Chosen request", "", listOf("claude"), "/repo", "live", AgentStatus.Error("Check this one"), statusMs = 300uL))
        }
        val settings = ThemeSettings(context.getSharedPreferences("thread-error-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        val generation = mutableIntStateOf(0)
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            key(generation.intValue) { AriesApp(model, settings) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        assertQueueHeading("Build failure")
        compose.runOnIdle { core.push(core.terminals.map { if (it.address == "t2") it.copy(agentStatus = AgentStatus.Working) else it }) }
        assertQueueHeading("Next request")
        compose.onNodeWithContentDescription(context.getString(R.string.thread_next_waiting_button)).performClick()
        assertQueueHeading("Chosen request")
        // D101: the same selected need must remain open after Activity composition recreation.
        compose.runOnIdle { generation.intValue++ }
        assertQueueHeading("Chosen request")
        compose.onNodeWithText(context.getString(R.string.thread_open_conversation)).performClick()
        compose.runOnIdle { assertEquals("t4", (model.screen as Screen.Chat).chat.terminal.address) }
    }

    // Regression D68: the first transcript fetched by a waiting preview must update
    // the sheet/pill title and stay consistent when that conversation is opened.
    @Test fun firstWaitingPreviewUpdatesTheConversationTitleAcrossThreadSurfaces() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            savedView = AgentView.CHAT
            terminals = listOf(
                TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working),
                TerminalInfo("t2", "Terminal title", "", listOf("claude"), "/repo", "live", AgentStatus.Blocked(BlockedReason.QUESTION, "Scope?")))
        }
        val previewCore = FakeCore()
        val delayedCore = object : AriesCoreInterface by core {
            override fun watchChat(hostId: String, terminal: String, cursor: String?): ChatWatch =
                if (terminal == "t2") previewCore.watchChat(hostId, terminal, cursor) else core.watchChat(hostId, terminal, cursor)
        }
        val settings = ThemeSettings(context.getSharedPreferences("thread-preview-title", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(delayedCore, scope) }
            AriesApp(model, settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Current").performClick()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        compose.runOnIdle { previewCore.pushChat(ChatSnapshot(listOf(ChatEntry("first-message", null,
            ChatEntryBody.User("Investigate the reconnect failure"))), ChatStatus.LIVE, null)) }
        assertQueueHeading("Investigate the reconnect failure")
        compose.onNodeWithText(context.getString(R.string.thread_open_conversation)).performClick()
        compose.onNode(hasText("Investigate the reconnect failure") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button,
        )).assertExists()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("thread-agent:h1:t2").assertTextContains("Investigate the reconnect failure")
    }

    // D129: Close Call hides an unanswered Choice panel without acknowledging its attention.
    @Test fun closingAnUnansweredChoiceKeepsItInTheDrawerAndWaitingSheet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me")); ensureKey(); savedView = AgentView.CHAT
            terminals = listOf("Current", "Choice caller").mapIndexed { index, name ->
                TerminalInfo("t${index + 1}", name, "", listOf("claude"), "/repo", "live", AgentStatus.Working)
            }
            callsOf["h1"] = listOf(CallInfo("h1", "choice", 1000uL, "t2", true,
                "Review ready", "Choose when ready", CallUrgency.CHECK, listOf("Continue"), null, true))
        }
        val settings = ThemeSettings(context.getSharedPreferences("choice-close-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }
        compose.runOnIdle { model.openPushedAgent("h1", "t2", openChat = true) }
        compose.onNodeWithText(context.getString(R.string.thread_call_dismiss)).performClick()
        compose.onNodeWithTag("thread-call-panel").assertDoesNotExist()
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.thread_section_needs)).assertExists()
        compose.onNodeWithTag("thread-agent:h1:t2").assertExists()
        compose.onNodeWithTag("thread-agent:h1:t1").performClick()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        compose.onNodeWithText(context.getString(R.string.thread_call_dismiss)).performClick()
        compose.onNodeWithTag("thread-call-panel").assertDoesNotExist()
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, "Review ready", "Continue"))
            .performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("h1", "choice", 0u)), core.replies) }
    }

    // D47: shrinking the viewport must not dispose an open waiting sheet or lose its target.
    @Test fun openWaitingSheetRemainsAnswerableWhenTheViewportBecomesCompact() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me")); ensureKey(); savedView = AgentView.CHAT
            terminals = listOf("Current", "Waiting").mapIndexed { index, name ->
                TerminalInfo("t${index + 1}", name, "", listOf("claude"), "/repo", "live", AgentStatus.Working)
            }
            callsOf["h1"] = listOf(CallInfo("h1", "choice", 1000uL, "t2", true,
                "Keep going?", "", CallUrgency.CHECK, listOf("Continue"), null, true))
        }
        val settings = ThemeSettings(context.getSharedPreferences("compact-waiting-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        var height by mutableStateOf(1000.dp)
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            Box(Modifier.height(height)) { AriesApp(model, settings) }
        }
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.onNodeWithTag("thread-waiting-pill").performClick()
        compose.runOnIdle { height = 360.dp }
        compose.onNodeWithContentDescription(context.getString(R.string.call_answer_choice_label, "Keep going?", "Continue"))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("h1", "choice", 0u)), core.replies) }
    }

    private fun assertQueueHeading(name: String) {
        compose.waitForIdle()
        fun descendants(view: android.view.View): Sequence<android.view.View> = sequence {
            yield(view)
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        // Native headings support accessibility focus, which is separate from Compose keyboard focus.
        compose.runOnIdle {
            val headings = android.view.inspector.WindowInspector.getGlobalWindowViews().asSequence()
                .flatMap(::descendants).filterIsInstance<android.widget.TextView>()
            org.junit.Assert.assertTrue(headings.any { it.isShown && it.isAccessibilityHeading && it.text.contains(name) })
        }
    }

}
