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
    @org.junit.Before fun initializeWorkManager() { WorkManagerTestInitHelper.initializeTestWorkManager(context) }
    private var pushedAt = 0uL
    private fun status(status: AgentStatus = AgentStatus.Done, turn: ULong? = 7uL) =
        PushNotice("h1", "box", PushContent.Status("t1", "agent", null, true, status, ++pushedAt, turn, null, "All tests passed."))
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
    /** Optional manual inspection artifact, never a pixel assertion. */
    private fun screenshot(name: String) {
        val directory = System.getenv("ARIES_NOTIFICATION_RENDER_DIR") ?: return
        java.io.File(directory).mkdirs()
        val parent = android.widget.FrameLayout(context)
        val view = Notification.Builder.recoverBuilder(context, shown()).createBigContentView().apply(context, parent)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE); view.draw(this) }
        java.io.File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private suspend fun work(target: String, action: String = InlineNotifications.REPLY, attempt: Int = 0,
        created: Long = System.currentTimeMillis()) = TestListenableWorkerBuilder<InlineActionWorker>(context)
        .setRunAttemptCount(attempt).setInputData(InlineActionWorker.input(context, target, action, "run tests", created))
        .build().doWork()

    // Regression: a foreground shade reply/Seen must survive its own consumed-card update.
    @Test
    @Config(application = InlineCoreApp::class)
    fun foregroundReplyAndSeenReachTheHostWhileViewingTheAgent() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        online()
        val app = context as InlineCoreApp
        val activity = android.app.Activity()
        for (reply in listOf(true, false)) {
            app.notificationVisibility.onActivityPaused(activity)
            PushService.show(context, status())
            app.notificationVisibility.viewing(activity, PushTarget("h1", "t1"))
            app.notificationVisibility.onActivityResumed(activity)
            val action = shown().actions.single {
                if (reply) !it.remoteInputs.isNullOrEmpty()
                else it.title == context.getString(R.string.notification_seen_button)
            }
            val hold = CompletableDeferred<Unit>()
            if (reply) app.core.backgroundHold = hold
            val fill = Intent()
            if (reply) RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply {
                putCharSequence(InlineNotifications.REPLY_TEXT, "from the shade")
            })
            action.actionIntent.send(context, 0, fill)
            withTimeout(5_000) {
                while (if (reply) app.core.backgroundMessages.isEmpty() else app.core.labelWrites.isEmpty()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            if (reply) {
                assertTrue(shown().actions.isNullOrEmpty())
                action.actionIntent.send(context, 0, fill)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, app.core.backgroundMessages.size)
                hold.complete(Unit)
                withTimeout(5_000) {
                    while (text() != context.getString(R.string.notification_message_sent)) delay(10)
                }
            }
        }
        assertEquals(listOf(Triple("h1", "t1", "from the shade")), app.core.backgroundMessages)
        assertEquals(listOf("h1 t1 seen=7"), app.core.labelWrites)
    }

    @Test
    fun anUnreadableReplyShowsFailureWithoutSendingOrFallingBackToPlaintext() = runBlocking {
        online()
        PushService.show(context, status())
        val data = androidx.work.workDataOf("target" to target(), "action" to InlineNotifications.REPLY,
            "text" to "legacy-plaintext-reply", "created" to System.currentTimeMillis())
        TestListenableWorkerBuilder<InlineActionWorker>(context).setRunAttemptCount(0).setInputData(data).build().doWork()
        assertEquals(context.getString(R.string.notification_action_failed), text())
        assertTrue(core.backgroundMessages.isEmpty())
    }

    @Test
    fun replyWorkDataDoesNotPersistTheReplyInPlaintext() {
        PushService.show(context, status())
        val data = InlineActionWorker.input(context, target(), InlineNotifications.REPLY, "secret-reply-180")
        assertFalse(data.toByteArray().toString(Charsets.ISO_8859_1).contains("secret-reply-180"))
    }

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

    // Regression: an offline RemoteInput must keep its text and remain explicitly retryable.
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun anOfflineRemoteInputPreservesTheReplyAndAllowsAnExplicitRetry() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
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
        val retry = shown().actions.single { !it.remoteInputs.isNullOrEmpty() }
        assertEquals("send later", retry.remoteInputs.single().choices.single().toString())
        assertTrue(shown().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("send later"))
        assertNotEquals(shadowOf(action.actionIntent).savedIntent.data, shadowOf(retry.actionIntent).savedIntent.data)
        assertEquals(InlineTarget.fromJson(shadowOf(retry.actionIntent).savedIntent.getStringExtra(InlineNotifications.TARGET)!!)!!.generation,
            shown().extras.getString(InlineNotifications.GENERATION))
        assertFalse(shown().extras.getBoolean("inline_consumed"))
        screenshot("offline-reply")
        online()
        val resend = Intent()
        RemoteInput.addResultsToIntent(retry.remoteInputs, resend, Bundle().apply {
            putCharSequence(InlineNotifications.REPLY_TEXT, retry.remoteInputs.single().choices.single())
        })
        retry.actionIntent.send(context, 0, resend)
        withTimeout(5_000) {
            while (text() != context.getString(R.string.notification_message_sent)) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        assertEquals(listOf(Triple("h1", "t1", "send later")), core.backgroundMessages)
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

    // Regression: checking only active cards revives an old snooze after the newer card was swiped.
    @Test
    fun aSnoozedCardDoesNotReturnAfterANewerCardWasSwiped() {
        PushService.show(context, status())
        shown().actions.single { it.title == context.getString(R.string.notification_snooze_button) }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm!!
        PushService.show(context, status(AgentStatus.Error("newer error")))
        shown().deleteIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        alarm.operation!!.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.activeNotifications.isEmpty())
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
        assertEquals("run tests", shown().actions.single { !it.remoteInputs.isNullOrEmpty() }.remoteInputs.single().choices.single())
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
        assertEquals("run tests", shown().actions.single { !it.remoteInputs.isNullOrEmpty() }.remoteInputs.single().choices.single())
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

    // Regression: the card can disappear after acceptance but before the persisted work starts.
    @Test
    fun acceptedReplyAndSeenStillRunAfterTheirCardsAreDismissed() = runBlocking {
        online()
        for (action in listOf(InlineNotifications.REPLY, InlineNotifications.SEEN)) {
            PushService.show(context, status())
            val accepted = target()
            manager.cancel("t1", "h1".hashCode())
            work(accepted, action)
        }
        assertEquals(listOf(Triple("h1", "t1", "run tests")), core.backgroundMessages)
        assertEquals(listOf("h1 t1 seen=7"), core.labelWrites)
    }

    // Regression: a late failed send must preserve the typed text without overwriting a newer push.
    @Test
    fun aFailedReplyKeepsItsDraftEvenWhenANewerCardArrivesDuringTheSend() = runBlocking {
        online()
        PushService.show(context, status())
        val accepted = target()
        val hold = CompletableDeferred<Unit>()
        core.backgroundHold = hold
        core.backgroundFailure = io.github.shiroel.aries.core.CoreException.Unreachable("connection lost")
        val result = async(Dispatchers.Default) { work(accepted) }
        withTimeout(5_000) {
            while (text() != context.getString(R.string.chat_send_held)) delay(10)
        }
        PushService.show(context, status(AgentStatus.Error("newer error")))
        hold.complete(Unit)
        result.await()
        assertTrue(text().contains("newer error"))
        val recovery = manager.activeNotifications.single { it.tag.startsWith("inline-recovery:") }.notification
        assertEquals("run tests", recovery.actions.single { !it.remoteInputs.isNullOrEmpty() }.remoteInputs.single().choices.single())
        assertFalse(recovery.publicVersion.extras.toString().contains("run tests"))
        assertEquals(PushTarget("h1", "t1"), PushTarget.from(shadowOf(recovery.contentIntent).savedIntent))
    }

    // Regression D98: opening the Agent while a shade reply is held must not lose
    // failed text, overwrite the active draft, or automatically retry it after recreation.
    @Test fun aFailedReplyAfterOpeningTheAgentBelongsToItsLiveAndRestoredOutbox() = runBlocking {
        online()
        val host = core.addHost("box", io.github.shiroel.aries.core.Endpoint.Ssh("box", 22u, "me"))
        val terminal = io.github.shiroel.aries.core.TerminalInfo("t1", "Agent", "", emptyList(), null, "live", AgentStatus.Done)
        val owner = io.github.shiroel.aries.ui.ChatModel(core, host, terminal)
        owner.draft = "a different draft"
        PushService.show(context, status())
        val accepted = target()
        val hold = CompletableDeferred<Unit>()
        core.backgroundHold = hold
        core.backgroundFailure = io.github.shiroel.aries.core.CoreException.Unreachable("connection lost")
        val result = async(Dispatchers.Default) { work(accepted) }
        withTimeout(5_000) {
            while (text() != context.getString(R.string.chat_send_held)) delay(10)
        }
        NotificationGroups.viewing(context, PushTarget(host.id, "t1"))
        assertTrue(manager.activeNotifications.isEmpty())
        hold.complete(Unit)
        withTimeout(5_000) {
            while (!result.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        }
        result.await()
        assertEquals("a different draft", owner.draft)
        assertEquals("run tests", owner.queuedMessages.single().draft)
        assertEquals(io.github.shiroel.aries.ui.QueuedMessagePhase.Uncertain, owner.queuedMessages.single().phase)
        owner.draft = "edited after failure"
        val restored = io.github.shiroel.aries.ui.ChatModel(core, host, terminal)
        assertEquals("edited after failure", restored.draft)
        assertEquals("run tests", restored.queuedMessages.single().draft)
        assertEquals(1, core.backgroundMessages.size)
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
    fun watchRemoteInputIsReadByThePhoneReceiverAndSentOnceThroughWorkManager() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        online()
        PushService.show(context, status())
        val action = Notification.WearableExtender(shown()).actions.single { !it.remoteInputs.isNullOrEmpty() }
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
        assertTrue(Notification.WearableExtender(shown()).actions.isEmpty())
    }

    @Test
    fun snoozeAfterChoosingTheAppLanguageStillRepostsThePrivateCard() {
        // Regression: a locale wrapper must not hide the reminder from the application alarm service.
        AppLanguage.choose(context, "en")
        try {
            checkOneHourReminder()
        } finally {
            AppLanguage.choose(context, "")
        }
    }

    @Test
    fun snoozeUsesAnInexactOneHourAlarmAndRepostsTheSamePrivateCard() = checkOneHourReminder()

    private fun checkOneHourReminder() {
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
        @Suppress("DEPRECATION")
        val stored = shadowOf(alarm.operation!!).savedIntent
            .getParcelableExtra<Notification>(InlineNotifications.NOTIFICATION)!!
        assertFalse("alarm extras retain the message", stored.extras.toString().contains("All tests passed."))
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

/** Real foreground policy with an inert core and notification vault. */
class InlineCoreApp : AriesApplication(), HasNotificationVault {
    override val core = io.github.shiroel.aries.ui.FakeCore()
    override val notificationVault = FakeCoreApp().notificationVault
    override fun onCreate() { registerActivityLifecycleCallbacks(notificationVisibility) }
}
