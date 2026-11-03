package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.TerminalInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class AgentStatusMarkTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun drawerMarksNameEveryStatusAndReserveTheSameWidth() {
        val statuses = listOf(
            AgentStatus.Blocked(null, null) to R.string.agent_status_blocked,
            AgentStatus.Error(null) to R.string.agent_status_error,
            AgentStatus.Working to R.string.agent_status_working,
            AgentStatus.Done to R.string.agent_status_done,
            AgentStatus.Idle to R.string.agent_status_idle,
        )
        compose.setContent { androidx.compose.material3.MaterialTheme { Column { statuses.forEach { DrawerAgentStatus(it.first) } } } }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val widths = statuses.map { (_, label) ->
            compose.onNodeWithContentDescription(context.getString(label), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.width
        }
        assertEquals(1, widths.distinct().size)
    }

    @Test
    fun agentHeadersUseEqualWidthAccessibleMarksForEveryStatus() {
        val statuses = listOf(
            AgentStatus.Blocked(null, null) to R.string.agent_status_blocked,
            AgentStatus.Error(null) to R.string.agent_status_error,
            AgentStatus.Working to R.string.agent_status_working,
            AgentStatus.Done to R.string.agent_status_done,
            AgentStatus.Idle to R.string.agent_status_idle,
        )
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                Column {
                    statuses.forEach { (status, _) ->
                        AgentScreenHeader(
                            TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", status),
                            hostName = "home", onBack = {}, current = AgentView.TERMINAL, onView = null,
                        )
                    }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val widths = statuses.map { (_, label) ->
            compose.onNodeWithContentDescription(context.getString(label), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.width
        }
        assertEquals("Header marks must reserve the same width", 1, widths.distinct().size)
    }
}
