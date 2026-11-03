package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: tapping an Agent notification opens that Agent's Terminal, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class NotificationTapTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun agent(address: String, name: String, status: AgentStatus?, state: String = "live") = TerminalInfo(
        address, name, "", listOf(name), "/repo/$name", state, status, false, 0uL,
        false, address.substringBeforeLast('/', "").ifEmpty { null },
    )

    /** Two Hosts that both have a `t1`; home's Agent on its machine `box` is Blocked. */
    private fun twoHosts() = FakeCore().apply {
        addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        addHost("server", Endpoint.Ssh("10.0.0.3", 22u, "me"))
        terminalsOf["h1"] = listOf(
            agent("t1", "claude", AgentStatus.Working),
            agent("box/t1", "codex", AgentStatus.Blocked(BlockedReason.PERMISSION, "Allow Bash?")),
        )
        terminalsOf["h2"] = listOf(agent("t1", "pi", AgentStatus.Error(null)))
    }

    private lateinit var model: HostsModel

    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.waitForIdle()
    }

    private fun tap(hostId: String, address: String) {
        compose.runOnIdle { model.openPushedAgent(hostId, address) }
        compose.waitForIdle()
    }

    @Test
    fun opensTheExactHostAndMachineTerminalAndBackShowsTheAgent() {
        val core = twoHosts()
        show(core)
        tap("h1", "box/t1")

        val view = core.connectionsOf.getValue("h1").views.single()
        assertEquals("box/t1", view.address)
        assertTrue("attaches as an observer", view.options.observe)
        assertTrue(core.connectionsOf["h2"]?.views.orEmpty().isEmpty())
        assertTrue(model.screen is Screen.Terminal)

        // Back leaves the Terminal for that Agent's detail in the Agents list.
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(view.detached)
        compose.onNodeWithText(str(R.string.agent_detail_host, "home")).assertExists()
        compose.onNodeWithText(str(R.string.agent_detail_address, "box/t1")).assertExists()
    }

    @Test
    fun aTapWhileAnotherTerminalIsOpenSwitchesToTheTappedOne() {
        val core = twoHosts()
        show(core)
        tap("h1", "t1")
        val first = core.connectionsOf.getValue("h1").views.single()

        // Warm start: the app is showing home's t1 when server's t1 notifies.
        tap("h2", "t1")
        assertTrue(first.detached)
        val view = core.connectionsOf.getValue("h2").views.single()
        assertEquals("t1", view.address)
        assertTrue(model.screen is Screen.Terminal)
    }

    @Test
    fun aTerminalThatIsGoneSaysWhyAndShowsTheAgentsList() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent("t1", "claude", AgentStatus.Working), agent("box/t1", "codex", null, state = "exited"))
        }
        show(core)
        tap("h1", "box/t1")

        assertTrue(core.connectionsOf.getValue("h1").views.isEmpty())
        compose.onNodeWithText(str(R.string.push_terminal_gone_title)).assertExists()
        compose.onNodeWithText(str(R.string.push_terminal_gone_body, "box/t1", "home")).assertExists()
        compose.onNodeWithText(str(R.string.ok_button)).performClick()
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Agents)
        compose.onNodeWithText("claude").assertExists()
    }

    @Test
    fun aNotificationFromARemovedHostSaysWhyAndShowsTheAgentsList() {
        val core = twoHosts()
        show(core)
        tap("gone", "t1")

        compose.onNodeWithText(str(R.string.push_terminal_gone_title)).assertExists()
        compose.onNodeWithText(str(R.string.push_host_gone_body, "t1")).assertExists()
        assertTrue(model.screen is Screen.Agents)
        assertTrue(core.connectionsOf.values.all { it.views.isEmpty() })
    }
}
