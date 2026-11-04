package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI/core seam: Thread must keep shared input eligibility as watch/status events arrive. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ThreadChatStatusComposerTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private lateinit var model: HostsModel
    private val agent = TerminalInfo("t1", "Review", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private val question = ChatEntry("question", null, ChatEntryBody.Question("AskUserQuestion", "Which approach?", listOf(
        ChatQuestionPart("Which approach?", null, false, listOf(ChatQuestionOption("Safe", null))),
    ), ChatToolStatus.RUNNING, null))

    private fun open(core: FakeCore): FakeConnection {
        core.terminals = listOf(agent)
        core.addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
        core.ensureKey()
        val appearance = ThemeSettings(context.getSharedPreferences("thread-status-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, appearance)
        }
        compose.runOnIdle { model.openPushedAgent("h1", agent.address, openChat = true) }
        compose.waitForIdle()
        return core.connection!!
    }

    // D69: history opened from Thread tools must survive sheet dismissal and append without leaving that sheet over the composer.
    @Test fun choosingHistoryClosesToolsAndKeepsTheHistoryPickerUsable() {
        val core = FakeCore()
        open(core)
        compose.runOnIdle { core.rememberInput("h1", "t1", "Previous instruction") }
        compose.onNodeWithContentDescription(str(R.string.chat_message_input_label)).performTextInput("Keep this draft")
        compose.onNodeWithContentDescription(str(R.string.chat_input_tools_button)).performClick()
        compose.onNode(hasText(str(R.string.input_history_title)) or hasContentDescription(str(R.string.input_history_title))).performClick()
        compose.onNodeWithText("Previous instruction").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_message_input_label)).assertTextContains("Keep this draft\n\nPrevious instruction")
        compose.onNodeWithText(str(R.string.chat_input_tools_button)).assertDoesNotExist()
    }

    // D07: stale/pending questions must not remove normal send or Working's Stop while only Live owns the panel.
    @Test fun onlyALiveQuestionReplacesTheComposerAsStatusAndWatchCatchUp() {
        val core = FakeCore()
        val connection = open(core)
        core.pushChat(ChatSnapshot(listOf(question), ChatStatus.LIVE, null))
        compose.onNodeWithContentDescription(str(R.string.chat_message_input_label)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsEnabled()
        compose.onNodeWithTag("thread-answer-panel").assertDoesNotExist()

        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.onNodeWithContentDescription(str(R.string.chat_message_input_label)).performTextInput("Next instruction")
        compose.onNodeWithContentDescription(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to "Next instruction"), connection.typed)

        core.push(listOf(agent))
        core.pushChat(ChatSnapshot(listOf(question), ChatStatus.RECONNECTING, null))
        compose.onNodeWithContentDescription(str(R.string.chat_message_input_label)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsEnabled()

        core.pushChat(ChatSnapshot(listOf(question), ChatStatus.LIVE, null))
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, null))))
        compose.onNodeWithTag("thread-answer-panel").assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.chat_message_input_label)).assertDoesNotExist()
    }

    // D06: a failed/delayed ChatWatch must not gate an interrupt on the independent, live Host connection.
    @Test fun workingCanBeInterruptedDuringAWatchFailureAndBeforeTheFirstSnapshot() {
        val core = FakeCore().apply { failChatWith = CoreException.BeaconNotInstalled() }
        val connection = open(core)
        compose.onAllNodesWithText(str(R.string.chat_status_no_beacon)).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.agents_host_offline, "Development")).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsEnabled().performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1"), connection.interrupted)

        core.failChatWith = null
        compose.runOnIdle { (model.screen as Screen.Chat).chat.retryConnection() }
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsEnabled()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1", "t1"), connection.interrupted)
    }

    // D05: watch transitions must retain their distinct recovery reason, once, instead of Thread's own aliases.
    @Test fun transcriptFailuresKeepTheirSharedExplanationAndOnlyADownHostIsOffline() {
        val core = FakeCore()
        val connection = open(core)
        compose.onAllNodesWithText(str(R.string.chat_loading)).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.chat_status_connecting)).assertDoesNotExist()
        for ((status, label) in listOf(
            ChatStatus.INCOMPATIBLE to R.string.chat_status_incompatible,
            ChatStatus.NO_CORE to R.string.chat_status_no_core,
            ChatStatus.NO_BEACON to R.string.chat_status_no_beacon,
            ChatStatus.CONNECTING to R.string.chat_status_connecting,
            ChatStatus.RECONNECTING to R.string.chat_status_reconnecting,
            ChatStatus.NO_TERMINAL to R.string.chat_status_no_terminal,
        )) {
            core.pushChat(ChatSnapshot(listOf(ChatEntry("user", null, ChatEntryBody.User("Review the changes"))), status, null))
            compose.onAllNodesWithText(str(label)).assertCountEquals(1)
            compose.onNodeWithText(str(R.string.agents_host_offline, "Development")).assertDoesNotExist()
        }
        compose.runOnIdle {
            core.failWith = CoreException.AuthRejected()
            (model.screen as Screen.Chat).chat.link!!.lost(connection)
        }
        compose.onAllNodesWithText(str(R.string.agents_host_offline, "Development")).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.connect_settings_button)).assertExists()
    }

    // D32: Terminal fallback must follow the visible Call controls, including dismissal of Thread's panel.
    @Test fun anAnswerableCallHidesTheTerminalFallbackUntilItsPanelIsDismissed() {
        val core = FakeCore().apply {
            callsOf["h1"] = listOf(CallInfo("h1", "review", 1000uL, "t1", true,
                "Ready to merge?", "Tests pass", CallUrgency.CHECK, listOf("Merge", "Wait"), null, true))
        }
        open(core)
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Blocked(BlockedReason.PERMISSION, null))))
        compose.onNodeWithContentDescription(str(R.string.call_answer_choice_label, "Ready to merge?", "Wait")).assertIsEnabled()
        compose.onNodeWithText(str(R.string.chat_blocked_permission)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.thread_call_dismiss)).performClick()
        compose.onNodeWithText(str(R.string.chat_blocked_permission)).assertIsDisplayed()
    }
}
