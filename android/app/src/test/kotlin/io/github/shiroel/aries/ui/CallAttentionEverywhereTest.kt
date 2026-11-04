package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class CallAttentionEverywhereTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: HostsModel
    private val core = FakeCore().apply {
        ensureKey()
        addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
    }
    private val call = CallInfo("h1", "check", 100uL, "t1", true,
        "Review the result", "Please check", CallUrgency.CHECK, emptyList(), null, true)

    private fun show(layout: AppLayout) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ThemeSettings(context.getSharedPreferences("attention-layout", Context.MODE_PRIVATE)).apply {
            chooseLayout(layout)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            LaunchedEffect(model) { model.start(layout = layout) }
            AriesApp(model, settings)
        }
        compose.waitForIdle()
    }

    // D01: Thread's home and management screens must show a newly arriving Call and open its inbox.
    @Test fun threadOutsideChatShowsNewCalls() {
        show(AppLayout.THREAD)
        compose.onNodeWithTag("thread-empty-home").assertIsDisplayed()
        compose.runOnIdle { core.sendCall(call) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.runOnIdle { model.closeCallInbox(); model.openSettings() }
        compose.runOnIdle { core.sendCall(call.copy(id = "settings")) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
        compose.runOnIdle { model.back() }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }

    // D36: landscape/IME must not leave a no-Choice CHECK Call with only a vibration.
    @Test @Config(qualifiers = "h400dp")
    fun compactCheckWithoutChoicesHasAttentionAndInboxCount() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.CONSOLE)
        compose.runOnIdle { core.sendCall(call) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertTextEquals("1")
        compose.onNodeWithTag("call_banner").performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.runOnIdle { model.closeCallInbox(); model.openPushedAgent("h1", "t1", openChat = false) }
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertTextEquals("1")
    }

    // D31: another Host's available snapshot supplies the caller name; missing names must not leak raw ids.
    @Test fun callerUsesOtherHostSnapshotOrGenericAgent() {
        core.addHost("Work", Endpoint.Ssh("work.test", 22u, "me"))
        core.storedStates["h2"] = LastKnownState("h2", listOf(TerminalInfo("t1", "Builder", "", emptyList(), null, "live")), 1uL)
        show(AppLayout.CONSOLE)
        compose.runOnIdle { core.sendCall(call.copy(hostId = "h2")) }
        compose.onNodeWithText("Builder on Work", substring = true).assertIsDisplayed()
        compose.runOnIdle { core.sendCall(call.copy(terminal = "raw-terminal-unknown")) }
        compose.onNodeWithText("Agent on Development", substring = true).assertIsDisplayed()
        compose.onNodeWithText("raw-terminal-unknown", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("call_banner").performClick()
        compose.onAllNodes(hasContentDescription("raw-terminal-unknown", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    // D30/D01: suppress duplication only while Chat's panel/pill is rendered; preview/Terminal need attention again.
    @Test fun threadSuppressesOnlyRenderedCallPresentations() {
        core.terminals = listOf(
            TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working),
            TerminalInfo("t2", "Other", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.THREAD)
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.runOnIdle { core.sendCall(call) }
        compose.onNodeWithTag("thread-call-panel").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { core.sendCall(call.copy(id = "other", terminal = "t2")) }
        compose.onNodeWithTag("thread-waiting-pill").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        val chat = (model.screen as Screen.Chat).chat
        compose.runOnIdle { chat.preview.selected = 5173u }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
        compose.runOnIdle { chat.preview.selected = null }
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { model.openTerminal(chat.terminal) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }

    // D30: a new urgent Call already rendered in the home strip must not also get a banner.
    @Test fun homeStripOwnsVisibleUrgentCalls() {
        show(AppLayout.CONSOLE)
        compose.runOnIdle { core.sendCall(call.copy(urgency = CallUrgency.EMERGENCY)) }
        compose.onNodeWithTag("console-urgent:h1/check").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { model.openSettings() }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }

    // D34: an arrival can precede its complete feed snapshot; tapping must still open a usable destination.
    @Test fun tasksArrivalBeforeQueueSnapshotOpensInbox() {
        show(AppLayout.TASKS)
        compose.runOnIdle { core.callListeners.getValue("h1").onNewCall(call) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call)) }
        compose.onNodeWithTag("call-${call.id}").assertIsDisplayed()
    }

    // D30: Tasks' visible Call rows/traces must own attention too; changing to Changes restores the banner.
    @Test fun tasksSuppressesOnlyItsVisibleCallRows() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.TASKS)
        compose.runOnIdle { core.sendCall(call.copy(choices = listOf("Continue"))) }
        compose.onNodeWithTag("task-call:h1/check").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.onNodeWithTag("tasks-call:h1/check").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.onNodeWithTag("tasks-tab-changes").performClick()
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }
}
