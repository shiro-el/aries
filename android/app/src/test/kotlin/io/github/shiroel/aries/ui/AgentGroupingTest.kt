package io.github.shiroel.aries.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class AgentGroupingTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test fun foldedParentAndSpaceKeepCountsAndUrgencyAndCanBeExpanded() {
        val host = Host("h1", "home", Endpoint.Ssh("host", 22u, "me"), null)
        val parent = TerminalInfo("t1", "parent", "", emptyList(), null, "live", AgentStatus.Working)
        val child = parent.copy(address = "t2", name = "child", parent = "t1", agentStatus = AgentStatus.Blocked(null, "Approve"))
        val grandchild = parent.copy(address = "t3", name = "grandchild", parent = "t2")
        val board = AgentBoard(listOf(host)).apply {
            update(host.id, listOf(parent, child, grandchild))
            updateStructure(host.id, HostStructure(listOf(SpaceInfo("s1", "project", null, listOf(
                TabInfo("tab1", "work", listOf(PaneInfo("p1", "t1", parent, listOf(child, grandchild)))),
            ))), emptyList(), null))
        }
        compose.setContent { AgentsScreen(board, {}, {}, { _, _ -> }) }
        compose.onNodeWithText("child").assertExists()
        compose.onNodeWithTag("agent-fold:h1:t1").performClick()
        compose.onNodeWithText("child").assertDoesNotExist()
        compose.onNodeWithText("grandchild").assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.agents_expand_children, "parent", 2)).assertExists()
        compose.onNodeWithTag("agent-summary:h1:t1", useUnmergedTree = true).assert(hasAnyDescendant(hasText(str(R.string.agents_child_count, 2))))
        compose.onNodeWithTag("agent-summary:h1:t1", useUnmergedTree = true).assert(hasAnyDescendant(hasContentDescription(str(R.string.agent_status_blocked))))
        compose.onNodeWithText(str(R.string.agents_view_grouped)).performClick()
        compose.onNodeWithText(str(R.string.agents_space_group, "home", "project", 1)).performClick()
        compose.onNodeWithText("parent").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.agents_space_group, "home", "project", 1)).performClick()
        compose.onNodeWithTag("agent-fold:h1:t1").performClick()
        compose.onNodeWithText("grandchild").assertExists()
    }
}
