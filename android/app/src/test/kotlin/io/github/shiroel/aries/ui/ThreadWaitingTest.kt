package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

}
