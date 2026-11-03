package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.SpaceInfo
import io.github.shiroel.aries.core.StructureProblem
import io.github.shiroel.aries.core.TabInfo
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: browsing a Host's Spaces, Tabs and Panes against a fake core API. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "h1200dp")
class BrowseScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun term(address: String, name: String) =
        TerminalInfo(address, name, "", listOf(name), "/repo", "live")

    private val claude = term("t1", "claude-main")
    private val codex = term("t2", "codex-review")
    private val shell = term("t3", "build-shell")
    private val stray = term("t9", "stray-job")

    private fun pane(id: String, t: TerminalInfo) = PaneInfo(id, t.address, t)

    private val desktop = HostStructure(
        spaces = listOf(
            SpaceInfo(
                "s1", "aries", "/repo",
                listOf(
                    TabInfo("tab1", "agents", listOf(pane("p1", claude), pane("p2", codex))),
                    TabInfo("tab2", "build", listOf(pane("p3", shell))),
                ),
            ),
            SpaceInfo("s2", "~", null, listOf(TabInfo("tab3", "1", listOf(PaneInfo("p9", "t99", null)), named = false)), named = false),
        ),
        unplaced = listOf(stray),
        problem = null,
    )

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
    fun consoleLeavesUseAccessibleMarksAndHostFoldersWithoutMarkingPlainTerminalsAsAgents() {
        val core = FakeCore().apply {
            structure = HostStructure(emptyList(), listOf(
                claude.copy(agentStatus = AgentStatus.Working), shell,
            ), null)
        }
        open(core)
        compose.onNodeWithContentDescription(str(R.string.agent_status_working), useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(str(R.string.agent_status_idle), useUnmergedTree = true).assertDoesNotExist()
        assertEquals(2, compose.onAllNodesWithText("home:/repo").fetchSemanticsNodes().size)
        compose.onNodeWithText("build-shell").performClick()
        compose.waitForIdle()
        assertEquals("t3", core.connection!!.views.single().address)
    }

    @Test
    fun hostRowsPinWithoutOpeningTheAgent() {
        val core = FakeCore().apply {
            structure = HostStructure(emptyList(), listOf(claude.copy(agentStatus = AgentStatus.Working)), null)
            terminals = structure!!.unplaced
        }
        open(core)
        compose.onNodeWithContentDescription(str(R.string.agent_pin_button_label, "claude-main", "home")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h1 t1 pinned=true"), core.labelWrites)
        compose.onNodeWithTag("agent-pin:h1:t1").assertIsSelected()
        assertTrue(core.connection!!.views.isEmpty())
    }

    @Test
    fun hostTreeStartsExpandedAndHeadersToggleWithoutNavigating() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithText("claude-main").assertExists()
        compose.onNodeWithText("build-shell").assertExists()
        compose.onNodeWithText("agents").performClick()
        compose.onNodeWithText("claude-main").assertDoesNotExist()
        compose.onNodeWithText("build-shell").assertExists()
        compose.onNodeWithText("agents").performClick()
        compose.onNodeWithText("claude-main").assertExists()
        compose.onNodeWithText("aries").performClick()
        compose.onNodeWithText("agents").assertDoesNotExist()
        compose.onNodeWithText("stray-job").performScrollTo().assertExists()
        compose.onNodeWithText("aries").performClick()
        compose.onNodeWithText("claude-main").performClick()
        compose.waitForIdle()
        assertEquals("t1", core.connection!!.views.single().address)
    }

    @Test
    fun preservesExplicitWhitespaceNamesFromCoreLikeHerdr() {
        val core = FakeCore().apply {
            structure = HostStructure(listOf(SpaceInfo(
                "s", "  ", null, listOf(TabInfo("tab", "   ", emptyList())),
            )), emptyList(), null)
        }
        open(core)
        compose.onNodeWithText("  ").assertExists()
        compose.onNodeWithText("   ").assertExists()
    }

    @Test
    fun showsTerminalsThatAreInNoPane() {
        val core = FakeCore().apply { structure = desktop }
        open(core)

        compose.onNodeWithText(str(R.string.unplaced_title)).performScrollTo().assertExists()
        compose.onNodeWithText("stray-job").performScrollTo().assertExists()
    }

    @Test
    fun showsAPaneWhoseTerminalIsGone() {
        val core = FakeCore().apply { structure = desktop }
        open(core)

        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText(str(R.string.pane_terminal_gone, "t99")).assertExists()
    }

    @Test
    fun warnsWhenTheStructureCameFromLabels() {
        val core = FakeCore().apply {
            structure = desktop.copy(problem = StructureProblem.UnknownFormat)
        }
        open(core)

        compose.onNodeWithText(str(R.string.structure_unknown_format)).assertExists()
    }

    @Test
    fun followsDesktopChangesRightAway() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithText("aries").assertExists()

        val renamed = desktop.copy(spaces = listOf(desktop.spaces[0].copy(name = "aries-renamed")))
        core.connection!!.push(renamed)
        compose.waitForIdle()

        compose.onNodeWithText("aries-renamed").assertExists()
        compose.onNodeWithText("~").assertDoesNotExist()
    }

    @Test
    fun foldedSpaceSurvivesLeavingAndReopeningTheHost() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithText("aries").performClick()
        compose.onNodeWithText("claude-main").assertDoesNotExist()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-hosts").performClick()
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude-main").assertDoesNotExist()
        compose.onNodeWithText("aries").performClick()
        compose.onNodeWithText("claude-main").assertExists()
    }

    @Test
    fun leavingTheHostStopsWatchingAndDisconnects() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        val conn = core.connection!!

        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-hosts").performClick()
        compose.waitForIdle()

        assertTrue(conn.watchStopped)
        assertTrue(conn.disconnected)
    }
}
