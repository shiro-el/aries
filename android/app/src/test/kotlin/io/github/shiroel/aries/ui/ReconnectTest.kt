package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.CursorPos
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
            CursorPos(0u, 0u), null,
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
    @Config(qualifiers = "h1200dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun connectingHasNoFailureActionsUntilTheHostNeedsUserRecovery() {
        val core = FakeCore().apply { failWith = CoreException.Unreachable("no route") }
        core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        fun screenshot(name: String) {
            val file = java.io.File("build/reports/connection-notice/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }

        compose.onNodeWithText(str(R.string.connect_retry_button)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.connect_settings_button)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.connect_failed_title)).assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithText(str(R.string.reconnecting)).assertDoesNotExist()
        screenshot("initial")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(str(R.string.reconnecting)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText(str(R.string.reconnecting)).assertExists()
        screenshot("delayed")

        core.failWith = CoreException.AuthRejected()
        compose.runOnIdle { model.networkChanged() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.connect_failed_title)).assertExists()
        compose.onNodeWithText(str(R.string.connect_retry_button)).assertExists()
        compose.onNodeWithText(str(R.string.connect_settings_button)).assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        screenshot("failed")

        core.failWith = null
        compose.onNodeWithText(str(R.string.connect_retry_button)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Browse)
        compose.onNodeWithText(str(R.string.connect_retry_button)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.reconnecting)).assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        screenshot("connected")

        // A later drop starts a fresh quiet period rather than retaining the old delayed label.
        core.failWith = CoreException.Unreachable("no route")
        compose.runOnIdle { model.networkChanged() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithText(str(R.string.reconnecting)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText(str(R.string.reconnecting)).assertExists()
        core.failWith = null
        compose.runOnIdle { model.networkChanged() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.reconnecting)).assertDoesNotExist()
    }

    @Test
    fun aFirstConnectionFailureRetriesAndStillOpensTheNotificationTarget() {
        val core = FakeCore().apply { terminals = listOf(agent) }
        val home = core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        core.failWith = CoreException.Unreachable("no route")
        start(core)
        compose.runOnIdle { model.openPushedAgent(home.id, agent.address) }
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Agents)
        core.failWith = null
        // No new navigation request: the retry must remember the original target.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("t1", (model.screen as Screen.Terminal).viewer.terminal.address)
        assertEquals("t1", core.connection!!.views.single().address)
    }

    @Test
    fun permanentFailuresStopWithoutInterruptingAnotherHostsAgent() {
        val core = FakeCore().apply { terminals = listOf(agent) }
        val home = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val errors = listOf(
            CoreException.AuthRejected(),
            CoreException.HostKeyChanged("old", "new"),
            CoreException.Rpc("permission_denied", "not allowed"),
            CoreException.Protocol("not a core protocol"),
            CoreException.KeyLocked(),
        )
        val failedHosts = errors.mapIndexed { i, error ->
            core.addHost("broken$i", Endpoint.Ssh("broken$i", 22u, "me")).also {
                core.failFor[it.id] = error
            }
        }
        start(core)
        compose.runOnIdle { model.openPushedAgent(home.id, agent.address) }
        compose.waitForIdle()
        assertTrue("another Host must not open a modal", model.prompt == null)
        compose.runOnIdle { model.networkChanged(); model.backgrounded(); model.foregrounded() }
        compose.mainClock.advanceTimeBy(35_000)
        compose.waitForIdle()
        failedHosts.forEach { host ->
            assertEquals("permanent failure for ${host.name} must wait for the user", 1,
                core.connectAttempts.count { it == host.id })
        }
        assertTrue(model.prompt == null)
        assertTrue("foreground must not bypass paused links with a separate snapshot connection",
            failedHosts.none { it.id in core.snapshotsRequested })
        assertEquals("t1", (model.screen as Screen.Terminal).viewer.terminal.address)
    }

    @Test
    fun aPermanentFirstBrowseFailureCanBeFixedAndRetriedInPlace() {
        val core = FakeCore().apply { terminals = listOf(build); failWith = CoreException.AuthRejected() }
        val home = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Connecting)
        assertEquals(1, core.connectAttempts.count { it == home.id })
        core.failWith = null
        compose.onNodeWithText(str(R.string.connect_retry_button)).performClick()
        compose.waitForIdle()
        assertEquals(home.id, (model.screen as Screen.Browse).browser.host.id)
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()
        assertEquals("t2", core.connection!!.views.single().address)
    }

    @Test
    fun aLockedChatStartKeepsItsCauseAndCanBeRetriedWithoutLeavingTheAgent() {
        val core = FakeCore().apply { terminals = listOf(agent); failChatWith = CoreException.KeyLocked() }
        val home = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        start(core)
        compose.runOnIdle { model.openPushedAgent(home.id, agent.address, openChat = true) }
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Chat)
        compose.runOnIdle { model.networkChanged(); model.backgrounded(); model.foregrounded() }
        compose.waitForIdle()
        assertEquals("unlock cancellation must not prompt repeatedly", 1, core.chatsOpened.size)
        core.failChatWith = null
        compose.onNodeWithText(str(R.string.connect_retry_button)).performClick()
        compose.waitForIdle()
        assertEquals(2, core.chatsOpened.size)
        core.pushChat(io.github.shiroel.aries.core.ChatSnapshot(emptyList(), io.github.shiroel.aries.core.ChatStatus.LIVE, null))
        compose.waitForIdle()
        assertEquals(io.github.shiroel.aries.core.ChatStatus.LIVE, (model.screen as Screen.Chat).chat.snapshot!!.status)
        compose.onNodeWithText(str(R.string.chat_status_no_beacon)).assertDoesNotExist()
    }

    @Test
    fun aDroppedLineShowsReconnectingThenReattachesTheOpenTerminalResumed() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val first = openBuild(core)
        val dropped = core.connection!!

        core.failWith = CoreException.Unreachable("network is unreachable")
        first.end()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_600)
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
    fun reconnectingKeepsStructureUntilTheFreshSnapshotReplacesIt() {
        val core = FakeCore().apply { terminals = listOf(build) }
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        start(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        val dropped = core.connection!!

        val browser = (model.screen as Screen.Browse).browser
        val cached = browser.structure
        core.failWith = CoreException.Unreachable("no route")
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        assertTrue(cached === browser.structure)
        compose.onNodeWithText("build").assertExists()

        // The old Terminal disappeared and a new Agent started while offline.
        core.terminals = listOf(agent)
        core.failWith = null
        compose.runOnIdle { model.foregrounded() }
        compose.waitForIdle()

        assertTrue(dropped.disconnected)
        assertNotSame(dropped, core.connection)
        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithText("build").assertDoesNotExist()
        assertEquals(listOf("t1"), browser.terminals.map { it.address })
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
    fun aShortBackgroundVisitKeepsTheLiveLineAndTerminal() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val view = openBuild(core)
        val conn = core.connection!!

        compose.runOnIdle { screenLifecycle.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(30_000)
        compose.runOnIdle {
            screenLifecycle.currentState = Lifecycle.State.RESUMED
            model.foregrounded()
        }
        compose.waitForIdle()

        // The old background deadline must also be cancelled after returning.
        compose.mainClock.advanceTimeBy(120_100)
        assertFalse(conn.disconnected)
        assertFalse(view.detached)
        screenShows("before")
    }

    @Test
    fun aLongBackgroundVisitReleasesTheLineAndResumesTheTerminal() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val first = openBuild(core)
        val old = core.connection!!
        compose.runOnIdle {
            (model.screen as Screen.Terminal).viewer.draft = "unfinished command"
            screenLifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.mainClock.advanceTimeBy(120_100)
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
    fun backgroundingChatReusesItsWatchBrieflyThenReleasesItAfterTheGracePeriod() {
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
        compose.mainClock.advanceTimeBy(30_000)
        compose.waitForIdle()
        assertFalse(core.chatPaused)
        compose.runOnIdle {
            screenLifecycle.currentState = Lifecycle.State.RESUMED
            model.foregrounded()
        }
        compose.waitForIdle()
        assertEquals(1, core.chatsOpened.size)
        assertEquals("cursor-1", chat.snapshot?.cursor)
        // Returning cancelled the first deadline; a second visit starts a fresh one.
        compose.runOnIdle { screenLifecycle.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(120_100)
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun foregroundRetriesImmediatelyAndResetsAnExhaustedBackoff() = kotlinx.coroutines.test.runTest {
        val core = FakeCore().apply { failWith = CoreException.Unreachable("no route") }
        val host = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val model = HostsModel(core, backgroundScope)
        model.connect(host)
        runCurrent()
        advanceTimeBy(50_000)
        runCurrent()
        val attempts = core.connectAttempts.size

        // Still offline on return: retry now, then restart from the shortest delay.
        model.foregrounded()
        runCurrent()
        assertEquals(attempts + 1, core.connectAttempts.size)
        core.failWith = null
        advanceTimeBy(500)
        runCurrent()
        assertTrue("foreground must reset the 30-second backoff to 500 ms", model.screen is Screen.Browse)
        model.back()
    }

    @Test
    fun aReconnectThatNeedsTheUserStopsAndSaysWhy() {
        val core = FakeCore().apply { terminals = listOf(build) }
        val first = openBuild(core)

        core.failWith = CoreException.HostKeyChanged("SHA256:old", "SHA256:new")
        first.end()
        compose.waitForIdle()

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
        compose.mainClock.advanceTimeBy(1_600)
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
