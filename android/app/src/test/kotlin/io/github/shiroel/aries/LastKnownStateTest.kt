package io.github.shiroel.aries

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.KeyProtection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import io.github.shiroel.aries.ui.HostsModel
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.core.PushContent
import com.google.firebase.messaging.RemoteMessage
import org.robolectric.Robolectric
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android -> core seam: background refresh uses only keys that work without the user. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LastKnownStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core get() = (context as FakeCoreApp).core

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        core.ensureKey()
    }

    @Test fun periodicWorkIsUniqueAndRunsEveryFifteenMinutesWithNetwork() {
        LastKnownStates.schedule(context)
        LastKnownStates.schedule(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(LastKnownStates.PERIODIC_WORK).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(15 * 60_000L, info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test fun workerRefreshesPlainHostsButNeverOpensBiometricKeys() = runBlocking {
        val host = core.addHost("dev", Endpoint.Ssh("box", 22u, "me"))
        fun worker() = TestListenableWorkerBuilder<LastKnownStateWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(listOf(host.id), core.snapshotsRequested)
        assertNotNull(core.lastKnownState(host.id))
        core.snapshotsRequested.clear()
        core.setKeyProtection(KeyProtection.BIOMETRIC)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(core.snapshotsRequested.isEmpty())
    }
    @Test fun foregroundRefreshesDisconnectedHostsAndReusesLiveConnections() = runTest {
        val connected = core.addHost("live", Endpoint.Ssh("live", 22u, "me"))
        val offline = core.addHost("other", Endpoint.Ssh("other", 22u, "me"))
        val model = HostsModel(core, backgroundScope)
        model.connect(connected)
        runCurrent()
        val connection = core.connection!!
        model.foregrounded()
        runCurrent()
        assertEquals(listOf(offline.id), core.snapshotsRequested)
        assertTrue(connection.calls.contains("terminals"))
    }

    @Test fun anAuthenticatedPushQueuesOnlyItsHostAndRejectedPushDoesNot() {
        val host = core.addHost("dev", Endpoint.Ssh("box", 22u, "me"))
        val service = Robolectric.buildService(PushService::class.java).create().get()
        val message = RemoteMessage.Builder("phone").addData("v", "1").addData("e", "AQ==").build()
        val manager = WorkManager.getInstance(context)
        val workName = "last-known-state:refresh:${host.id}"
        service.onMessageReceived(message)
        assertTrue(manager.getWorkInfosForUniqueWork(workName).get().isEmpty())
        core.openedPush = PushNotice(host.id, host.name, PushContent.Test(1uL))
        service.onMessageReceived(message)
        service.onMessageReceived(message)
        assertEquals(2, manager.getWorkInfosForUniqueWork(workName).get().size)
    }

    @Test fun manualRefreshTargetsOneHostAndFailuresLeaveItsFetchTimeUntouched() = runBlocking {
        val first = core.addHost("first", Endpoint.Ssh("first", 22u, "me"))
        val other = core.addHost("other", Endpoint.Ssh("other", 22u, "me"))
        val old = core.snapshot(first.id)
        core.snapshotFailures += first.id
        core.snapshotsRequested.clear()
        val worker = TestListenableWorkerBuilder<LastKnownStateWorker>(context)
            .setInputData(androidx.work.workDataOf("host_id" to first.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf(first.id), core.snapshotsRequested)
        assertEquals(old, core.lastKnownState(first.id))
        assertNull(core.lastKnownState(other.id))
        TestListenableWorkerBuilder<LastKnownStateWorker>(context).build().doWork()
        assertNotNull(core.lastKnownState(other.id))
        assertEquals(old, core.lastKnownState(first.id))
    }

    @Test fun biometricHostsCanRefreshWhileTheAppIsInFront() = runTest {
        val host = core.addHost("private", Endpoint.Ssh("box", 22u, "me"))
        core.setKeyProtection(KeyProtection.BIOMETRIC)
        val model = HostsModel(core, backgroundScope)
        model.foregrounded()
        runCurrent()
        assertEquals(listOf(host.id), core.snapshotsRequested)
    }

}
