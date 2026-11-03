package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Console row behavior at the Android UI / core API seam. */
@RunWith(RobolectricTestRunner::class)
class ConsoleAgentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pinnedWorkComesBeforeWaitingRowsAndKeepsMessageLocationAndPinActions() {
        fun terminal(address: String, name: String, status: AgentStatus, pinned: Boolean = false) =
            TerminalInfo(address, name, "", listOf(name), "/repo/$name", "live", status, false, 0uL, pinned)
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("host", 22u, "me"))
            terminalsOf["h1"] = listOf(
                terminal("t1", "build", AgentStatus.Working, pinned = true),
                terminal("t2", "review", AgentStatus.Error("Please retry")),
                terminal("t3", "idle", AgentStatus.Idle),
            )
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun str(id: Int, vararg args: Any) = context.getString(id, *args)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesTheme(remember { ThemeSettings(context.getSharedPreferences("console-agents-test", android.content.Context.MODE_PRIVATE)) }) { AriesApp(model) }
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        val waiting = compose.onNodeWithText("review").fetchSemanticsNode().boundsInRoot.top
        val work = compose.onNodeWithText("build").fetchSemanticsNode().boundsInRoot.top
        assertTrue("herdr-app puts pinned Agents first", work < waiting)
        compose.onNodeWithText("Please retry").assertExists()
        compose.onNodeWithText("home:/repo/build").assertExists()
        compose.onNodeWithContentDescription(str(R.string.agent_pin_button_label, "review", "home")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h1 t2 pinned=true"), core.labelWrites)
        compose.onNodeWithContentDescription(str(R.string.agent_unpin_button_label, "review", "home")).assertExists()
        assertTrue("Pinning keeps the list open", core.connectionsOf.getValue("h1").views.isEmpty())
        compose.onNodeWithText("review").performClick()
        compose.waitForIdle()
        assertEquals("t2", core.connectionsOf.getValue("h1").views.single().address)
    }
}
