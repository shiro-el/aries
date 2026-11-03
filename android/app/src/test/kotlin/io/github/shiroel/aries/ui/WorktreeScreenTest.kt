package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class WorktreeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private fun open(unplaced: Boolean = false): FakeConnection {
        val terminal = TerminalInfo("t1", "build", "", listOf("sh"), "/launch", "live",
            foreground = ForegroundInfo(42u, "sh", listOf("sh"), "/current"))
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("example.invalid", 22u, "me"))
            structure = HostStructure(listOf(SpaceInfo("s1", "repo", "/space", listOf(
                TabInfo("tab1", "build", listOf(PaneInfo("p1", "t1", terminal))),
            ))), emptyList(), null)
            if (unplaced) structure = HostStructure(emptyList(), listOf(terminal), null)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        if (!unplaced) compose.onNodeWithText("repo").performClick()
        compose.waitForIdle()
        return core.connection!!
    }

    @Test fun choosesAWorktreeAndAgentUsingTheCurrentFolderAndExplicitSpace() {
        val conn = open()
        conn.worktrees = listOf(
            WorktreeInfo("/linked", "abc123", "refs/heads/topic", false, false, null, null),
            WorktreeInfo("/bare", null, null, false, true, null, null),
            WorktreeInfo("/gone", "abc123", null, true, false, null, "missing"),
        )
        compose.onNodeWithText(str(R.string.worktrees_button)).performClick()
        compose.onNodeWithText(str(R.string.worktrees_open_button)).assertIsNotEnabled()
        compose.onNodeWithTag("worktree:/bare").assertIsNotEnabled()
        compose.onNodeWithTag("worktree:/gone").assertIsNotEnabled()
        assertEquals(listOf("/current"), conn.worktreeFolders)
        compose.onNodeWithTag("worktree:/linked").performClick()
        compose.onNodeWithTag("worktree-preset:CODEX").performClick()
        compose.onNodeWithText(str(R.string.worktrees_open_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("/linked", "s1", AgentPreset.CODEX)), conn.openedWorktrees)
        compose.onNodeWithText(str(R.string.worktrees_title)).assertDoesNotExist()
    }

    @Test fun failedOpenKeepsTheSelectionAndDoesNotRepeatTheRequest() {
        val conn = open()
        conn.worktrees = listOf(WorktreeInfo("/linked", "abc123", null, true, false, "", null))
        conn.worktreeFailure = CoreException.Rpc("herdr_app_failed", "closed window")
        compose.onNodeWithText(str(R.string.worktrees_button)).performClick()
        compose.onNodeWithTag("worktree:/linked").performClick()
        compose.onNodeWithText(str(R.string.worktrees_open_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.worktrees_title)).assertExists()
        assertEquals(listOf(Triple("/linked", "s1", null)), conn.openedWorktrees)
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        compose.waitForIdle()
        assertEquals(1, conn.openedWorktrees.size)
    }

    @Test fun unplacedTerminalsCanOpenWorktreesWithoutChoosingADesktopSpace() {
        val conn = open(unplaced = true)
        compose.onNodeWithText("build").performTouchInput { longClick() }
        conn.worktrees = listOf(WorktreeInfo("/linked", "abc123", null, true, false, null, null))
        compose.onNodeWithText(str(R.string.worktrees_button)).performClick()
        compose.onNodeWithTag("worktree:/linked").performClick()
        compose.onNodeWithText(str(R.string.worktrees_open_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("/linked", null, null)), conn.openedWorktrees)
        compose.onNodeWithText(str(R.string.worktrees_title)).assertDoesNotExist()
    }
}
