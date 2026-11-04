package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: the line to a Host drops and the app restores what was on screen, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class ReconnectTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val build = TerminalInfo("t2", "build", "", listOf("make"), "/repo", "live", null, false, 0uL)
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)

    private lateinit var model: HostsModel
    private lateinit var screenLifecycle: LifecycleRegistry

    private fun frame(text: String) = TerminalUpdate.Screen(
        ScreenFrame(
            80u, 2u, true,
            listOf(
                ScreenLine(
                    0u,
                    listOf(CellRun(0u, text.length.toUShort(), text, false, null, null, false, false, false, false, false, false)),
                ),
            ),
            null, null,
        ),
    )

    private fun start(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            val owner = remember {
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                }
            }
            screenLifecycle = owner.lifecycle
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { AriesApp(model) }
            }
        }
    }

    /** Opens Host "home" and its Terminal "build", which shows "before". */
    private fun openBuild(core: FakeCore): FakeTerminalView {
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()
        val view = core.connection!!.views.single()
        view.push(frame("before"))
        compose.waitForIdle()
        return view
    }

    private fun screenShows(text: String) =
        compose.onNode(hasContentDescription(text, substring = true)).assertExists()

    @Test
    fun aDroppedLineShowsReconnectingThenReattachesTheOpenTerminalResumed() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val first = openBuild(core)
        val dropped = core.connection!!

        core.failWith = CoreException.Unreachable("network is unreachable")
        first.end()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.reconnecting)).assertExists()
        screenShows("before")
        assertTrue("the dead line is let go", dropped.disconnected)

        // The network is back.
        core.failWith = null
        model.networkChanged()
        compose.waitForIdle()

        val again = core.connection!!
        assertNotSame(dropped, again)
        val view = again.views.single()
        assertEquals("t2", view.address)
        assertTrue("still an observer", view.options.observe)
        assertTrue("re-attached as resumed", view.options.resumed)
        view.push(frame("after"))
        compose.waitForIdle()
        screenShows("after")
        compose.onNodeWithText(str(R.string.reconnecting)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.terminal_connection_closed)).assertDoesNotExist()
    }

    @Test
    fun aNetworkChangeReplacesTheLineAndTheListComesFromTheNewSnapshot() {
        val core = FakeCore().apply { terminals = listOf(build) }
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        val dropped = core.connection!!

        // While the phone switched networks, an Agent started on the Host.
        core.terminals = listOf(build, agent)
        model.networkChanged()
        compose.waitForIdle()

        assertTrue(dropped.disconnected)
        assertNotSame(dropped, core.connection)
        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithText(str(R.string.structure_connection_closed)).assertDoesNotExist()
    }

    @Test
    fun comingBackToTheAppReconnectsALineThatDiedInTheBackground() {
        val core = FakeCore().apply { terminals = listOf(build) }
        openBuild(core)
        val slept = core.connection!!
        slept.dead = true

        model.foregrounded()
        compose.waitForIdle()

        assertTrue(slept.disconnected)
        val view = core.connection!!.views.single()
        assertTrue(view.options.resumed)
    }

    @Test
    fun aLiveLineIsKeptWhenTheAppComesBack() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val view = openBuild(core)
        val conn = core.connection!!

        model.foregrounded()
        compose.waitForIdle()

        assertFalse(conn.disconnected)
        assertFalse(view.detached)
        screenShows("before")
    }

    @Test
    fun backgroundingDetachesAndStopsReconnectUntilTheScreenResumes() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val first = openBuild(core)
        val old = core.connection!!
        compose.runOnIdle {
            (model.screen as Screen.Terminal).viewer.draft = "unfinished command"
            screenLifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.waitForIdle()
        assertTrue(first.detached)
        assertTrue(old.disconnected)
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        assertTrue(core.connection === old)
        screenShows("before")

        compose.runOnIdle { screenLifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertNotSame(old, core.connection)
        assertTrue(core.connection!!.views.single().options.resumed)
        assertEquals("unfinished command", (model.screen as Screen.Terminal).viewer.draft)
    }

    @Test
    fun backgroundingChatPausesItsWatchAndKeepsTheDraftAndCursorOnResume() {
        val core = FakeCore().apply { terminals = listOf(agent) }
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        compose.waitForIdle()
        val chat = (model.screen as Screen.Chat).chat
        core.pushChat(io.github.shiroel.aries.core.ChatSnapshot(emptyList(), io.github.shiroel.aries.core.ChatStatus.LIVE, "cursor-1"))
        compose.waitForIdle()
        compose.runOnIdle {
            chat.draft = "unfinished question"
            screenLifecycle.currentState = Lifecycle.State.CREATED
            model.networkChanged()
        }
        compose.waitForIdle()
        assertTrue(core.chatPaused)
        assertEquals(0, core.chatReconnects)
        compose.runOnIdle { screenLifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertFalse(core.chatPaused)
        assertTrue(chat === (model.screen as Screen.Chat).chat)
        assertEquals("unfinished question", chat.draft)
        assertEquals("cursor-1", chat.snapshot?.cursor)
    }

    @Test
    fun aReconnectThatNeedsTheUserStopsAndSaysWhy() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val first = openBuild(core)

        core.failWith = CoreException.HostKeyChanged("SHA256:old", "SHA256:new")
        first.end()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.terminal_connection_closed)).assertExists()
        compose.onNodeWithText(str(R.string.host_key_changed_title)).assertExists()
        compose.onNodeWithText(str(R.string.reconnecting)).assertDoesNotExist()
    }

    @Test
    fun theAgentListComesBackFromTheNewSnapshotAndItsTerminalReattaches() {
        val core = FakeCore()
        val home = core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        core.terminalsOf[home.id] = listOf(agent)
        start(core)
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        val dropped = core.connectionsOf.getValue(home.id)
        assertEquals("t1", dropped.views.single().address)

        core.terminalsOf[home.id] = listOf(agent.copy(agentStatus = AgentStatus.Done, turn = 1uL))
        core.failWith = CoreException.Unreachable("no route")
        model.networkChanged()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.reconnecting)).assertExists()

        core.failWith = null
        model.networkChanged()
        compose.waitForIdle()
        val again = core.connectionsOf.getValue(home.id)
        assertNotSame(dropped, again)
        assertTrue(again.views.single().options.resumed)

        compose.runOnIdle { model.closeTerminal() }
        compose.waitForIdle()
        // The resumed, visible Terminal acknowledged the turn from the new snapshot.
        compose.onNodeWithContentDescription(str(R.string.agent_status_idle), useUnmergedTree = true).assertExists()
        assertEquals(listOf("h1 t1 seen=0", "h1 t1 seen=1"), core.labelWrites)
    }
}
