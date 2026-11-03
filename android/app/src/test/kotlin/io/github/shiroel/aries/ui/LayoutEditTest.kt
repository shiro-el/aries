package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.LayoutEdit
import io.github.shiroel.aries.core.SpaceInfo
import io.github.shiroel.aries.core.TabInfo
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.CoreException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose -> fake core API seam; dialogs need a tall viewport in Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class LayoutEditTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun open(core: FakeCore) {
        core.addHost("home", Endpoint.Ssh("example.invalid", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
    }

    @Test fun unavailableAppDisablesLayoutEditsButTerminalKillStillWorks() {
        val terminal = TerminalInfo("t1", "build", "", listOf("sleep"), "/repo", "live")
        val core = FakeCore().apply {
            herdrAppAvailable = false
            structure = HostStructure(emptyList(), listOf(terminal), null)
        }
        open(core)
        compose.onNodeWithText(str(R.string.layout_new_space_button)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.layout_app_unavailable)).assertExists()
        compose.onNodeWithText(str(R.string.layout_kill_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_confirm_title, "build")).assertExists()
        compose.onNodeWithText(str(R.string.layout_impact, 1, 0)).assertExists()
        assertEquals(emptyList<String>(), core.connection!!.killed)
        compose.onNodeWithText(str(R.string.layout_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1"), core.connection!!.killed)
    }

    @Test fun workingTerminalRequiresTwoSecondsAndReleasingEarlyCancels() {
        val terminal = TerminalInfo("t1", "agent", "", listOf("codex"), "/repo", "live", agentStatus = AgentStatus.Working)
        val core = FakeCore().apply { structure = HostStructure(emptyList(), listOf(terminal), null) }
        open(core)
        compose.onNodeWithText(str(R.string.layout_kill_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_working_warning)).assertExists()
        compose.onNodeWithText(str(R.string.layout_confirm_button)).assertDoesNotExist()
        val hold = compose.onNodeWithText(str(R.string.layout_hold_button))
        hold.performTouchInput { longClick(durationMillis = 1_900) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), core.connection!!.killed)
        hold.performTouchInput { longClick(durationMillis = 2_100) }
        compose.waitForIdle()
        assertEquals(listOf("t1"), core.connection!!.killed)
    }

    @Test fun createsAndRenamesSpacesAndTabsWithoutADestructiveConfirmation() {
        val core = FakeCore().apply {
            structure = HostStructure(listOf(SpaceInfo("s1", "repo", "/repo", listOf(TabInfo("tab1", "build", emptyList())))), emptyList(), null)
        }
        open(core)
        compose.onNodeWithText(str(R.string.layout_new_space_button)).performClick()
        compose.onNodeWithTag("layout-name").performTextReplacement("new space")
        compose.onNodeWithTag("layout-folder").performTextReplacement("/new/repo")
        compose.onNodeWithText(str(R.string.layout_save_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LayoutEdit.NewSpace("new space", "/new/repo")), core.connection!!.edits)

        compose.onNodeWithText("repo").performClick()
        compose.onNodeWithText(str(R.string.layout_new_tab_button)).performClick()
        compose.onNodeWithTag("layout-name").performTextReplacement("tests")
        compose.onNodeWithText(str(R.string.layout_save_button)).performClick()
        compose.waitForIdle()
        assertEquals(LayoutEdit.NewTab("s1", "tests", "/repo", null), core.connection!!.edits.last())

        compose.onNodeWithText(str(R.string.layout_actions_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_rename_space_button)).performClick()
        compose.onNodeWithTag("layout-name").performTextReplacement("renamed")
        compose.onNodeWithText(str(R.string.layout_save_button)).performClick()
        compose.waitForIdle()
        assertEquals(LayoutEdit.RenameSpace("s1", "renamed"), core.connection!!.edits.last())

        compose.onNodeWithText(str(R.string.layout_actions_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_rename_tab_button)).performClick()
        compose.onNodeWithTag("layout-name").performTextReplacement("")
        compose.onNodeWithText(str(R.string.layout_save_button)).performClick()
        compose.waitForIdle()
        assertEquals(LayoutEdit.RenameTab("tab1", ""), core.connection!!.edits.last())
        compose.onNodeWithText(str(R.string.layout_confirm_button)).assertDoesNotExist()
    }

    @Test fun closingASpaceCountsChildAgentsAndUsesTheirLatestWorkingStatus() {
        val parent = TerminalInfo("t1", "parent", "", listOf("sh"), "/repo", "live")
        val child = parent.copy(address = "t2", name = "child", parent = "t1", agentStatus = AgentStatus.Idle)
        val shell = parent.copy(address = "t3", name = "shell")
        val core = FakeCore().apply {
            structure = HostStructure(listOf(SpaceInfo("s1", "repo", "/repo", listOf(
                TabInfo("tab1", "agents", listOf(PaneInfo("p1", "t1", parent, listOf(child)))),
                TabInfo("tab2", "build", listOf(PaneInfo("p2", "t3", shell))),
            ))), emptyList(), null)
        }
        open(core)
        compose.onNodeWithText("repo").performClick()
        compose.onNodeWithText(str(R.string.layout_actions_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_close_space_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_confirm_title, "repo")).assertExists()
        compose.onNodeWithText(str(R.string.layout_impact, 3, 1)).assertExists()
        // Status changes while the confirmation is open: a stale normal
        // confirmation must never stop a newly Working child Agent.
        core.push(listOf(parent, child.copy(agentStatus = AgentStatus.Working), shell))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.layout_working_warning)).assertExists()
        compose.onNodeWithText(str(R.string.layout_confirm_button)).assertDoesNotExist()
        assertEquals(emptyList<LayoutEdit>(), core.connection!!.edits)
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        assertEquals(emptyList<LayoutEdit>(), core.connection!!.edits)

        compose.onNodeWithText(str(R.string.layout_actions_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_close_space_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_hold_button)).performTouchInput { longClick(durationMillis = 2_100) }
        compose.waitForIdle()
        assertEquals(listOf(LayoutEdit.CloseSpace("s1")), core.connection!!.edits)
    }

    @Test fun closingTheLastTabStillUsesAnExplicitConfirmedTabClose() {
        val core = FakeCore().apply {
            structure = HostStructure(listOf(SpaceInfo("s1", "repo", null, listOf(TabInfo("only", "build", emptyList())))), emptyList(), null)
        }
        open(core)
        compose.onNodeWithText("repo").performClick()
        compose.onNodeWithText(str(R.string.layout_actions_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_close_tab_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_confirm_title, "build")).assertExists()
        compose.onNodeWithText(str(R.string.layout_impact, 0, 0)).assertExists()
        assertEquals(emptyList<LayoutEdit>(), core.connection!!.edits)
        compose.onNodeWithText(str(R.string.layout_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LayoutEdit.CloseTab("only")), core.connection!!.edits)
    }

    @Test fun aFailedEditKeepsTheDialogAndNeverReplaysIt() {
        val core = FakeCore().apply {
            structure = HostStructure(listOf(SpaceInfo("s1", "repo", null, listOf(TabInfo("only", "build", emptyList())))), emptyList(), null)
        }
        open(core)
        core.connection!!.editFailure = CoreException.Rpc("herdr_app_failed", "closed window")
        compose.onNodeWithText("repo").performClick()
        compose.onNodeWithText(str(R.string.layout_actions_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_close_tab_button)).performClick()
        compose.onNodeWithText(str(R.string.layout_confirm_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.layout_confirm_title, "build")).assertExists()
        assertEquals(listOf(LayoutEdit.CloseTab("only")), core.connection!!.edits)
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LayoutEdit.CloseTab("only")), core.connection!!.edits)
    }
}
