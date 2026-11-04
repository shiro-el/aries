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
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun foregroundProgressAndCompletionPublishDurablyWithoutOldProgressResurrectingTheWatch() = runBlocking {
        val published = mutableListOf<WatchProgress>()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                WatchProgressWorker(appContext, workerParameters) { request ->
                    published += requireNotNull(WatchProgress.fromBytes(request.data))
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setWorkerFactory(factory).build())
        suspend fun publish() = TestListenableWorkerBuilder<WatchProgressWorker>(context).setWorkerFactory(factory).build().doWork()
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val progress = PushNotice("h1", "Host", PushContent.Progress("remote/t7", "Builder", 10uL, 2u, 100uL, false))
            PushService.show(context, progress)
            publish()
            assertEquals(WatchProgress("h1", "remote/t7", "Builder", 90_000, 2, 100_000), published.last())
            PushService.show(context, PushNotice("h1", "Host",
                PushContent.Status("remote/t7", "Builder", null, true, AgentStatus.Done, 101uL, null, null, null)))
            // FCM can deliver ordinary Progress after high-priority Done, including after process restart.
            PushService.show(context, progress)
            publish()
            assertTrue(published.last().cancelled)
            assertEquals(101_000L, published.last().atMs)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
