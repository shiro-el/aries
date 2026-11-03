package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.ForegroundInfo
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ForegroundScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowsAndHeadersFollowForegroundAndFallBackWhenItClears() {
        val initial = TerminalInfo("t1", "build", "", listOf("/bin/sh"), "/launch", "live")
        val terminal = mutableStateOf(initial)
        compose.setContent {
            MaterialTheme { Column {
                ConsoleTerminalRow(terminal.value, "home", {})
                AgentScreenHeader(terminal.value, "home", {}, AgentView.TERMINAL, null)
            } }
        }
        // The compact header exposes the current program in its actions menu.
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onAllNodesWithText("sh").assertCountEquals(2)
        compose.runOnIdle {
            terminal.value = initial.copy(foreground = ForegroundInfo(42u, "nvim", listOf("nvim", "file"), "/current"))
        }
        compose.onAllNodesWithText("nvim").assertCountEquals(2)
        compose.onNodeWithText("/current").assertExists()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.console_agent_location, "home", "/current")).assertExists()
        compose.runOnIdle { terminal.value = initial }
        compose.onAllNodesWithText("nvim").assertCountEquals(0)
        compose.onAllNodesWithText("sh").assertCountEquals(2)
        compose.onNodeWithText("/launch").assertExists()
    }
}
