package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import org.robolectric.annotation.GraphicsMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: phone task creation through the real UI and fake core API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewTaskScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private fun open(core: FakeCore) {
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_TASK_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onNodeWithTag("new-task-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun waitsForReadyThenSendsExactlyOnceAndOpensChat() {
        val core = FakeCore().apply {
            savedLaunches += SavedLaunch("saved-codex", "Codex task",
                LaunchRequest("--sandbox workspace-write", "/saved", "old-space", "old-tab",
                    AgentPreset.CODEX, "my-model", "high"))
        }
        open(core)
        compose.onNodeWithTag("host-new-task").performClick()
        compose.onNodeWithTag("new-task-prompt").performScrollTo().performTextInput("Fix the login race")
        compose.onNodeWithText(str(R.string.new_task_start)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.launch_saved)).performClick()
        compose.onNodeWithTag("new-task-saved:saved-codex").performClick()
        compose.onNodeWithText(str(R.string.new_task_browse)).performClick()
        compose.onNodeWithText(str(R.string.new_task_use_folder)).performClick()
        compose.onNodeWithTag("launch_cwd").assertTextContains("/repo")
        screenshot("new-task")
        compose.onNodeWithText(str(R.string.new_task_start)).performClick()
        compose.waitForIdle()
        val conn = core.connection!!
        val started = core.terminals.single { it.address == "t101" }
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
        val held = CompletableDeferred<Unit>()
        compose.runOnIdle {
            conn.sendHold = held
            core.push(listOf(started.copy(agentStatus = AgentStatus.Working)))
        }
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
        compose.runOnIdle { core.push(listOf(started.copy(agentStatus = AgentStatus.Idle))) }
        compose.waitForIdle()
        compose.runOnIdle { core.push(listOf(started.copy(agentStatus = AgentStatus.Idle))); held.complete(Unit) }
        compose.waitUntil { conn.typed.size == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("new-task-prompt").assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertExists()
        assertEquals(listOf("t101" to "Fix the login race"), conn.typed)
        assertEquals(listOf(LaunchRequest("--sandbox workspace-write", "/repo", null, null,
            AgentPreset.CODEX, "my-model", "high")), conn.launched)
    }

    @Test fun uncertainSendKeepsPromptAndNeverRelaunchesOrRetriesIt() {
        val core = FakeCore()
        open(core)
        compose.onNodeWithTag("host-new-task").performClick()
        compose.onNodeWithTag("launch_cwd").performTextInput("/repo")
        compose.onNodeWithTag("new-task-prompt").performScrollTo().performTextInput("Keep this task")
        compose.onNodeWithText(str(R.string.new_task_start)).performClick()
        compose.waitForIdle()
        val conn = core.connection!!
        compose.runOnIdle {
            conn.sendFailWith = CoreException.Closed()
            core.push(core.terminals.map { if (it.address == "t101") it.copy(agentStatus = AgentStatus.Idle) else it })
        }
        compose.waitForIdle()
        compose.onNodeWithTag("new-task-prompt").performScrollTo().assertTextContains("Keep this task")
        compose.onNodeWithText(str(R.string.new_task_delivery_unknown)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.new_task_open_chat)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextContains("Keep this task")
        compose.runOnIdle {
            assertEquals(1, conn.launched.size)
            assertEquals(1, conn.calls.count { it == "sendMessage" })
        }
    }

    @Test fun issuePrefillsEditableContextAndRepositoryFolder() {
        val core = FakeCore().apply {
            terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/launch", "live", AgentStatus.Idle))
        }
        open(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.github_button)).performClick()
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithText(str(R.string.new_task_from_issue)).performClick()
        compose.onNodeWithTag("launch_cwd").performScrollTo().assertTextContains("/repo")
        compose.onNodeWithTag("new-task-prompt").performScrollTo()
            .assertTextContains("Issue title", substring = true)
            .assertTextContains("#42", substring = true)
            .assertTextContains("https://github.com/octo/project/issues/42", substring = true)
            .assertTextContains("Issue body", substring = true)
            .performTextInput("\nFocus on tests")
        screenshot("issue-task")
        compose.onNodeWithText(str(R.string.new_task_start)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("/repo", core.connection!!.launched.single().cwd)
            assertEquals(AgentPreset.CLAUDE_CODE, core.connection!!.launched.single().preset)
        }
    }
}
