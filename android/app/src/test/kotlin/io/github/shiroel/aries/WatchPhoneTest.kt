package io.github.shiroel.aries

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.ui.FakeCore
import io.github.shiroel.aries.weardata.OpenWatchAgent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import kotlinx.coroutines.runBlocking
import io.github.shiroel.aries.weardata.WatchSummary

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [33])
class WatchPhoneTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun agent(address: String, status: AgentStatus, parent: String? = null, pinned: Boolean = false) = TerminalInfo(
        address, address, "", listOf("agent"), "/repo", "live", status, false, 0u, pinned, null, parent,
        statusMs = if (status is AgentStatus.Error) 180_000u else 60_000u,
    )

    @Test fun phoneUsesSharedFamilyPriorityAndPinsKeepsOnlyThreeAndDoesNotRefreshAge() {
        val core = FakeCore()
        val a = core.addHost("one", Endpoint.Ssh("one", 22u, "me"))
        val b = core.addHost("two", Endpoint.Ssh("two", 22u, "me"))
        val states = listOf(
            LastKnownState(a.id, listOf(
                agent("t1", AgentStatus.Working),
                agent("t2", AgentStatus.Blocked(null, null), parent = "t1"),
                agent("t3", AgentStatus.Idle, pinned = true),
                agent("t4", AgentStatus.Done),
            ), 60_000u),
            LastKnownState(b.id, listOf(agent("remote/t7", AgentStatus.Error("oops"))), 180_000u),
        )
        val summary = WatchPhone.summary(core.hosts(), states)
        assertEquals(1, summary.waiting)
        assertEquals(60_000L, summary.fetchedAtMs)
        assertEquals(listOf("t3", "remote/t7", "t1"), summary.agents.map { it.terminal })
        assertEquals(listOf(a.id, b.id, a.id), summary.agents.map { it.hostId })
        assertEquals(0L, WatchPhone.summary(emptyList(), states).fetchedAtMs)
    }

    @Test fun watchOpenLinkReusesNotificationRoutingAndPreservesWholeAddress() {
        val command = OpenWatchAgent("h1", "remote/t7")
        val intent = command.remoteIntent(context.packageName)
        assertEquals(PushTarget("h1", "remote/t7"), PushTarget.from(intent))
        assertNull(PushTarget.from(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("aries-watch://wrong"))))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(PushTarget.from(intent))
    }

    @Test fun messageClientAcknowledgesKnownAgentsAndRejectsDeletedOrMalformedTargets() = runBlocking<Unit> {
        val core = (context as FakeCoreApp).core
        core.ensureKey()
        val host = core.addHost("one", Endpoint.Ssh("one", 22u, "me"))
        core.terminalsOf[host.id] = listOf(agent("remote/t7", AgentStatus.Working))
        core.snapshot(host.id)
        val controller = Robolectric.buildService(WatchPhoneService::class.java).create()
        val service = controller.get()
        val command = OpenWatchAgent(host.id, "remote/t7")
        assertEquals(command, OpenWatchAgent.fromBytes(service.onRequest("watch", WatchSummary.OPEN_PATH, command.toBytes())!!.result))
        assertTrue(service.onRequest("watch", WatchSummary.OPEN_PATH, OpenWatchAgent(host.id, "deleted").toBytes())!!.result.isEmpty())
        assertTrue(service.onRequest("watch", WatchSummary.OPEN_PATH, byteArrayOf(1))!!.result.isEmpty())
        assertNull(service.onRequest("watch", "/unknown", command.toBytes()))
        controller.destroy()
    }
}
