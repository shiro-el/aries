package io.github.shiroel.aries

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.weardata.WatchProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchProgressPhoneTest {
    private val now = System.currentTimeMillis() / 1000
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Regression D97: acknowledged completion must remove both DataItems and must not
    // republish on process restart or resurrect from a late normal-priority progress push.
    @Test fun acknowledgedCompletionDeletesRemoteItemsAndKeepsAReplayWatermark() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val remote = mutableMapOf<String, WatchProgress>()
        var operations = 0
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                WatchProgressWorker(appContext, workerParameters, delete = { path -> operations++; remote.remove(path); Unit }) { request ->
                    operations++
                    remote[request.uri.path!!] = requireNotNull(WatchProgress.fromBytes(request.data))
                }
        }
        suspend fun publish() = TestListenableWorkerBuilder<WatchProgressWorker>(context).setWorkerFactory(factory).build().doWork()
        val update = PushContent.Progress("t1", "Agent", 10uL, 0u, now.toULong(), false)
        WatchProgressPhone.progress(context, "h1", update)
        publish()
        assertEquals(2, remote.size)
        WatchProgressPhone.completed(context, "h1", "t1", (now + 1).toULong())
        publish()
        assertTrue(remote.isEmpty())
        assertTrue(WatchProgressPhone.all(context).isEmpty())
        val completedOperations = operations
        WatchProgressPhone.progress(context, "h1", update)
        publish()
        assertTrue(WatchProgressPhone.all(context).isEmpty())
        assertEquals(completedOperations, operations)
    }

    @Test fun independentAgentsSurviveWorkerPublicationAndCompletingOneDoesNotEraseTheOther() = runBlocking {
        val published = mutableListOf<WatchProgress>()
        val paths = mutableListOf<String?>()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                WatchProgressWorker(appContext, workerParameters, delete = {}) { request ->
                    published += requireNotNull(WatchProgress.fromBytes(request.data))
                    paths += request.uri.path
                }
        }
        // Only the explicitly built worker publishes to this fake DataClient.
        // Automatically scheduled work must not race its result observations.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WatchProgressPhone.progress(context, "h1", PushContent.Progress("t1", "One", 10uL, 0u, now.toULong(), false))
        WatchProgressPhone.progress(context, "h2", PushContent.Progress("t1", "Two", 10uL, 0u, (now - 10).toULong(), false))
        WatchProgressPhone.completed(context, "h1", "t1", (now + 1).toULong())
        TestListenableWorkerBuilder<WatchProgressWorker>(context).setWorkerFactory(factory).build().doWork()
        assertEquals(setOf("h1", "h2"), published.map { it.hostId }.toSet())
        assertTrue(published.last { it.hostId == "h1" }.cancelled)
        assertFalse(published.last { it.hostId == "h2" }.cancelled)
        assertEquals(2, published.map { it.dataPath }.toSet().size)
        assertTrue(paths.contains(WatchProgress.DATA_PATH))
    }

    @Test fun foregroundProgressAndCompletionPublishDurablyWithoutOldProgressResurrectingTheWatch() = runBlocking {
        val published = mutableListOf<WatchProgress>()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                WatchProgressWorker(appContext, workerParameters, delete = {}) { request ->
                    published += requireNotNull(WatchProgress.fromBytes(request.data))
                }
        }
        // Only the explicitly built worker publishes to this fake DataClient.
        // Automatically scheduled work must not race its result observations.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        suspend fun publish() = TestListenableWorkerBuilder<WatchProgressWorker>(context).setWorkerFactory(factory).build().doWork()
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val progress = PushNotice("h1", "Host", PushContent.Progress("remote/t7", "Builder", 10uL, 2u, now.toULong(), false))
            PushService.show(context, progress)
            publish()
            assertEquals(WatchProgress("h1", "remote/t7", "Builder", (now - 10) * 1000, 2, now * 1000), published.last())
            PushService.show(context, PushNotice("h1", "Host",
                PushContent.Status("remote/t7", "Builder", null, true, AgentStatus.Done, (now + 1).toULong(), null, null, null)))
            // FCM can deliver ordinary Progress after high-priority Done, including after process restart.
            PushService.show(context, progress)
            publish()
            assertTrue(published.last().cancelled)
            assertEquals((now + 1) * 1000, published.last().atMs)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
