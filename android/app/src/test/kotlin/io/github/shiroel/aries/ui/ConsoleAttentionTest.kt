package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import org.junit.Assert.assertFalse
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ConsoleAttentionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // D118: Back must dismiss the visible drawer/inbox before the search beneath it.
    @Test fun backClosesOverlaysBeforeChatSearch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            terminals = listOf(TerminalInfo("t1", "Review agent", "", listOf("claude"), "/repo", "live", AgentStatus.Idle))
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Review agent").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_search_button)).performClick()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.console_open_drawer)).performClick()
        compose.onNodeWithTag("console-drawer").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_search_hint)).assertIsDisplayed()
        compose.runOnIdle { model.openCallInbox() }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertFalse(model.callInboxOpen) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_search_hint)).assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_search_hint)).assertDoesNotExist()
    }

    // Regression: startup Calls on another Host, including those without Choices, must remain discoverable
    // after dismissing the arrival banner, and opening one must connect to its own Host/address.
    @Test fun urgentCallsFromOtherHostsRemainVisibleAndOpenTheirOwnChat() {
        val agent = TerminalInfo("t1", "Review agent", "", listOf("codex"), "/repo", "live", AgentStatus.Idle)
        val core = FakeCore().apply {
            ensureKey()
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            terminalsOf["h1"] = listOf(agent)
            terminalsOf["h2"] = listOf(agent)
            callsOf["h2"] = listOf(CallInfo("h2", "urgent", 100uL, "t1", true,
                "Review deployment", "Please check", CallUrgency.EMERGENCY, emptyList(), null, true))
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.runOnIdle { model.openHost(model.hosts[0]); model.calls.dismissBanner() }
        compose.waitForIdle()
        compose.onNodeWithTag("console-urgent:h2/urgent").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val chat = (model.screen as Screen.Chat).chat
            assertEquals("h2", chat.host.id)
            assertEquals("t1", chat.terminal.address)
        }
    }
}
