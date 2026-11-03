package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.SpaceInfo
import io.github.shiroel.aries.core.TabInfo
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: launching a Terminal from a custom command against a fake core API. */
@RunWith(RobolectricTestRunner::class)
class LaunchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val claude = TerminalInfo("t1", "claude-main", "", listOf("claude"), "/repo", "live")

    private val desktop = HostStructure(
        spaces = listOf(
            SpaceInfo(
                "s1", "aries", "/home/me/aries",
                listOf(
                    TabInfo("tab1", "agents", listOf(PaneInfo("p1", "t1", claude))),
                    TabInfo("tab2", "build", emptyList()),
                ),
            ),
            SpaceInfo("s2", "notes", null, listOf(TabInfo("tab3", "todo", emptyList()))),
        ),
        unplaced = emptyList(),
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

    private fun command(text: String) =
        compose.onNodeWithText(str(R.string.launch_command)).performTextInput(text)

    private fun run() {
        compose.onNodeWithText(str(R.string.launch_button)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun launchesIntoTheTabShownAndOpensTheNewTerminal() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithText("aries").performClick()
        compose.onNodeWithText("build").performClick()

        compose.onNodeWithText(str(R.string.launch_open_button)).performClick()
        compose.onNodeWithText(str(R.string.launch_button)).assertIsNotEnabled()
        command("my-tool --flag")
        compose.onNodeWithText(str(R.string.launch_cwd_space_folder, "/home/me/aries")).performClick()
        run()

        assertEquals(
            listOf(LaunchRequest("my-tool --flag", "/home/me/aries", "s1", "tab2")),
            core.connection!!.launched,
        )
        // The form closed, the new Terminal is in the Tab and focused (zoom is offered).
        compose.onNodeWithText(str(R.string.launch_title)).assertDoesNotExist()
        compose.onNodeWithText("my-tool --flag").assertExists()
        compose.onNodeWithText(str(R.string.pane_zoom_button)).assertExists()
    }

    @Test
    fun picksAnotherSpaceAndTab() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithText(str(R.string.launch_open_button)).performClick()
        compose.onNodeWithTag("launch_space:s2").performClick()
        compose.onNodeWithTag("launch_tab:tab3").performClick()
        command("htop")
        run()
        assertEquals(LaunchRequest("htop", null, "s2", "tab3"), core.connection!!.launched.last())
        compose.onNodeWithText("todo").assertExists()
        compose.onNodeWithText("htop").assertExists()
    }

    @Test
    fun offersRecentFoldersAndNoSpace() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        core.connection!!.recentCwds = listOf("/srv/app", "/tmp")
        compose.onNodeWithText(str(R.string.launch_open_button)).performClick()
        compose.onNodeWithText("/tmp").performClick()
        compose.onNodeWithText(str(R.string.launch_space_none)).performClick()
        command("make test")
        run()

        assertEquals(LaunchRequest("make test", "/tmp", null, null), core.connection!!.launched.last())
        // Unplaced Terminals show on the Space list.
        compose.onNodeWithText(str(R.string.unplaced_title)).performScrollTo().assertExists()
        compose.onNodeWithText("make test").performScrollTo().assertExists()
    }

    @Test
    fun showsWhyALaunchFailedAndKeepsTheForm() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        core.connection!!.launchFailWith =
            CoreException.Rpc("spawn_failed", "cannot start \"nope\": No such file or directory (os error 2)")
        compose.onNodeWithText(str(R.string.launch_open_button)).performClick()
        command("nope")
        run()

        compose.onNodeWithText(
            str(R.string.launch_spawn_failed, "cannot start \"nope\": No such file or directory (os error 2)"),
        ).assertExists()
        compose.onNodeWithText(str(R.string.launch_title)).assertExists()

        // Fixing it and retrying works from the same form.
        core.connection!!.launchFailWith = null
        run()
        compose.onNodeWithText(str(R.string.launch_title)).assertDoesNotExist()
    }
}
