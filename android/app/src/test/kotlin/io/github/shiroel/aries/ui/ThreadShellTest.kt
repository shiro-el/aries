package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ThreadShellTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val generation = mutableIntStateOf(0)
    private lateinit var model: HostsModel
    private val terminal = TerminalInfo("box/t1", "Review reconnect", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private val core = FakeCore().apply {
        addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
        ensureKey()
        terminals = listOf(terminal)
    }
    private fun show(savedAgent: LastLocation? = null) {
        val preferences = context.getSharedPreferences("thread-shell-location", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        savedAgent?.let { LastLocationStore(preferences).saveAgent(it) }
        val appearance = ThemeSettings(context.getSharedPreferences("thread-shell-appearance", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            key(generation.intValue) {
                val scope = rememberCoroutineScope()
                model = remember { HostsModel(core, scope, locationStore = LastLocationStore(preferences)) }
                LaunchedEffect(model) { model.start(layout = AppLayout.THREAD) }
                AriesApp(model, appearance)
            }
        }
        compose.waitForIdle()
    }
    private fun restart() {
        compose.runOnIdle { generation.intValue++ }
        compose.waitForIdle()
    }

    // D62: a stopped list watch must expose recovery on home and in the drawer, rather than claim the Host is empty.
    @Test fun failedHomeWatchCanBeRetriedFromHomeAndDrawer() {
        core.terminals = emptyList()
        core.watchFailures["h1"] = CoreException.Protocol("List subscription refused")
        show()
        compose.onNodeWithText(context.getString(R.string.thread_home_empty_title)).assertDoesNotExist()
        compose.onNodeWithText("List subscription refused", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNode(hasText("List subscription refused", substring = true) and
            hasAnyAncestor(hasTestTag("thread-drawer"))).assertIsDisplayed()
        val firstList = CompletableDeferred<Unit>()
        compose.runOnIdle {
            core.watchFailures.clear()
            core.firstTerminalListHolds["h1"] = firstList
        }
        compose.onNode(hasText(context.getString(R.string.connect_retry_button)) and
            hasAnyAncestor(hasTestTag("thread-drawer"))).performClick()
        compose.onNodeWithText(context.getString(R.string.thread_home_empty_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue((model.screen as Screen.Agents).board.loadingHosts.isNotEmpty())
            firstList.complete(Unit)
        }
        compose.runOnIdle {
            val board = (model.screen as Screen.Agents).board
            assertTrue(board.watchErrors.isEmpty())
            assertTrue(board.isEmpty)
        }
        compose.onNodeWithTag("thread-hosts").performClick()
        compose.onNodeWithTag("console-back").performClick()
        compose.runOnIdle { core.failTerminalWatch("h1", CoreException.Protocol("List stream interrupted")) }
        compose.onNodeWithText("List stream interrupted", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connect_retry_button)).performClick()
        compose.onNodeWithText(context.getString(R.string.thread_home_empty_title)).assertIsDisplayed()
    }

    // D63: a rebuilt home must send its retained inline draft on the replacement link, not the closed owner.
    @Test fun inlineDraftUsesTheReplacementHomeLinkAfterHosts() {
        core.terminals = emptyList()
        show()
        compose.onNodeWithTag("new-task-prompt").performTextInput("Send after returning")
        val original = model.task!!
        compose.runOnIdle {
            original.form.cwd = "/work/payments"
            original.form.preset = AgentPreset.CODEX
            model.back()
        }
        compose.onNodeWithTag("console-back").performClick()
        compose.onNodeWithTag("new-task-prompt").assertTextContains("Send after returning")
        compose.onNodeWithTag("thread-task-start").performClick()
        compose.runOnIdle {
            assertEquals(listOf(LaunchRequest("", "/work/payments", null, null, AgentPreset.CODEX)), core.connection!!.launched)
            core.push(core.terminals.map { it.copy(agentStatus = AgentStatus.Idle) })
        }
        compose.waitUntil { core.connection!!.typed.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("t101" to "Send after returning"), core.connection!!.typed) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_input_label)).assertExists()
    }

    // D64: a Host selected from the inline composer must return a visible editable draft after connecting.
    @Test fun changingInlineHostPresentsTheDraftAfterConnecting() {
        core.terminals = emptyList()
        core.addHost("Second", Endpoint.Ssh("second.test", 22u, "me"))
        core.failFor["h2"] = CoreException.NoKey()
        show()
        compose.onNodeWithTag("new-task-prompt").performTextInput("Continue on the other Host")
        compose.runOnIdle { model.task!!.form.cwd = "/old-host-only" }
        compose.onNode(hasText("Development") and hasClickAction()).performClick()
        compose.runOnIdle { core.failFor.clear() }
        compose.onNodeWithText("Second").performClick()
        compose.onNodeWithTag("new-task-prompt").assertIsDisplayed().assertTextContains("Continue on the other Host")
        compose.runOnIdle {
            assertEquals("h2", model.task!!.link.host.id)
            assertEquals("", model.task!!.form.cwd)
            assertFalse(model.task!!.canStart)
            assertTrue(core.connection!!.launched.isEmpty())
        }
    }

    // D65: searching the project displayed for an unplaced Agent must keep that conversation discoverable.
    @Test fun drawerSearchFindsTheDisplayedCwdProject() {
        core.terminals = listOf(terminal.copy(cwd = "/work/payments"), terminal.copy(address = "box/t2", cwd = "/work/other"))
        show()
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("thread-drawer"))).performTextInput("payments")
        compose.onNodeWithTag("thread-agent:h1:box/t1").assertIsDisplayed()
        compose.onNodeWithTag("thread-agent:h1:box/t2").assertDoesNotExist()
    }
    // Regression: visiting Host management after Chat must not erase Thread's last Agent or restore Terminal instead.
    @Test fun startupRestoresTheLastAgentAsChatAfterVisitingHosts() {
        show()
        compose.runOnIdle { model.openPushedAgent("h1", terminal.address, openChat = true) }
        compose.waitForIdle()
        compose.runOnIdle { model.back() }
        compose.waitForIdle()
        restart()
        compose.runOnIdle {
            assertEquals(terminal.address, (model.screen as Screen.Chat).chat.terminal.address)
            assertEquals(AgentView.TERMINAL, model.agentView)
        }
    }
    // Regression: a removed Agent, including a still-live plain Terminal at that address, must leave the empty Thread home.
    @Test fun startupDoesNotOpenAGoneAgent() {
        show()
        compose.runOnIdle { model.openPushedAgent("h1", terminal.address, openChat = true) }
        compose.waitForIdle()
        for (remaining in listOf(emptyList(), listOf(terminal.copy(agentStatus = null)))) {
            core.terminals = remaining
            restart()
            compose.runOnIdle {
                assertTrue(model.screen is Screen.Agents)
                assertNull(model.prompt)
                assertTrue(core.connection!!.views.isEmpty())
            }
            compose.onNodeWithTag("thread-empty-home").assertIsDisplayed()
        }
    }
    // Regression: selecting a Host that needs connecting must keep the prompt and require a new folder before launch.
    @Test fun changingHostReconnectsWithoutReusingTheOldProjectOrLosingThePrompt() {
        core.addHost("Second", Endpoint.Ssh("second.test", 22u, "me"))
        show()
        compose.runOnIdle { model.openHost(model.hosts.first()) }
        compose.waitForIdle()
        compose.runOnIdle { model.beginThreadTask(model.hosts.first()) }
        compose.onNodeWithTag("new-task-prompt").performTextInput("Keep this instruction")
        compose.onNode(hasText("Development") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Second").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("h2", model.task!!.link.host.id)
            assertEquals("Keep this instruction", model.task!!.prompt)
            assertEquals("", model.task!!.form.cwd)
            assertFalse(model.task!!.canStart)
            assertTrue(core.connectionsOf.values.all { it.launched.isEmpty() })
        }
    }

    // Regression D35: the Calls waiting-Agent link in Thread must reach a conversation
    // instead of navigating to the layout's empty Agent home.
    @Test fun callInboxWaitingLinkOpensTheWaitingConversation() {
        core.terminals = listOf(terminal, terminal.copy(address = "box/t2", title = "Needs review",
            agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, "Pick a scope")))
        show()
        compose.waitUntil { model.drawerAgents.any { it.terminal.address == "box/t2" } }
        compose.runOnIdle { model.openCallInbox() }
        compose.onNodeWithTag("call-inbox-waiting-agents").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("box/t2", (model.screen as? Screen.Chat)?.chat?.terminal?.address) }
    }

    // D55: the first fresh list must not replace an inline draft typed during cold startup.
    @Test fun typingAtHomeCancelsDelayedStartupRestoration() {
        val firstList = CompletableDeferred<Unit>()
        core.firstTerminalListHolds["h1"] = firstList
        show(LastLocation("h1", terminal = terminal.address))
        compose.onNodeWithTag("new-task-prompt").performTextInput("Keep the new conversation")
        compose.runOnIdle { firstList.complete(Unit) }
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Agents)
            assertEquals("Keep the new conversation", model.task!!.input.text)
            assertTrue(core.chatsOpened.isEmpty())
        }
    }

    // D55: opening the drawer is explicit navigation, even before an Agent has been selected.
    @Test fun openingTheDrawerCancelsDelayedStartupRestoration() {
        val firstList = CompletableDeferred<Unit>()
        core.firstTerminalListHolds["h1"] = firstList
        show(LastLocation("h1", terminal = terminal.address))
        compose.onNodeWithTag("console-menu").performClick()
        compose.runOnIdle { firstList.complete(Unit) }
        compose.onNodeWithTag("thread-drawer").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Agents)
            assertTrue(core.chatsOpened.isEmpty())
        }
    }

    // D55: returning through Hosts is warm navigation and must not restore the saved Chat again.
    @Test fun returningFromHostsKeepsTheBoardWithoutRestoringTheLastChat() {
        show()
        val home = model.screen
        compose.runOnIdle { model.openPushedAgent("h1", terminal.address, openChat = true) }
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNodeWithTag("thread-hosts").performClick()
        compose.runOnIdle { assertEquals(Screen.Hosts, model.screen) }
        compose.onNodeWithTag("console-back").performClick()
        compose.runOnIdle {
            assertSame(home, model.screen)
            assertEquals(listOf("h1"), core.connectAttempts)
            assertEquals(listOf("h1" to terminal.address), core.chatsOpened)
        }
    }

}
