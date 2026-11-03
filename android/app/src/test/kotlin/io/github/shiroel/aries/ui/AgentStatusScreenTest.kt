package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: live Agent status on one Host's Terminal list, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class AgentStatusScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private fun terminal(address: String, name: String, status: AgentStatus?, attention: Boolean = false) =
        TerminalInfo(address, name, "", listOf(name), "/repo", "live", status, attention, 0uL)

    private fun open(core: FakeCore) {
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
    }

    @Test
    fun showsEachAgentsStatusAndLeavesOtherProgramsWithoutOne() {
        val core = FakeCore().apply {
            terminals = listOf(
                terminal("t1", "claude", AgentStatus.Working),
                terminal("t2", "vim", null),
            )
        }
        open(core)

        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithContentDescription(str(R.string.agent_status_working), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("vim").assertExists()
        // Only one status line: the program that never reported has none.
        assertEquals(1, statusLines())
        compose.onNodeWithText(str(R.string.agent_attention_label)).assertDoesNotExist()
    }

    @Test
    fun updatesLiveAndShowsWhyAnAgentIsBlocked() {
        val core = FakeCore().apply { terminals = listOf(terminal("t1", "claude", AgentStatus.Working)) }
        open(core)

        core.push(
            listOf(
                terminal(
                    "t1", "claude",
                    AgentStatus.Blocked(BlockedReason.PERMISSION, "approve Write: hello.txt"),
                    attention = true,
                ),
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription(str(R.string.agent_status_blocked), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("approve Write: hello.txt").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.agent_status_blocked_permission)))
        compose.onNodeWithText("approve Write: hello.txt").assertExists()
        compose.onNodeWithText(str(R.string.agent_attention_label)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.agent_status_working), useUnmergedTree = true).assertDoesNotExist()

        core.push(listOf(terminal("t1", "claude", AgentStatus.Done)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.agent_status_done), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(str(R.string.agent_attention_label)).assertDoesNotExist()
    }

    @Test
    fun anErrorNeedsAttentionLikeBlocked() {
        val core = FakeCore().apply {
            terminals = listOf(
                terminal("t1", "pi", AgentStatus.Error("500 upstream failure"), attention = true),
                terminal("t2", "codex", AgentStatus.Idle),
            )
        }
        open(core)

        compose.onNodeWithContentDescription(str(R.string.agent_status_error), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("500 upstream failure").assertExists()
        compose.onNodeWithContentDescription(str(R.string.agent_status_idle), useUnmergedTree = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText(str(R.string.agent_attention_label)).fetchSemanticsNodes().size)
    }

    private fun statusLines(): Int = listOf(
        R.string.agent_status_working, R.string.agent_status_blocked, R.string.agent_status_error,
        R.string.agent_status_done, R.string.agent_status_idle,
    ).sumOf { compose.onAllNodesWithContentDescription(str(it), useUnmergedTree = true).fetchSemanticsNodes().size }
}
