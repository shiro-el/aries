package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression: changing layout through Settings must not close an Agent or lose its unsent draft. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class AppLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun changingLayoutInSettingsKeepsTheOpenAgentDraftAndConnection() {
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        }
        val preferences = context.getSharedPreferences("layout-ui-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = ThemeSettings(preferences)
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        val original = model.screen as Screen.Chat
        val connection = core.connection!!
        compose.onNodeWithContentDescription(context.getString(R.string.chat_input_label)).performTextInput("keep my draft")
        for (layout in listOf(AppLayout.THREAD, AppLayout.TASKS, AppLayout.CONSOLE)) {
            compose.runOnIdle { model.openSettings() }
            val label = context.getString(when (layout) {
                AppLayout.CONSOLE -> R.string.layout_console
                AppLayout.THREAD -> R.string.layout_thread
                AppLayout.TASKS -> R.string.layout_tasks
            })
            compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("console-back").performClick()
            compose.runOnIdle {
                assertEquals(layout, settings.layout)
                assertSame(original, model.screen)
                assertSame(connection, original.chat.link?.connection)
                assertFalse("Settings must keep the Host connection", connection.disconnected)
                assertFalse("Settings must keep the Chat stream", core.chatStopped)
                assertEquals(listOf("h1" to "t1"), core.chatsOpened)
            }
            compose.onNodeWithContentDescription(context.getString(R.string.chat_input_label)).assertTextEquals("keep my draft")
        }
    }
}
