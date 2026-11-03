package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsoleAgentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun waitingRowsComeBeforePinnedWorkAndKeepMessageLocationAndPinActions() {
        val core = FakeCore().apply { addHost("home", Endpoint.Ssh("host", 22u, "me")) }
        val board = AgentBoard(core.hosts())
        fun terminal(address: String, name: String, status: AgentStatus, pinned: Boolean = false) =
            TerminalInfo(address, name, "", listOf(name), "/repo/$name", "live", status, false, 0uL, pinned)
        board.update("h1", listOf(
            terminal("t1", "build", AgentStatus.Working, pinned = true),
            terminal("t2", "review", AgentStatus.Error("Please retry")),
            terminal("t3", "idle", AgentStatus.Idle),
        ))
        var opened: AgentKey? = null
        var pin: Pair<AgentKey, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                AgentsScreen(board, {}, { opened = it.key }, { entry, value -> pin = entry.key to value }, showHeader = false)
            }
        }
        val waiting = compose.onNodeWithText("review").fetchSemanticsNode().boundsInRoot.top
        val work = compose.onNodeWithText("build").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Waiting Agent must be above pinned work", waiting < work)
        compose.onNodeWithText("Please retry").assertExists()
        compose.onNodeWithText("home:/repo/build").assertExists()
        compose.onNodeWithText("review").performClick()
        assertEquals(AgentKey("h1", "t2"), opened)
        compose.onNodeWithContentDescription("Pin review on home").performClick()
        assertEquals(AgentKey("h1", "t2") to true, pin)
    }
}
