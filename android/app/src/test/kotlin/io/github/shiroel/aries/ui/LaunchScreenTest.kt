package io.github.shiroel.aries.ui

import androidx.compose.ui.test.onNodeWithContentDescription

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.CompletableDeferred
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "h1200dp")
class LaunchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun screenshot(name: String) {
        System.getenv("ARIES_TASK_RENDER_DIR")?.let { directory ->
            File(directory, "$name.png").outputStream().use {
                compose.onNodeWithTag("launch-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

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

    // Regression: a delayed/lost launch reply must not allow a duplicate write after
    // dismissal or through the saved-launch entry; existing spawn_failed tests miss it.
    // Regression D87: retiring an uncertain saved launch must release the Host without executing that saved request again.
    @Test fun stopTrackingSavedLaunchAllowsAnUnrelatedNewTask() {
        val saved = io.github.shiroel.aries.core.SavedLaunch("L1", "Build repo", LaunchRequest("make", "/repo", null, null))
        val core = FakeCore().apply { savedLaunches += saved }
        open(core)
        val conn = core.connection!!
        conn.launchReplyFailure = CoreException.Closed()
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithTag("saved_run:L1").performClick()
        compose.onNodeWithText(str(R.string.launch_stop_tracking_button)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithText(str(R.string.new_task_title)).performClick()
        compose.onNodeWithTag("new-task-prompt").performScrollTo().performTextInput("Different work")
        compose.onNodeWithText(str(R.string.new_task_start)).assertIsEnabled()
        assertEquals(listOf(saved.request), conn.launched)
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
    }

    @Test fun delayedSavedLaunchAndLostReplyRequireExplicitDuplicateApproval() {
        val saved = io.github.shiroel.aries.core.SavedLaunch("L1", "Build repo", LaunchRequest("make", "/repo", null, null))
        val core = FakeCore().apply { savedLaunches += saved }
        open(core)
        val conn = core.connection!!
        val reply = CompletableDeferred<Unit>()
        conn.launchReplyHold = reply
        conn.launchReplyFailure = CoreException.Closed()
        fun runSaved() {
            compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
            compose.onNodeWithTag("saved_run:L1").performClick()
        }
        runSaved()
        compose.onNodeWithText(str(R.string.launch_starting)).assertExists()
        screenshot("launch-starting")
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        runSaved()
        assertEquals(1, conn.launched.size)
        compose.runOnIdle { reply.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.launch_button)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        // Leaving and reconnecting must retain uncertainty while rebinding its recovery to the new line.
        compose.openConsoleDrawer()
        compose.onNodeWithTag("drawer-hosts").performScrollTo().performClick()
        compose.onNodeWithText("home").performClick()
        runSaved()
        val retryConnection = core.connection!!
        compose.onNodeWithText(str(R.string.launch_button)).assertIsNotEnabled()
        screenshot("launch-unknown")
        compose.onNodeWithText(str(R.string.launch_review_button)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.launch_again_button)).assertExists()
        assertEquals(1, conn.launched.size)
        compose.onNodeWithText(str(R.string.launch_again_button)).performClick()
        compose.waitForIdle()
        assertEquals(1, conn.launched.size)
        assertEquals(listOf(saved.request), retryConnection.launched)
        compose.onNodeWithTag("terminal-screen").assertExists()
    }

    @Test
    fun launchesIntoTheTabShownAndOpensTheNewTerminal() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithTag("host-layout-context").performClick()
        compose.onNodeWithTag("host-context-tab:s1:tab2").performClick()

        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithText(str(R.string.launch_open_button)).performClick()
        compose.onNodeWithText(str(R.string.launch_button)).assertIsNotEnabled()
        command("my-tool --flag")
        compose.onNodeWithText(str(R.string.launch_cwd_space_folder, "/home/me/aries")).performClick()
        run()

        assertEquals(
            listOf(LaunchRequest("my-tool --flag", "/home/me/aries", "s1", "tab2")),
            core.connection!!.launched,
        )
        // The creation opens the actual Terminal, even if its list row is folded.
        compose.onNodeWithText(str(R.string.launch_title)).assertDoesNotExist()
        compose.onNodeWithText("my-tool --flag").assertExists()
        compose.onNodeWithText("my-tool --flag").assertExists()
    }

    @Test
    fun picksAnotherSpaceAndTab() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithText(str(R.string.launch_open_button)).performClick()
        compose.onNodeWithTag("launch_space:s2").performClick()
        compose.onNodeWithTag("launch_tab:tab3").performClick()
        command("htop")
        run()
        assertEquals(LaunchRequest("htop", null, "s2", "tab3"), core.connection!!.launched.last())
        compose.onNodeWithTag("terminal-screen").assertExists()
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.onNodeWithTag("host-layout-context").assertExists()
        compose.onNodeWithText("notes · todo").assertExists()
        compose.onNodeWithText("htop").assertExists()
    }

    @Test
    fun offersRecentFoldersAndNoSpace() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        core.connection!!.recentCwds = listOf("/srv/app", "/tmp")
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithText(str(R.string.launch_open_button)).performClick()
        compose.onNodeWithText("/tmp").performClick()
        compose.onNodeWithText(str(R.string.launch_space_none)).performClick()
        command("make test")
        run()

        assertEquals(LaunchRequest("make test", "/tmp", null, null), core.connection!!.launched.last())
        compose.onNodeWithTag("terminal-screen").assertExists()
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        // Unplaced Terminals show on the Host list.
        compose.onNodeWithTag("host-plain-terminals").performScrollTo().assertExists()
        compose.onNodeWithText("make test").performScrollTo().assertExists()
    }

    @Test
    fun showsWhyALaunchFailedAndKeepsTheForm() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        core.connection!!.launchFailWith =
            CoreException.Rpc("spawn_failed", "cannot start \"nope\": No such file or directory (os error 2)")
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
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
