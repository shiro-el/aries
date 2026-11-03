package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class SubAgentsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val parent = TerminalInfo("t1", "parent", "", listOf("agent"), "/repo", "live", AgentStatus.Working)
    private val child = parent.copy(address = "t2", name = "child", parent = "t1", agentStatus = AgentStatus.Error("Report to parent"))
    private val grandchild = parent.copy(address = "t3", name = "grandchild", parent = "t2")

    private fun assertUnder(child: String, parent: String, drawer: Boolean = false) {
        fun bounds(name: String) = if (drawer) compose.onNode(
            hasText(name) and hasAnyAncestor(hasTestTag("console-drawer")), useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot else compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val c = bounds(child)
        val p = bounds(parent)
        assertTrue("$child must follow $parent", c.top > p.top)
        assertTrue("$child must be indented under $parent", c.left > p.left)
    }

    @Test fun browseShowsPaneDescendantsAndOpensTheirOwnTerminal() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            structure = HostStructure(listOf(SpaceInfo("s1", "project", null, listOf(
                TabInfo("tab1", "work", listOf(PaneInfo("p1", "t1", parent, listOf(grandchild, child)))),
            ))), emptyList(), null)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            AriesApp(remember { HostsModel(core, scope) })
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText(context.getString(R.string.unplaced_title)).assertDoesNotExist()
        compose.onNodeWithText("project").performClick()
        assertUnder("child", "parent")
        assertUnder("grandchild", "child")
        compose.onNodeWithText("child").performClick()
        compose.waitForIdle()
        assertEquals("t2", core.connection!!.views.single().address)
    }

    @Test fun unplacedFamiliesKeepTheirParentAndMissingAncestorsStayVisible() {
        val host = Host("h1", "home", Endpoint.Ssh("home.test", 22u, "me"), null)
        val browser = HostBrowser(host).apply {
            update(HostStructure(emptyList(), listOf(grandchild, child, parent, parent.copy(address = "t4", name = "orphan", parent = "gone")), null))
        }
        compose.setContent { BrowseScreen(browser, {}) }
        assertUnder("child", "parent")
        assertUnder("grandchild", "child")
        compose.onNodeWithText("orphan").assertExists()
    }

    @Test fun agentsRetainAPlainParentAndOpenItsOwnTerminal() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminalsOf["h1"] = listOf(child, parent.copy(agentStatus = null))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            AriesApp(remember { HostsModel(core, scope) })
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.agents_button)).performClick()
        assertUnder("child", "parent")
        compose.onNodeWithText("parent").performClick()
        compose.waitForIdle()
        assertEquals("t1", core.connectionsOf.getValue("h1").views.single().address)
    }

    @Test fun agentsAndDrawerGroupChildrenWithoutCountingThemAsWaiting() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminalsOf["h1"] = listOf(child, grandchild, parent)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            AriesApp(remember { HostsModel(core, scope) })
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.agents_button)).performClick()
        assertUnder("child", "parent")
        assertUnder("grandchild", "child")
        compose.openConsoleDrawer()
        compose.onNodeWithText(context.getString(R.string.console_agents_waiting, 0)).assertExists()
        val root = compose.onNodeWithTag("drawer-agent:h1:t1").fetchSemanticsNode().boundsInRoot
        val sub = compose.onNodeWithTag("drawer-agent:h1:t2").fetchSemanticsNode().boundsInRoot
        assertTrue(root.top < sub.top)
        assertUnder("child", "parent", drawer = true)
        compose.onNodeWithTag("drawer-agent:h1:t2").performClick()
        compose.waitForIdle()
        assertEquals("t2", core.connectionsOf.getValue("h1").views.single().address)
    }
}
