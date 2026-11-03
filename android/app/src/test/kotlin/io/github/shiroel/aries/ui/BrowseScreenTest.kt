package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
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
            SpaceInfo("s2", "", null, listOf(TabInfo("tab3", "", listOf(PaneInfo("p9", "t99", null))))),
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
    fun showsSpacesThenTabsAndPanesLikeHerdrApp() {
        val core = FakeCore().apply { structure = desktop }
        open(core)

        compose.onNodeWithText("aries").assertExists()
        compose.onNodeWithText(str(R.string.space_unnamed)).assertExists()

        compose.onNodeWithText("aries").performClick()
        // First Tab is shown, with its Panes in layout order.
        compose.onNodeWithText("agents").assertExists()
        compose.onNodeWithText("build").assertExists()
        compose.onNodeWithText("claude-main").assertExists()
        compose.onNodeWithText("codex-review").assertExists()
        compose.onNodeWithText("build-shell").assertDoesNotExist()

        compose.onNodeWithText("build").performClick()
        compose.onNodeWithText("build-shell").assertExists()
        compose.onNodeWithText("claude-main").assertDoesNotExist()
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

        compose.onNodeWithText(str(R.string.space_unnamed)).performClick()
        compose.onNodeWithText(str(R.string.tab_unnamed)).assertExists()
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
        compose.onNodeWithText(str(R.string.space_unnamed)).assertDoesNotExist()
    }

    @Test
    fun focusAndZoomStayOnThePhoneAndSurviveDesktopUpdates() {
        val core = FakeCore().apply { structure = desktop }
        open(core)

        compose.onNodeWithText("aries").performClick()
        compose.onNodeWithText("codex-review").performClick() // focus
        compose.onNodeWithText(str(R.string.pane_zoom_button)).performClick()
        compose.onNodeWithText("claude-main").assertDoesNotExist()
        compose.onNodeWithText("codex-review").assertExists()

        // The desktop changes something unrelated; the phone's view stays put.
        core.connection!!.push(desktop.copy(unplaced = emptyList()))
        compose.waitForIdle()
        compose.onNodeWithText("codex-review").assertExists()
        compose.onNodeWithText("claude-main").assertDoesNotExist()

        compose.onNodeWithText(str(R.string.pane_unzoom_button)).performClick()
        compose.onNodeWithText("claude-main").assertExists()

        // Browsing only ever read from the Host.
        assertEquals(listOf("watchStructure"), core.connection!!.calls)
    }

    @Test
    fun zoomFallsBackWhenTheDesktopClosesThatPane() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithText("aries").performClick()
        compose.onNodeWithText("codex-review").performClick()
        compose.onNodeWithText(str(R.string.pane_zoom_button)).performClick()

        val tab1 = desktop.spaces[0].tabs[0].copy(panes = listOf(pane("p1", claude)))
        val space = desktop.spaces[0].copy(tabs = listOf(tab1, desktop.spaces[0].tabs[1]))
        core.connection!!.push(desktop.copy(spaces = listOf(space, desktop.spaces[1])))
        compose.waitForIdle()

        compose.onNodeWithText("claude-main").assertExists()
        compose.onNodeWithText(str(R.string.pane_unzoom_button)).assertDoesNotExist()
    }

    @Test
    fun leavingTheHostStopsWatchingAndDisconnects() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        val conn = core.connection!!

        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()

        assertTrue(conn.watchStopped)
        assertTrue(conn.disconnected)
    }
}
