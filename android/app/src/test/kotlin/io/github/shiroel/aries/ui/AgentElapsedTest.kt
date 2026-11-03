package io.github.shiroel.aries.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentElapsedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun durationUsesHerdrMinuteHourDayBoundariesAndClampsFutureClocks() {
        val cases = listOf(
            0L to AgentDuration(0, AgentTimeUnit.LessThanMinute),
            59_999L to AgentDuration(0, AgentTimeUnit.LessThanMinute),
            60_000L to AgentDuration(1, AgentTimeUnit.Minute),
            3_599_999L to AgentDuration(59, AgentTimeUnit.Minute),
            3_600_000L to AgentDuration(1, AgentTimeUnit.Hour),
            86_399_999L to AgentDuration(23, AgentTimeUnit.Hour),
            86_400_000L to AgentDuration(1, AgentTimeUnit.Day),
            -60_000L to AgentDuration(0, AgentTimeUnit.LessThanMinute),
        )
        cases.forEach { (elapsed, expected) -> assertEquals(expected, agentDuration(1_000uL, 1_000 + elapsed)) }
        assertNull(agentDuration(null, 1_000))
    }

    @Test fun listAndHeaderShowElapsedTimeFromTheCoreStatusTimestamp() {
        val terminal = TerminalInfo("t1", "agent", "", emptyList(), null, "live", AgentStatus.Working,
            statusMs = (System.currentTimeMillis() - 5 * 60_000).toULong())
        val host = Host("h1", "home", Endpoint.Ssh("host", 22u, "me"), null)
        val board = AgentBoard(listOf(host)).apply { update(host.id, listOf(terminal)) }
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                AgentScreenHeader(terminal, host.name, {}, AgentView.TERMINAL, null)
                AgentsScreen(board, {}, {}, { _, _ -> })
            }
        }
        compose.onAllNodesWithText("5m").assertCountEquals(2)
    }
}
