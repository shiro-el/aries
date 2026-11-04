package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
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
