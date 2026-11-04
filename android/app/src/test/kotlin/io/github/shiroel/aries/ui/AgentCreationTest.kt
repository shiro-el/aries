package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Existing phone UI -> fake core seam: creation must retain the selected Host's live link. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp")
class AgentCreationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private lateinit var model: HostsModel

    private fun open(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
    }

    @Test fun newTaskFromSingleHostBoardSendsPromptAndOpensChatWithoutLeavingTheBoardConnection() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.invalid", 22u, "me"))
            terminalsOf["h1"] = emptyList()
        }
        open(core)
        compose.onNodeWithText(str(R.string.new_task_title)).performClick()
        compose.onNodeWithTag("launch_cwd").performTextInput("/repo")
        compose.onNodeWithTag("new-task-prompt").performScrollTo().performTextInput("Fix the race")
        compose.onNodeWithText(str(R.string.new_task_start)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            core.push("h1", listOf(core.terminalsOf.getValue("h1").single().copy(agentStatus = AgentStatus.Idle)))
        }
        compose.waitUntil { core.connectionsOf.getValue("h1").typed.isNotEmpty() }
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertExists()
        assertEquals(listOf("t101" to "Fix the race"), core.connectionsOf.getValue("h1").typed)
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).assertExists()
        assertEquals(listOf("h1"), core.connectAttempts)
    }

    @Test fun savedLaunchUsesSelectedHostAndRemembersItWhenTheBoardIsReopened() {
        val homeRequest = LaunchRequest("--home", "/home/repo", null, null, AgentPreset.CLAUDE_CODE)
        val serverRequest = LaunchRequest("--server", "/server/repo", "s2", "tab2", AgentPreset.CODEX)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.invalid", 22u, "me"))
            addHost("server", Endpoint.Ssh("server.invalid", 22u, "me"))
            terminalsOf["h1"] = emptyList()
            terminalsOf["h2"] = emptyList()
            savedLaunchesOf["h1"] = mutableListOf(SavedLaunch("L1", "Home task", homeRequest))
            savedLaunchesOf["h2"] = mutableListOf(SavedLaunch("L1", "Server task", serverRequest))
        }
        open(core)
        compose.onNodeWithTag("creation-host").performClick()
        compose.onNodeWithTag("creation-host:h2").performClick()
        compose.onNodeWithTag("saved_run:L1").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertExists()
        assertEquals(emptyList<LaunchRequest>(), core.connectionsOf.getValue("h1").launched)
        assertEquals(listOf(serverRequest), core.connectionsOf.getValue("h2").launched)
        // Last-used creation Host survives rebuilding the board, even if it now needs user action.
        compose.runOnIdle {
            core.failFor["h2"] = CoreException.HostKeyUnknown("changed-key")
            model.openAgents()
        }
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithTag("creation-host").assertTextContains("server")
        compose.onNodeWithText(str(R.string.new_task_title)).assertIsNotEnabled().performClick()
        compose.onNodeWithTag("new-task-dialog").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.creation_host_disconnected, "server")).assertExists()
        compose.onNodeWithTag("creation-host").performClick()
        compose.onNodeWithTag("creation-host:h1").performClick()
        compose.onNodeWithTag("saved_run:L1").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertExists()
        assertEquals(listOf(homeRequest), core.connectionsOf.getValue("h1").launched)
    }

    @Test fun firstConnectedHostIsDefaultAndDisconnectedHostsRemainSelectableWithoutLaunching() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.invalid", 22u, "me"))
            addHost("server", Endpoint.Ssh("server.invalid", 22u, "me"))
            terminalsOf["h1"] = emptyList()
            terminalsOf["h2"] = emptyList()
            failFor["h1"] = CoreException.HostKeyUnknown("unknown-key")
        }
        open(core)
        compose.onNodeWithTag("creation-host").assertTextContains("server")
        compose.onNodeWithText(str(R.string.new_task_title)).assertIsEnabled()
        compose.onNodeWithTag("creation-host").performClick()
        compose.onNodeWithTag("creation-host:h1").performClick()
        compose.onNodeWithText(str(R.string.new_task_title)).assertIsNotEnabled().performClick()
        compose.onNodeWithTag("new-task-dialog").assertDoesNotExist()
        compose.onNodeWithTag("creation-host").performClick()
        compose.onNodeWithTag("creation-host:h2").performClick()
        compose.onNodeWithText(str(R.string.new_task_title)).performClick()
        compose.onNodeWithTag("launch_cwd").performTextInput("/server/repo")
        compose.onNodeWithTag("new-task-prompt").performScrollTo().performTextInput("Server task")
        compose.onNodeWithText(str(R.string.new_task_start)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LaunchRequest("", "/server/repo", null, null, AgentPreset.CLAUDE_CODE)),
            core.connectionsOf.getValue("h2").launched)
        assertEquals(listOf("h1", "h2"), core.connectAttempts)
    }

}
