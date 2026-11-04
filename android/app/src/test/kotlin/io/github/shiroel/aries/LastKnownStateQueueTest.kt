package io.github.shiroel.aries

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.LastKnownState
import io.github.shiroel.aries.ui.FakeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pause real workers at the core boundary, after the Host has already been read. */
class GatedRefreshApplication : Application(), HasCore {
    val fake = FakeCore()
    val reads = Channel<LastKnownState>(Channel.UNLIMITED)
    val release = Channel<Unit>(Channel.UNLIMITED)
    override val core = object : AriesCoreInterface by fake {
        override suspend fun snapshot(hostId: String): LastKnownState {
            val state = fake.snapshot(hostId)
            reads.send(state)
            release.receive()
            return state
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = GatedRefreshApplication::class)
class LastKnownStateQueueTest {
    // Regression: KEEP loses requests during SSH; APPEND_OR_REPLACE alone queues every burst item.
    @Test fun refreshDuringAnActivePullQueuesOneFollowUpAndCoalescesTheBurst() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<GatedRefreshApplication>()
        app.fake.ensureKey()
        val host = app.fake.addHost("dev", Endpoint.Ssh("box", 22u, "me"))
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val manager = WorkManager.getInstance(app)
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        try {
            // Both widgets (all Hosts) and authenticated pushes (one Host) share these rules.
            for (hostId in listOf(null, host.id)) {
                val name = "last-known-state:refresh:${hostId ?: "all"}"
                LastKnownStates.requestRefresh(app, hostId)
                val first = manager.getWorkInfosForUniqueWork(name).get().single()
                driver.setAllConstraintsMet(first.id)
                assertEquals(host.id, withTimeout(5_000) { app.reads.receive() }.hostId)
                assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfoById(first.id).get()!!.state)

                List(4) { async(Dispatchers.Default) { LastKnownStates.requestRefresh(app, hostId) } }.awaitAll()
                val queued = manager.getWorkInfosForUniqueWork(name).get()
                assertEquals(2, queued.size)
                val next = queued.single { it.id != first.id }
                driver.setAllConstraintsMet(next.id)
                app.release.send(Unit)
                assertEquals(host.id, withTimeout(5_000) { app.reads.receive() }.hostId)
                app.release.send(Unit)
                withTimeout(5_000) {
                    while (manager.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) delay(10)
                }
                assertTrue(manager.getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.SUCCEEDED })
            }
            assertEquals(listOf(host.id, host.id, host.id, host.id), app.fake.snapshotsRequested)
        } finally {
            manager.cancelAllWork().result.get()
            app.reads.close()
            app.release.close()
        }
    }
}
