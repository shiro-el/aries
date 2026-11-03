package io.github.shiroel.aries

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The FCM -> background core API seam; no notification or user action. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
class PairingChallengeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core get() = (context as FakeCoreApp).core

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(context) }

    @Test fun challengeIsConfirmedAutomaticallyAndDuplicateDeliveryUsesOneWorkRequest() = runBlocking {
        val id = "i".repeat(43)
        val proof = "p".repeat(43)
        val message = RemoteMessage.Builder("phone").addData("v", "1")
            .addData("kind", "pairing_challenge").addData("verification_id", id).addData("challenge", proof).build()
        val service = Robolectric.buildService(PushService::class.java).create().get()
        service.onMessageReceived(message)
        service.onMessageReceived(message)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("push-ownership:$id").get()
        assertEquals(1, infos.size)
        val worker = TestListenableWorkerBuilder<PairingChallengeWorker>(context)
            .setInputData(androidx.work.workDataOf("verification_id" to id, "challenge" to proof)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf(id to proof), core.pushConfirmations)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun malformedChallengeDoesNotCreateBackgroundWork() {
        val service = Robolectric.buildService(PushService::class.java).create().get()
        service.onMessageReceived(RemoteMessage.Builder("phone").addData("v", "1")
            .addData("kind", "pairing_challenge").addData("verification_id", "bad").addData("challenge", "bad").build())
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("push-ownership:bad").get().isEmpty())
    }

    @Test fun transientConfirmationFailureIsRetriedButUnknownOrExpiredIdsFinishQuietly() = runBlocking {
        fun worker() = TestListenableWorkerBuilder<PairingChallengeWorker>(context)
            .setInputData(androidx.work.workDataOf("verification_id" to "i".repeat(43), "challenge" to "p".repeat(43))).build()
        core.pushConfirmationFailure = true
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        core.pushConfirmationFailure = false
        core.pushConfirmationKnown = false
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }
}
