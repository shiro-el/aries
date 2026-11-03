package io.github.shiroel.aries

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [32])
class InlineNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun status(status: AgentStatus = AgentStatus.Done, turn: ULong? = 7uL) =
        PushNotice("h1", "box", PushContent.Status("t1", "agent", null, true, status, 1uL, turn, null, "All tests passed."))
    private fun shown(tag: String = "t1") = manager.activeNotifications.single { it.tag == tag }.notification

    private val core get() = (context as FakeCoreApp).core
    private fun text() = shown().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun target() = shadowOf(shown().actions.first().actionIntent).savedIntent
        .getStringExtra(InlineNotifications.TARGET)!!
    private fun online(enabled: Boolean = true) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        if (enabled) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }
    private suspend fun work(target: String, action: String = InlineNotifications.REPLY, attempt: Int = 0,
        created: Long = System.currentTimeMillis()) = TestListenableWorkerBuilder<InlineActionWorker>(context)
        .setRunAttemptCount(attempt).setInputData(InlineActionWorker.input(target, action, "run tests", created))
        .build().doWork()

    @Test
    fun anOversizedReplyReportsFailureInsteadOfLeavingSending() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        online()
        PushService.show(context, status())
        val action = shown().actions.single { !it.remoteInputs.isNullOrEmpty() }
        val fill = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply {
            putCharSequence(InlineNotifications.REPLY_TEXT, "a".repeat(20_000))
        })
        action.actionIntent.send(context, 0, fill)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.notification_action_failed), text())
        assertTrue(core.backgroundMessages.isEmpty())
    }

    @Test
    fun anOfflineRemoteInputFailsImmediatelyWithoutWaitingForWork() {
        online(false)
        PushService.show(context, status())
        val action = shown().actions.single { !it.remoteInputs.isNullOrEmpty() }
        val fill = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply {
            putCharSequence(InlineNotifications.REPLY_TEXT, "send later")
        })
        action.actionIntent.send(context, 0, fill)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.notification_action_offline), text())
        assertTrue(core.backgroundMessages.isEmpty())
    }

    @Test
    fun aSnoozedCardDoesNotReplaceANewerNotification() {
        PushService.show(context, status())
        shown().actions.single { it.title == context.getString(R.string.notification_snooze_button) }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm!!
        PushService.show(context, status(AgentStatus.Error("newer error")))
        alarm.operation!!.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(text().contains("newer error"))
    }

    @Test
    fun offlineBlockedAndConnectionFailuresAreVisibleWithoutAnyAutomaticRetry() = runBlocking {
        PushService.show(context, status())
        online(false)
        work(target())
        assertEquals(context.getString(R.string.notification_action_offline), text())
        assertTrue(core.backgroundMessages.isEmpty())

        online()
        PushService.show(context, status())
        core.backgroundSendOutcome = io.github.shiroel.aries.core.SendOutcome.Blocked(null, "Allow Write?")
        work(target())
        assertEquals(context.getString(R.string.chat_send_refused), text())
        assertEquals(1, core.backgroundMessages.size)

        PushService.show(context, status())
        core.backgroundFailure = io.github.shiroel.aries.core.CoreException.Unreachable("connection refused")
        work(target())
        assertEquals(context.getString(R.string.notification_action_failed), text())
        assertTrue(shown().actions.isNullOrEmpty())
        PushService.show(context, status())
        core.backgroundFailure = io.github.shiroel.aries.core.CoreException.KeyLocked()
        work(target())
        assertEquals(context.getString(R.string.notification_action_key_locked), text())
    }

    @Test
    fun interruptedAndStaleWorkNeverResendsText() = runBlocking {
        online()
        PushService.show(context, status())
        work(target(), attempt = 1)
        assertEquals(context.getString(R.string.notification_action_failed), text())
        PushService.show(context, status())
        work(target(), created = System.currentTimeMillis() - 61_000)
        assertEquals(context.getString(R.string.notification_action_failed), text())
        assertTrue(core.backgroundMessages.isEmpty())
    }

    @Test
    fun seenWritesTheNotificationsTurnAndRemovesTheCardAndSummary() = runBlocking {
        online()
        PushService.show(context, status())
        work(target(), InlineNotifications.SEEN)
        assertEquals(listOf("h1 t1 seen=7"), core.labelWrites)
        assertTrue(manager.activeNotifications.isEmpty())
        PushService.show(context, status(turn = null))
        assertFalse(shown().actions.any { it.title == context.getString(R.string.notification_seen_button) })
        shown().deleteIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("h1 t1 seen=7"), core.labelWrites)
    }

    @Test
    fun typingIsShownAndALateResultCannotOverwriteANewerNotification() = runBlocking {
        online()
        PushService.show(context, status())
        val old = target()
        val hold = CompletableDeferred<Unit>()
        core.backgroundHold = hold
        val result = async(Dispatchers.Default) { work(old) }
        withTimeout(5_000) {
            while (text() != context.getString(R.string.chat_send_held)) delay(10)
        }
        PushService.show(context, status(AgentStatus.Error("newer error")))
        hold.complete(Unit)
        result.await()
        assertTrue(text().contains("newer error"))
        assertTrue(shown().actions.any { !it.remoteInputs.isNullOrEmpty() })
    }

    @Test
    fun remoteInputIsReadByTheReceiverAndSentOnceThroughWorkManager() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        online()
        PushService.show(context, status())
        val action = shown().actions.single { !it.remoteInputs.isNullOrEmpty() }
        val fill = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply {
            putCharSequence(InlineNotifications.REPLY_TEXT, "from the notification")
        })
        action.actionIntent.send(context, 0, fill)
        withTimeout(5_000) {
            while (text() != context.getString(R.string.notification_message_sent)) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        assertEquals(listOf(Triple("h1", "t1", "from the notification")), core.backgroundMessages)
    }

    @Test
    fun snoozeUsesAnInexactOneHourAlarmAndRepostsTheSamePrivateCard() {
        PushService.show(context, status()) { true }
        val original = shown()
        val before = SystemClock.elapsedRealtime()
        original.actions.single { it.title == context.getString(R.string.notification_snooze_button) }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.activeNotifications.isEmpty())
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm!!.type)
        assertEquals(before + 3_600_000, alarm.triggerAtTime)
        assertTrue("inexact window", alarm.windowLengthMs > 0)
        alarm.operation!!.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("All tests passed.", shown().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(context.getString(R.string.agent_status_done), shown().publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(original.actions.map { it.title.toString() }, shown().actions.map { it.title.toString() })
        assertTrue((context as FakeCoreApp).core.labelWrites.isEmpty())
    }

    @Test
    fun aBackgroundReplyUpdatesTheSameNotificationAndDoesNotOfferToResend() = runBlocking {
        PushService.show(context, status()) { true }
        online()
        assertEquals(ListenableWorker.Result.success(), work(target()))
        assertEquals(listOf(Triple("h1", "t1", "run tests")), (context as FakeCoreApp).core.backgroundMessages)
        assertEquals(context.getString(R.string.notification_message_sent), shown().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(shown().actions.isNullOrEmpty())
    }

    @Test
    fun onlyDoneErrorAndChoiceLessCallsOfferAnAuthenticatedFreeTextReply() {
        for (state in listOf(AgentStatus.Done, AgentStatus.Error("failed"))) {
            PushService.show(context, status(state)) { true }
            val reply = shown().actions.orEmpty().singleOrNull { !it.remoteInputs.isNullOrEmpty() }
            assertNotNull("a reply on $state", reply)
            assertTrue(reply!!.isAuthenticationRequired)
            assertTrue(reply.remoteInputs.single().allowFreeFormInput)
            assertFalse(reply.actionIntent.isImmutable)
        }
        PushService.show(context, status(AgentStatus.Blocked(null, null))) { true }
        assertTrue(shown().actions.orEmpty().all { it.remoteInputs.isNullOrEmpty() })
        for (choices in listOf(emptyList(), listOf("Yes", "No"))) {
            PushService.show(context, PushNotice("h1", "box", PushContent.Call("c1", "t1", true, "Review", "Ready", CallUrgency.CHECK, choices, 1uL))) { true }
            assertEquals(choices.isEmpty(), shown("call:c1").actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() })
        }
    }
}
