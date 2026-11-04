package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI/core seam: cancelling rewind must never type keys into the Agent's Terminal. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ChatRewindTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var model: HostsModel
    private val prompt = "Review the first change."
    private val agent = TerminalInfo("t1", "Review", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)

    private fun open(core: FakeCore, layout: AppLayout, program: String = "claude"): FakeConnection {
        core.terminals = listOf(agent.copy(argv = listOf(program)))
        core.savedView = AgentView.CHAT
        core.addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
        core.ensureKey()
        val appearance = ThemeSettings(context.getSharedPreferences("rewind-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(layout)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, appearance)
        }
        compose.runOnIdle { model.openPushedAgent("h1", agent.address, openChat = true) }
        compose.waitForIdle()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User(prompt))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(prompt) and hasAnyAncestor(hasTestTag("chat-transcript")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return core.connection!!
    }

    private fun cancelRewind(layout: AppLayout) {
        val connection = open(FakeCore(), layout)
        compose.onNode(hasText(prompt) and hasAnyAncestor(hasTestTag("chat-transcript")), useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.chat_rewind_here)).performClick()
        compose.onNodeWithText(context.getString(R.string.chat_rewind_open_button)).assertExists()
        assertTrue(connection.views.isEmpty())
        compose.onNodeWithText(context.getString(R.string.cancel_button)).performClick()
        compose.waitForIdle()
        assertTrue(connection.views.isEmpty())
        assertTrue(model.screen is Screen.Chat)
        assertEquals(emptyList<Pair<String, String>>(), connection.typed)
    }

    @Test fun cancellingInConsoleSendsNoTerminalKeys() = cancelRewind(AppLayout.CONSOLE)
    @Test fun cancellingInThreadSendsNoTerminalKeys() = cancelRewind(AppLayout.THREAD)
    @Test fun cancellingInTasksSendsNoTerminalKeys() = cancelRewind(AppLayout.TASKS)

    // Catches accidental acceptance of the CLI's default row, plus lost Terminal navigation in a layout.
    private fun confirmRewind(layout: AppLayout, program: String) {
        val connection = open(FakeCore(), layout, program)
        compose.onNode(hasText(prompt) and hasAnyAncestor(hasTestTag("chat-transcript")), useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.chat_rewind_here)).performClick()
        compose.onNodeWithText(context.getString(R.string.chat_rewind_open_button)).performClick()
        compose.waitUntil(5_000) { connection.views.any { it.sent.size >= 2 } }
        val view = connection.views.last()
        assertEquals("t1", view.address)
        assertEquals(false, view.options.observe)
        assertEquals(listOf("key:Escape", "key:Escape"), view.sent)
        compose.onNodeWithText(context.getString(R.string.key_up)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.key_down)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.chat_rewind_enter_key)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { view.sent.size == 5 }
        assertEquals(listOf("key:Escape", "key:Escape", "key:Up", "key:Down", "key:Enter"), view.sent)
        compose.runOnIdle { model.closeTerminal() }
        compose.runOnIdle { assertTrue(model.screen is Screen.Chat) }
    }

    @Test fun claudePickerInConsoleWaitsForTheUserToPressEnter() = confirmRewind(AppLayout.CONSOLE, "claude")
    @Test fun codexPickerInThreadWaitsForTheUserToPressEnter() = confirmRewind(AppLayout.THREAD, "codex")
    @Test fun claudePickerInTasksWaitsForTheUserToPressEnter() = confirmRewind(AppLayout.TASKS, "claude")

    // Catches a stale confirmation opening the picker after the Agent starts work or the prompt changes.
    @Test fun statusAndTranscriptChangesInvalidateConfirmation() {
        val core = FakeCore()
        val connection = open(core, AppLayout.CONSOLE)
        compose.onNode(hasText(prompt) and hasAnyAncestor(hasTestTag("chat-transcript")), useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.chat_rewind_here)).performClick()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Working)))
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.chat_rewind_open_button)).assertIsNotEnabled()
        assertTrue(connection.views.isEmpty())
        compose.onNodeWithText(context.getString(R.string.cancel_button)).performClick()
        compose.onNode(hasText(prompt) and hasAnyAncestor(hasTestTag("chat-transcript")), useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.chat_rewind_here)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.chat_copy_all)).performClick()
        core.push(listOf(agent))
        compose.waitForIdle()
        compose.onNode(hasText(prompt) and hasAnyAncestor(hasTestTag("chat-transcript")), useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.chat_rewind_here)).performClick()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User("Changed branch"))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.chat_rewind_open_button)).assertIsNotEnabled()
        assertTrue(connection.views.isEmpty())
    }

    // Catches exposing raw rewind keys for an unsupported Agent, queued prompt, or an Agent response.
    @Test fun onlyReceivedUserPromptsOnSupportedAgentsOfferRewind() {
        val core = FakeCore()
        val connection = open(core, AppLayout.CONSOLE, "opencode")
        fun checkNoRewind(text: String) {
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("chat-transcript")), useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNodeWithText(context.getString(R.string.chat_rewind_here)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.chat_copy_all)).performClick()
        }
        checkNoRewind(prompt)
        core.push(listOf(agent.copy(argv = listOf("pi"))))
        compose.waitForIdle()
        checkNoRewind(prompt)
        core.push(listOf(agent))
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User(prompt, queued = true))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        checkNoRewind(prompt)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a1", null, ChatEntryBody.Agent("Agent response"))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Agent response").fetchSemanticsNodes().isNotEmpty() }
        checkNoRewind("Agent response")
        assertTrue(connection.views.isEmpty())
    }
}
