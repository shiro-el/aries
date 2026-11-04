package io.github.shiroel.aries.ui

import androidx.compose.ui.test.onNodeWithContentDescription

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentPreset
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.core.SavedLaunch
import io.github.shiroel.aries.core.SpaceInfo
import io.github.shiroel.aries.core.TabInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: Agent presets and saved launch configs against a fake core API. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class PresetsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val desktop = HostStructure(
        spaces = listOf(SpaceInfo("s1", "aries", "/home/me/aries", listOf(TabInfo("tab1", "agents", emptyList())))),
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

    private fun openForm() {
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNode(hasText(str(R.string.launch_open_button)) and hasAnyAncestor(hasTestTag("host-create-sheet"))).performClick()
    }

    private fun click(id: Int) = compose.onNodeWithText(str(id)).performClick()

    private fun pick(preset: AgentPreset) = compose.onNodeWithTag("preset:" + preset.name).performScrollTo().performClick()

    @Test
    fun modelAndEffortAreSavedRestoredAndUsedByOneTapLaunch() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        openForm()
        pick(AgentPreset.CODEX)
        compose.onNodeWithTag("launch_model").performScrollTo().performTextInput("my-model")
        compose.onNodeWithTag("launch_effort").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.launch_effort_high)).performClick()
        compose.onNodeWithTag("launch_command").performScrollTo().performTextInput("--search")
        compose.onNodeWithTag("launch_name").performScrollTo().performTextInput("Review")
        compose.onNodeWithText(str(R.string.launch_save_button)).performScrollTo().performClick()
        compose.waitForIdle()
        val saved = core.savedLaunches.single()
        assertEquals(LaunchRequest("--search", null, null, null, AgentPreset.CODEX, "my-model", "high"), saved.request)

        // Switching Agents must not carry another Agent's model or effort.
        pick(AgentPreset.PI)
        compose.onNodeWithTag("launch_model").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("launch_effort").assertTextContains(str(R.string.launch_agent_default))
        compose.onNodeWithTag("saved_edit:${saved.id}").performScrollTo().performClick()
        compose.onNodeWithTag("launch_model").assertTextContains("my-model")
        compose.onNodeWithTag("launch_effort").assertTextContains(str(R.string.launch_effort_high))
        click(R.string.cancel_button)
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithTag("saved_run:${saved.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(saved.request), core.connection!!.launched)
    }

    @Test
    fun claudeAliasesPiThinkingAndOpenCodeModelUseTheirOwnControls() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        openForm()
        // Custom commands have no Agent options.
        compose.onNodeWithTag("launch_model").assertDoesNotExist()
        pick(AgentPreset.CLAUDE_CODE)
        compose.onNodeWithText(str(R.string.launch_model_choose)).performScrollTo().performClick()
        compose.onNodeWithText("sonnet").performClick()
        compose.onNodeWithTag("launch_effort").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.launch_effort_max)).performClick()
        click(R.string.launch_button)
        compose.waitForIdle()
        assertEquals(LaunchRequest("", null, null, null, AgentPreset.CLAUDE_CODE, "sonnet", "max"), core.connection!!.launched.last())

        openForm()
        pick(AgentPreset.PI)
        compose.onNodeWithTag("launch_model").performScrollTo().performTextInput("provider/model")
        compose.onNodeWithTag("launch_effort").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.launch_effort_off)).performClick()
        click(R.string.launch_button)
        compose.waitForIdle()
        assertEquals(LaunchRequest("", null, null, null, AgentPreset.PI, "provider/model", "off"), core.connection!!.launched.last())

        openForm()
        pick(AgentPreset.OPEN_CODE)
        compose.onNodeWithTag("launch_model").performScrollTo().performTextInput("provider/model")
        compose.onNodeWithTag("launch_effort").assertDoesNotExist()
        click(R.string.launch_button)
        compose.waitForIdle()
        assertEquals(LaunchRequest("", null, null, null, AgentPreset.OPEN_CODE, "provider/model", null), core.connection!!.launched.last())
    }

    @Test
    fun startsEachPresetWithNoCommandAndOnlyExtraArguments() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        for ((label, preset) in listOf(
            R.string.preset_claude_code to AgentPreset.CLAUDE_CODE,
            R.string.preset_codex to AgentPreset.CODEX,
            R.string.preset_opencode to AgentPreset.OPEN_CODE,
            R.string.preset_pi to AgentPreset.PI,
        )) {
            assertEquals(preset.label, label)
            openForm()
            compose.onNodeWithText(str(R.string.launch_button)).assertIsNotEnabled()
            pick(preset)
            // A preset needs no command; the field now takes extra arguments.
            compose.onNodeWithText(str(R.string.launch_button)).assertIsEnabled()
            compose.onNodeWithText(str(R.string.launch_command)).assertDoesNotExist()
            compose.onNodeWithText(str(R.string.launch_preset_args)).assertExists()
            click(R.string.launch_button)
            compose.waitForIdle()
            assertEquals(LaunchRequest("", null, null, null, preset), core.connection!!.launched.last())
        }

        openForm()
        pick(AgentPreset.CODEX)
        compose.onNodeWithTag("launch_command").performTextInput("-m gpt-5")
        click(R.string.launch_button)
        compose.waitForIdle()
        assertEquals(LaunchRequest("-m gpt-5", null, null, null, AgentPreset.CODEX), core.connection!!.launched.last())
    }

    @Test
    fun savesEditsAndDeletesALaunchThatRunsInOneTap() {
        val core = FakeCore().apply { structure = desktop }
        open(core)
        openForm()
        pick(AgentPreset.CLAUDE_CODE)
        compose.onNodeWithTag("launch_command").performTextInput("--model haiku")
        compose.onNodeWithTag("launch_space:s1").performClick()
        compose.onNodeWithText(str(R.string.launch_cwd_space_folder, "/home/me/aries")).performClick()
        compose.onNodeWithText(str(R.string.launch_save_button)).assertIsNotEnabled()
        compose.onNodeWithTag("launch_name").performTextInput("Claude haiku")
        click(R.string.launch_save_button)
        compose.waitForIdle()

        val request = LaunchRequest("--model haiku", "/home/me/aries", "s1", "tab1", AgentPreset.CLAUDE_CODE)
        val saved = core.savedLaunches.single()
        assertEquals(SavedLaunch(saved.id, "Claude haiku", request), saved)
        assertEquals(emptyList<LaunchRequest>(), core.connection!!.launched)
        click(R.string.cancel_button)

        // The creation sheet runs it with the saved options.
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithTag("saved_run:${saved.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(request), core.connection!!.launched)

        // Edit: same id, new name and arguments.
        openForm()
        compose.onNodeWithTag("saved_edit:${saved.id}").performClick()
        compose.onNodeWithTag("launch_command").performTextClearance()
        compose.onNodeWithTag("launch_command").performTextInput("--model opus")
        compose.onNodeWithTag("launch_name").performTextClearance()
        compose.onNodeWithTag("launch_name").performTextInput("Claude opus")
        click(R.string.launch_save_button)
        compose.waitForIdle()
        assertEquals(
            listOf(SavedLaunch(saved.id, "Claude opus", request.copy(command = "--model opus"))),
            core.savedLaunches,
        )

        compose.onNodeWithTag("saved_delete:${saved.id}").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<SavedLaunch>(), core.savedLaunches)
        click(R.string.cancel_button)
        compose.onNodeWithTag("saved_run:${saved.id}").assertDoesNotExist()
    }

    @Test
    fun aFailedOneTapLaunchOpensItsFormWithTheReason() {
        val saved = SavedLaunch("L9", "build", LaunchRequest("make", "/nope", null, null))
        val core = FakeCore().apply { structure = desktop; savedLaunches += saved }
        open(core)
        core.connection!!.launchFailWith = CoreException.Rpc("spawn_failed", "cannot start in /nope")
        compose.onNodeWithContentDescription(str(R.string.host_create_title)).performClick()
        compose.onNodeWithTag("saved_run:${saved.id}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.launch_spawn_failed, "cannot start in /nope")).assertExists()
        compose.onNodeWithTag("launch_command").assertTextContains("make")
    }
}
