package io.github.shiroel.aries

import android.content.Context
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.PushQuestion
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.CallUrgency
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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

    // Regression D93: a successful pull must retire a question answered on another device,
    // including its bridged copy and a previously scheduled reminder.
    @Test fun pullRecallsAnsweredQuestionAndPreventsItsReminderFromReturning() = runBlocking {
        val host = core.addHost("dev", Endpoint.Ssh("box", 22u, "me"))
        val question = PushQuestion("q1", "Continue?", listOf("Yes"))
        PushService.show(context, PushNotice(host.id, host.name, PushContent.Status("t1", "Agent", null, true,
            AgentStatus.Blocked(BlockedReason.QUESTION, null),
            1uL, 7uL, question, null))) { true }
        val manager = context.getSystemService(NotificationManager::class.java)
        val old = manager.activeNotifications.single { it.tag == "t1" }.notification
        val progress = PushNotice(host.id, host.name, PushContent.Progress("t2", "Builder", 10uL, 0u,
            (System.currentTimeMillis() / 1000).toULong(), false))
        PushService.show(context, progress)
        core.terminals = listOf(TerminalInfo("t1", "Agent", "", emptyList(), null, "live",
            AgentStatus.Working, turn = 7uL))
        TestListenableWorkerBuilder<LastKnownStateWorker>(context).build().doWork()
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(NotificationGroups.canRestore(context, host.id, "t1", old))
        assertTrue(WatchProgressPhone.all(context).single { it.terminal == "t2" }.cancelled)
        NotificationGroups.post(context, host.id, host.name, "t1", old)
        PushService.show(context, progress) // Normal-priority progress can arrive again after the pull.
        assertTrue(manager.activeNotifications.isEmpty())
    }

    // Regression D93: a live storage watch must retire q1 when q2 replaces it without
    // an intervening Working snapshot; a stale cache must not retract the current q2.
    @Test fun liveWatchRecallsAReplacedQuestionWithoutRetractingTheNextOne() = runBlocking {
        val host = core.addHost("dev", Endpoint.Ssh("box", 22u, "me"))
        val blocked = AgentStatus.Blocked(BlockedReason.QUESTION, null)
        core.terminals = listOf(TerminalInfo("t1", "Agent", "", emptyList(), null, "live", blocked, turn = 7uL))
        fun ask(id: String, at: ULong) = PushService.show(context, PushNotice(host.id, host.name,
            PushContent.Status("t1", "Agent", null, true, blocked, at, 7uL,
                PushQuestion(id, "Continue?", listOf("Yes")), null))) { true }
        fun entry(id: String, status: ChatToolStatus) =
            ChatEntry(id, null, ChatEntryBody.Question(
                "request_user_input", "", emptyList(), status, null))
        ask("q1", 1uL)
        core.cachedChats[host.id to "t1"] = ChatSnapshot(
            listOf(entry("q1", ChatToolStatus.RUNNING),
                entry("q2", ChatToolStatus.RUNNING)), ChatStatus.LIVE, null)
        // Both requests can be pending: Codex still presents q1 first.
        core.pushChat(core.cachedChats.getValue(host.id to "t1"))
        TestListenableWorkerBuilder<LastKnownStateWorker>(context).build().doWork()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.tag == "t1" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val publication = kotlinx.coroutines.CompletableDeferred<Unit>()
        val observation = WatchPhone.start(context, core, scope) {
            publication.complete(Unit)
            kotlinx.coroutines.awaitCancellation() // A slow/disconnected DataClient must not block phone recall.
        }
        try {
            core.cachedChats[host.id to "t1"] = ChatSnapshot(
                listOf(entry("q1", ChatToolStatus.DONE),
                    entry("q2", ChatToolStatus.RUNNING)), ChatStatus.LIVE, null)
            core.snapshot(host.id)
            withTimeout(5_000) {
                while (manager.activeNotifications.any { it.tag == "t1" }) delay(10)
            }
            publication.await()
            ask("q2", 2uL)
            core.cachedChats[host.id to "t1"] = ChatSnapshot(
                listOf(entry("q1", ChatToolStatus.RUNNING), entry("q2", ChatToolStatus.RUNNING)), ChatStatus.LIVE, null)
            // The live Host still owns q2 even though the local Chat is stale.
            core.pushChat(ChatSnapshot(listOf(entry("q1", ChatToolStatus.DONE), entry("q2", ChatToolStatus.RUNNING)), ChatStatus.LIVE, null))
            TestListenableWorkerBuilder<LastKnownStateWorker>(context).build().doWork()
            assertTrue(manager.activeNotifications.any { it.tag == "t1" })
            core.terminals = core.terminals.map { it.copy(agentStatus = AgentStatus.Working) }
            core.snapshot(host.id)
            withTimeout(5_000) {
                while (manager.activeNotifications.any { it.tag == "t1" }) delay(10)
            }
        } finally {
            observation.cancel()
            observation.join()
            scope.coroutineContext[Job]!!.cancel()
        }
    }

    // Regression D94: notification answers must update the shared waiting count now,
    // rather than leaving widgets/QS/Watch surfaces blocked until the periodic pull.
    @Test fun everyNotificationAnswerPullsFreshStateForTheSharedWaitingCount() = runBlocking {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        for (kind in listOf("question", "call", "inline")) {
            val host = core.addHost(kind, Endpoint.Ssh(kind, 22u, "me"))
            val terminal = TerminalInfo("t1", "Agent", "", emptyList(), null, "live",
                AgentStatus.Blocked(BlockedReason.QUESTION, null))
            core.terminalsOf[host.id] = listOf(terminal)
            core.snapshot(host.id)
            assertEquals(1, WatchPhone.summary(listOf(host), core.lastKnownStates()).waiting)
            // The Host resumes after accepting the answer; only the saved snapshot is still Blocked.
            core.terminalsOf[host.id] = listOf(terminal.copy(agentStatus = AgentStatus.Working))
            when (kind) {
                "question" -> TestListenableWorkerBuilder<QuestionReplyWorker>(context).setRunAttemptCount(0)
                    .setInputData(QuestionReplyWorker.input(context, QuestionNotice(host.id, host.name, "t1",
                        PushQuestion("q1", "Continue?", listOf("Yes"))), 0)).build().doWork()
                "call" -> TestListenableWorkerBuilder<CallReplyWorker>(context).setRunAttemptCount(0)
                    .setInputData(CallReplyWorker.input(context, CallNotice(host.id, host.name,
                        PushContent.Call("c1", "t1", true, "Review", "Ready", CallUrgency.CHECK,
                            listOf("Yes"), 1uL)), 0)).build().doWork()
                else -> TestListenableWorkerBuilder<InlineActionWorker>(context).setRunAttemptCount(0)
                    .setInputData(InlineActionWorker.input(context,
                        InlineTarget(host.id, host.name, "t1", "t1", null).toJson(), InlineNotifications.REPLY, "Continue")).build().doWork()
            }
            val requests = WorkManager.getInstance(context).getWorkInfosForUniqueWork("last-known-state:refresh:${host.id}").get()
            assertTrue("$kind did not request fresh state", requests.isNotEmpty())
            requests.filter { !it.state.isFinished }.forEach {
                WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(it.id)
            }
            withTimeout(5_000) {
                while (WatchPhone.summary(listOf(host), core.lastKnownStates()).waiting != 0) delay(10)
            }
        }
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
        assertEquals(1, manager.getWorkInfosForUniqueWork(workName).get().size)
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
        // Regression: recall's additional cache read must preserve the worker's
        // existing storage-failure behavior instead of failing the whole refresh.
        core.lastKnownStatesFailure = io.github.shiroel.aries.core.CoreException.Storage("unreadable cache")
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
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
