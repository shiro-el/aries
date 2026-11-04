package io.github.shiroel.aries

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.CallAnswer
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallReply
import io.github.shiroel.aries.core.CallReplyOutcome
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.ui.FakeCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/** The app with a fake core, as the worker finds it. */
class FakeCoreApp : Application(), HasCore, HasNotificationVault {
    override val notificationVault = object : io.github.shiroel.aries.core.KeyVault {
        override fun seal(plaintext: ByteArray, protection: KeyProtection) =
            plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
        override fun open(sealed: ByteArray, protection: KeyProtection) = seal(sealed, protection)
    }
    override val core = FakeCore()
}

/** Seam 3: answering a Call from its notification (buttons, receiver, worker), against a fake core. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
class CallNotificationAnswerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = context as FakeCoreApp
    private val core get() = app.core
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var hostId: String

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // Online, as the worker checks first.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        hostId = core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me")).id
        core.ensureKey()
    }

    private fun push(choices: List<String> = listOf("Merge", "Wait")) =
        PushNotice(hostId, "box", PushContent.Call("c1", "t3", true, "Review ready", "Tests pass.", CallUrgency.URGENT, choices, 1uL))

    private fun notice() = push().let { CallNotice(it.hostId, it.hostName, it.content as PushContent.Call) }

    private fun shown(): Notification = manager.activeNotifications.single { it.tag == "call:c1" }.notification

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun buttons(n: Notification) = n.actions.orEmpty().map { it.title.toString() }

    private fun showWithBackgroundKey() =
        PushService.show(context, push()) { core.canAnswerInBackground(it) }

    /** Runs the worker as WorkManager would after [ChoiceReceiver]. */
    private fun runWorker(choice: Int): ListenableWorker.Result = runBlocking {
        if (manager.activeNotifications.none { it.tag == "call:c1" }) showWithBackgroundKey()
        TestListenableWorkerBuilder<CallReplyWorker>(context)
            .setInputData(CallReplyWorker.input(context, notice(), choice))
            .build()
            .doWork()
    }

    @Test
    fun callBodiesAndChoicesAreSealedInWorkAndPendingIntents() {
        showWithBackgroundKey()
        val input = CallReplyWorker.input(context, notice(), 0).toByteArray().toString(Charsets.ISO_8859_1)
        val extra = shadowOf(shown().actions.first().actionIntent).savedIntent
            .getStringExtra(ChoiceReceiver.EXTRA_NOTICE)!!
        for (secret in listOf("Tests pass.", "Merge")) {
            assertFalse(input.contains(secret))
            assertFalse(extra.contains(secret))
        }
    }

    @Test
    fun aCallShowsItsChoicesAsButtonsAndKeepsTheTapOnTheTerminal() {
        showWithBackgroundKey()

        val n = shown()
        assertEquals(listOf("Merge", "Wait"), buttons(n))
        assertNotNull("tap opens the Terminal (ticket 29)", n.contentIntent)
    }

    @Test
    fun aHostWhoseKeyNeedsABiometricUnlockShowsOpenAppInstead() {
        core.setKeyProtection(KeyProtection.BIOMETRIC)

        showWithBackgroundKey()

        assertEquals(listOf(str(R.string.call_notify_open_app)), buttons(shown()))
    }

    @Test
    fun moreChoicesThanButtonsShowOpenApp() {
        PushService.show(context, push(listOf("a", "b", "c", "d"))) { true }

        assertEquals(listOf(str(R.string.call_notify_open_app)), buttons(shown()))
    }

    @Test
    fun aCallWithoutChoicesOffersReplyAndSnooze() {
        PushService.show(context, push(emptyList())) { true }

        assertEquals(listOf(str(R.string.notification_reply_button), str(R.string.notification_snooze_button)), buttons(shown()))
    }

    @Test
    fun tappingAChoiceShowsSendingWithoutButtonsAndQueuesOneReply() {
        val hold = CompletableDeferred<Unit>()
        core.replyHold = hold
        showWithBackgroundKey()
        val wait = shown().actions[1].actionIntent

        wait.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        wait.send() // a second tap while it is on its way
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val n = shown()
        assertEquals(str(R.string.call_notify_sending, "Wait"), text(n))
        assertEquals(emptyList<String>(), buttons(n))
        val works = WorkManager.getInstance(context).getWorkInfosForUniqueWork("call-reply:$hostId/c1").get()
        assertEquals(1, works.size)
        assertEquals(WorkInfo.State.RUNNING, works.single().state)
        hold.complete(Unit)
    }

    @Test
    fun aDeliveredAnswerShowsOnTheSameNotificationWithoutButtons() {
        showWithBackgroundKey()
        core.replyResult = CallReply(CallReplyOutcome.DELIVERED, CallAnswer(1u, "Wait", CallAnswerState.DELIVERED, 2uL, null), null)

        assertEquals(ListenableWorker.Result.success(), runWorker(1))

        assertEquals(listOf(Triple(hostId, "c1", 1u)), core.replies)
        val n = shown()
        assertEquals(1, manager.activeNotifications.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 })
        assertEquals(str(R.string.call_answer_delivered, "Wait"), text(n))
        assertEquals(emptyList<String>(), buttons(n))
    }

    @Test
    fun aHostThatCouldNotBeReachedIsNotSentAndCanBeTriedAgain() {
        core.replyFailure = CoreException.Unreachable("connection refused")

        runWorker(0)

        val n = shown()
        assertEquals(str(R.string.call_notify_unreachable), text(n))
        assertEquals(listOf(str(R.string.call_notify_try_again)), buttons(n))
        // "Try again" sends the same Choice.
        val retry = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(0, retry.getIntExtra(ChoiceReceiver.EXTRA_CHOICE, -1))
    }

    @Test
    fun aLockedKeyIsNotSentAndPointsToTheApp() {
        core.replyFailure = CoreException.KeyLocked()

        runWorker(0)

        assertEquals(str(R.string.call_notify_key_locked), text(shown()))
        assertEquals(listOf(str(R.string.call_notify_open_app)), buttons(shown()))
    }

    @Test
    fun aRejectedKeyIsNotSentAndPointsToTheApp() {
        core.replyFailure = CoreException.AuthRejected()

        runWorker(0)

        assertEquals(str(R.string.call_notify_key_rejected), text(shown()))
        assertEquals(listOf(str(R.string.call_notify_open_app)), buttons(shown()))
    }

    @Test
    fun aReplyThatStartedButNeverReportedIsUnknownAndSafeToRetry() {
        core.replyResult = CallReply(CallReplyOutcome.UNKNOWN, null, "the connection ended")

        runWorker(0)

        assertEquals(str(R.string.call_notify_unknown), text(shown()))
        assertEquals(listOf(str(R.string.call_notify_try_again), str(R.string.call_notify_open_app)), buttons(shown()))
    }

    @Test
    fun aRetryAfterTheFirstGotThroughShowsTheRecordedAnswer() {
        core.replyResult = CallReply(CallReplyOutcome.ANSWERED, CallAnswer(0u, "Merge", CallAnswerState.DELIVERED, 2uL, null), null)

        runWorker(0)

        assertEquals(str(R.string.call_answer_delivered, "Merge"), text(shown()))
    }

    @Test
    fun withoutANetworkItIsNotSentAtOnce() = runBlocking {
        val view = CallReplies.answer(context, core, notice(), 0, online = false)

        assertEquals(str(R.string.call_notify_no_network), view.line)
        assertEquals(0, view.retry)
        assertTrue(core.replies.isEmpty())
    }

    @Test
    fun theNoticeSurvivesTheTripThroughTheIntent() {
        val n = notice()
        assertEquals(n, CallNotice.fromJson(n.toJson()))
        assertNull(CallNotice.fromJson("{}"))
    }
}
