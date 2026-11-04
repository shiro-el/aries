package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CursorPos
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.SpaceInfo
import io.github.shiroel.aries.core.TabInfo
import io.github.shiroel.aries.core.TerminalExit
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: opening a Terminal in observe mode, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class TerminalScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)
    private val build = TerminalInfo("t2", "build", "", listOf("make"), "/repo", "live", null, false, 0uL)

    private fun run(col: Int, text: String) =
        CellRun(col.toUShort(), text.length.toUShort(), text, false, null, null, false, false, false, false, false, false)

    private fun frame(cols: Int, rows: Int, full: Boolean, vararg lines: Pair<Int, String>) = TerminalUpdate.Screen(
        ScreenFrame(
            cols.toUShort(), rows.toUShort(), full,
            lines.map { (row, text) -> ScreenLine(row.toUShort(), listOf(run(0, text))) },
            lines.lastOrNull()?.let { CursorPos(it.first.toUShort(), 0u) }, null,
        ),
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

    private fun screenShows(text: String) =
        compose.onNode(hasContentDescription(text, substring = true)).assertExists()

    @Test
    fun opensAnAgentsPaneInObserveModeAndDrawsLiveOutput() {
        val core = FakeCore().apply {
            terminals = listOf(agent)
            structure = HostStructure(
                listOf(SpaceInfo("s1", "work", null, listOf(TabInfo("tab1", "main", listOf(PaneInfo("p1", "t1", agent)))))),
                emptyList(),
                null,
            )
        }
        open(core)
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()

        val view = core.connection!!.views.single()
        assertEquals("t1", view.address)
        assertTrue("attaches as an observer", view.options.observe)

        view.push(frame(120, 3, true, 0 to "$ claude", 1 to "Thinking..."))
        compose.waitForIdle()
        screenShows("Thinking...")
        screenShows(str(R.string.terminal_accessibility_summary, 120, 3))

        // A partial frame updates the cursor line without reading the other rows.
        view.push(frame(120, 3, false, 1 to "Done."))
        compose.waitForIdle()
        screenShows("Done.")

        // The desktop resized the Terminal: the phone follows.
        view.push(frame(80, 2, true, 0 to "resized"))
        compose.waitForIdle()
        screenShows(str(R.string.terminal_accessibility_summary, 80, 2))
        screenShows("resized")
    }

    @Test
    fun terminalAnnouncementStaysBoundedAndReadsOnlyTheCursorLine() {
        val core = FakeCore().apply { terminals = listOf(build) }
        open(core)
        compose.onNodeWithText("build").performClick()
        val view = core.connection!!.views.single()
        val lines = (0 until 80).map { row ->
            ScreenLine(row.toUShort(), listOf(run(0, if (row == 40) "cursor prompt " + "x".repeat(2000) else "unrelated output $row")))
        }
        view.push(TerminalUpdate.Screen(ScreenFrame(4096u, 80u, true, lines, CursorPos(40u, 0u), null)))
        compose.waitForIdle()
        val spoken = compose.onNodeWithTag("terminal-screen").fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("bounded regardless of grid and line lengths", spoken.length <= 512)
        assertTrue("cursor context remains available", spoken.contains("cursor prompt"))
        assertTrue("other screen rows are not read automatically", !spoken.contains("unrelated output"))
    }

    @Test
    fun opensAnUnplacedTerminalAndShowsItsExitCode() {
        val core = FakeCore().apply { terminals = listOf(build) }
        open(core)
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()

        val view = core.connection!!.views.single()
        assertEquals("t2", view.address)
        view.push(frame(80, 1, true, 0 to "error: 1 failed"))
        view.push(TerminalUpdate.Exited(TerminalExit("exited", 2, null)))
        compose.waitForIdle()
        screenShows("error: 1 failed")
        compose.onNodeWithText(str(R.string.terminal_exited_code, 2)).assertExists()
    }

    @Test
    fun backDetachesAndReturnsToTheList() {
        val core = FakeCore().apply { terminals = listOf(build) }
        open(core)
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()
        val view = core.connection!!.views.single()

        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(view.detached)
        assertTrue("still connected to the Host", !core.connection!!.disconnected)
        compose.onNodeWithText("build").assertExists()
    }

    @Test
    fun saysSoWhenTheConnectionDropsAndCannotComeBack() {
        val core = FakeCore().apply { terminals = listOf(build) }
        open(core)
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()
        core.failWith = io.github.shiroel.aries.core.CoreException.AuthRejected()
        core.connection!!.views.single().end()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.connect_auth_rejected)).assertExists()
    }
}
