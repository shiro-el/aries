package io.github.shiroel.aries

import android.content.Context
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import io.github.shiroel.aries.ui.HostsModel
import io.github.shiroel.aries.ui.Screen
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [33])
class AgentTileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core get() = (context as FakeCoreApp).core

    @Test fun tileShowsWaitingCountAndConservativeAgeWithoutConnecting() = kotlinx.coroutines.runBlocking {
        val host = core.addHost("dev", Endpoint.Ssh("box", 22u, "me"))
        val other = core.addHost("other", Endpoint.Ssh("other", 22u, "me"))
        val blocked = TerminalInfo("box/t7", "agent", "", emptyList(), null, "live",
            AgentStatus.Blocked(BlockedReason.QUESTION, "which?"))
        core.storedStates[host.id] = LastKnownState(host.id, listOf(blocked), 120_000uL)
        core.storedStates[other.id] = LastKnownState(other.id, emptyList(), 60_000uL)
        val service = Robolectric.buildService(AgentTileService::class.java).create().get()
        service.refreshTile(nowMs = 240_000L)
        assertEquals("Aries · Needs you 1", service.qsTile.label.toString())
        assertEquals("3 min ago", service.qsTile.subtitle.toString())
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        core.lastKnownStatesFailure = CoreException.Storage("unreadable state")
        service.refreshTile(nowMs = 300_000L)
        assertEquals("Aries · Waiting 1", service.qsTile.label.toString())
        assertEquals("3 min ago", service.qsTile.subtitle.toString())
        core.lastKnownStatesFailure = null
        assertEquals(PushTarget(host.id, "box/t7"), AgentSurfaces.attention(core))
        assertNull(core.connection)
        assertTrue(core.snapshotsRequested.isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun tappingOpensTheRecentWaitingAgentAndAnEmptyTileOpensAgents() = runTest {
        val host = core.addHost("dev", Endpoint.Ssh("box", 22u, "me"))
        val old = TerminalInfo("t1", "old", "", emptyList(), null, "live", AgentStatus.Error(null), statusMs = 10uL)
        val recent = old.copy(address = "box/t7", name = "recent", statusMs = 20uL)
        core.terminals = listOf(old, recent)
        core.storedStates[host.id] = LastKnownState(host.id, core.terminals, 1uL)
        val service = Robolectric.buildService(AgentTileService::class.java).create().get()
        service.onClick()
        assertEquals(AgentSurfaces.OPEN_ATTENTION, shadowOf(context as android.app.Application).nextStartedActivity.action)
        val model = HostsModel(core, backgroundScope)
        model.openTile()
        runCurrent()
        assertEquals("box/t7", (model.screen as Screen.Terminal).viewer.terminal.address)
        core.storedStates[host.id] = LastKnownState(host.id, emptyList(), 1uL)
        model.openTile()
        runCurrent()
        assertTrue(model.screen is Screen.Agents)
        service.refreshTile()
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }
}
