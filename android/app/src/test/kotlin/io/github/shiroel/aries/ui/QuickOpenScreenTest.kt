package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class QuickOpenScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("result row") {
        it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) &&
            it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith(prefix)
    }
    private fun result(name: String) = compose.onNode(hasText(name) and hasTestTagPrefix("quick-result:"))
    private lateinit var model: HostsModel
    private fun start(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
    }

    @Test fun storedTabConnectsOnlyToItsHostAndRevealsItsSpaceAndTab() {
        val core = FakeCore()
        val host = core.addHost("Remote", Endpoint.Ssh("remote.test", 22u, "me"))
        core.addHost("Other", Endpoint.Ssh("other.test", 22u, "me"))
        val structure = HostStructure(listOf(SpaceInfo("s1", "Aries", "/repo", listOf(
            TabInfo("tab1", "Build", emptyList()), TabInfo("tab2", "Review", emptyList()),
        ))), emptyList(), null)
        core.structure = structure
        core.storedStates[host.id] = LastKnownState(host.id, emptyList(), (System.currentTimeMillis() - 180_000).toULong(), structure)
        start(core)
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement("rvw")
        compose.onNodeWithText("Review").assertExists()
        compose.onNodeWithText(str(R.string.quick_open_age, 3)).assertExists()
        assertNull(core.connection)
        compose.onNodeWithText("Review").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val browser = (model.screen as Screen.Browse).browser
            assertEquals(host.id, browser.host.id)
            assertEquals("s1", browser.openSpaceId)
            assertEquals("tab2", browser.tab?.id)
        }
        compose.onNodeWithTag("quick-open-query").assertDoesNotExist()
    }

    @Test fun commandModeUsesTheExistingRenameAndDestructiveConfirmationDialogs() {
        val terminal = TerminalInfo("t1", "writer", "", listOf("codex"), "/repo", "live", AgentStatus.Working)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(terminal)
            structure = HostStructure(listOf(SpaceInfo("s1", "Aries", "/repo", listOf(
                TabInfo("tab1", "Review", listOf(PaneInfo("p1", "t1", terminal))),
            ))), emptyList(), null)
        }
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("Aries").performClick()
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement(">" + str(R.string.layout_rename_tab_button))
        compose.onNodeWithTag("quick-command:RENAME_TAB").performClick()
        compose.onNodeWithTag("layout-name").performTextReplacement("Tests")
        compose.onNodeWithText(str(R.string.layout_save_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LayoutEdit.RenameTab("tab1", "Tests")), core.connection!!.edits)
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement(">" + str(R.string.layout_close_space_button))
        compose.onNodeWithTag("quick-command:CLOSE_SPACE").performClick()
        compose.onNodeWithText(str(R.string.layout_working_warning)).assertExists()
        compose.onNodeWithText(str(R.string.layout_hold_button)).assertExists()
        assertEquals(1, core.connection!!.edits.size)
    }


    @Test fun liveResultsReplaceStoredNamesAndFollowStatusAndStructureWatches() {
        val old = TerminalInfo("t1", "old-agent", "", listOf("codex"), "/repo", "live", AgentStatus.Working)
        val fresh = old.copy(name = "fresh-agent")
        val core = FakeCore().apply {
            val host = addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(fresh)
            storedStates[host.id] = LastKnownState(host.id, listOf(old), 1uL)
        }
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement("old-agent")
        result("old-agent").assertDoesNotExist()
        compose.onNodeWithTag("quick-open-query").performTextReplacement("fresh-agent")
        result("fresh-agent").assertExists()
        core.push(emptyList())
        result("fresh-agent").assertDoesNotExist()
        core.connection!!.push(HostStructure(listOf(SpaceInfo("s1", "renamed-space", "/repo", emptyList())), emptyList(), null))
        compose.onNodeWithTag("quick-open-query").performTextReplacement("renamed-space")
        result("renamed-space").assertExists()
    }

    @Test fun storedAgentUsesWholeHostIdentityAndSavedChatView() {
        val agent = TerminalInfo("t1", "writer", "", listOf("codex"), "/repo", "live", AgentStatus.Working)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            val work = addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
            terminalsOf["h1"] = listOf(agent.copy(name = "other-writer"))
            terminalsOf[work.id] = listOf(agent)
            storedStates[work.id] = LastKnownState(work.id, listOf(agent), 1uL)
            savedView = AgentView.CHAT
        }
        start(core)
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement("writer")
        result("writer").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val chat = (model.screen as Screen.Chat).chat
            assertEquals("h2", chat.host.id)
            assertEquals("t1", chat.terminal.address)
            assertEquals(setOf("h2"), core.connectionsOf.keys)
        }
        // Commands are reachable from the Agent header and retain the edit
        // dialog across unmounting/remounting Browse.
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement(">" + str(R.string.layout_kill_button))
        compose.onNodeWithTag("quick-command:END_TERMINAL").performClick()
        compose.onNodeWithText(str(R.string.layout_working_warning)).assertExists()
        compose.onNodeWithText(str(R.string.layout_hold_button)).assertExists()
        assertEquals(emptyList<String>(), core.connection!!.killed)
    }

    @Test fun staleStoredTargetExplainsItsAbsenceAfterConnecting() {
        val core = FakeCore().apply {
            val host = addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            val agent = TerminalInfo("t1", "gone", "", listOf("codex"), "/repo", "live", AgentStatus.Working)
            storedStates[host.id] = LastKnownState(host.id, listOf(agent), 1uL)
        }
        start(core)
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement("gone")
        result("gone").performClick()
        compose.onNodeWithText(str(R.string.quick_open_gone, "gone")).assertExists()
        compose.runOnIdle { assertTrue(model.screen is Screen.Browse) }
    }

    @Test fun unavailableCommandsAreExplainedAndSettingsNeedsNoHost() {
        val core = FakeCore()
        start(core)
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement(">" + str(R.string.layout_new_space_button))
        compose.onNodeWithTag("quick-command:NEW_SPACE").assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.quick_open_choose_host)).assertExists()
        compose.onNodeWithTag("quick-open-query").performTextReplacement(">" + str(R.string.settings_title))
        compose.onNodeWithTag("quick-command:SETTINGS").performClick()
        compose.onNodeWithText(str(R.string.theme_title)).assertExists()
    }


    @Test fun cancellingHostTrustDiscardsTheStoredDestination() {
        val core = FakeCore().apply {
            val host = addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            structure = HostStructure(listOf(SpaceInfo("s1", "Aries", "/repo", emptyList())), emptyList(), null)
            storedStates[host.id] = LastKnownState(host.id, emptyList(), 1uL, structure)
            requireTrust = "SHA256:example"
        }
        start(core)
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement("Aries")
        result("Aries").performClick()
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        core.requireTrust = null
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertNull((model.screen as Screen.Browse).browser.openSpaceId) }
    }


    @Test fun unavailableHerdrAppDisablesLayoutCommandsButAllowsAgentLaunch() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            herdrAppAvailable = false
        }
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithTag("quick-open-button").performClick()
        compose.onNodeWithTag("quick-open-query").performTextReplacement(">" + str(R.string.layout_new_space_button))
        compose.onNodeWithTag("quick-command:NEW_SPACE").assertIsNotEnabled()
        compose.onNode(hasText(str(R.string.layout_app_unavailable)) and hasAnyAncestor(hasTestTag("quick-command:NEW_SPACE")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("quick-open-query").performTextReplacement(">" + str(R.string.quick_open_run_agent))
        compose.onNodeWithTag("quick-command:RUN_AGENT").performClick()
        compose.onNodeWithText(str(R.string.launch_title)).assertExists()
    }

}
