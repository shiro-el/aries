package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
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
    private fun show() {
        val preferences = context.getSharedPreferences("thread-shell-location", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
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

}
