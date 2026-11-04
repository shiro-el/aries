package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.core.PushQuestion
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/** The notifications handed to Android, including the actions sent to a paired watch. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [32])
class WearNotificationBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before fun initializeWorkManager() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun call(choices: List<String> = listOf("Continue", "Stop")) =
        PushNotice("h1", "box", PushContent.Call("c1", "t1", true, "Review", "Ready",
            CallUrgency.CHECK, choices, 1uL))

    private fun shown(tag: String = "call:c1") = manager.activeNotifications.single { it.tag == tag }.notification
    private fun wearable(notification: Notification) = Notification.WearableExtender(notification).actions

    private fun status(state: AgentStatus = AgentStatus.Done, turn: ULong? = 7uL) =
        PushNotice("h1", "box", PushContent.Status("t1", "agent", null, true, state, 2uL, turn, null, "Finished"))

    @Test
    fun questionOptionsBridgeInOrderAndSendingAndResultRemoveOldWatchChoices() {
        val question = PushQuestion("q1", "Which color?", listOf("Red", "Blue", "Green"))
        val status = status(AgentStatus.Blocked(BlockedReason.QUESTION, "Which color?"))
        val push = status.copy(content = (status.content as PushContent.Status).copy(question = question))
        PushService.show(context, push) { true }
        val notification = shown("t1")
        assertEquals(question.options, wearable(notification).map { it.title.toString() })
        assertEquals(notification.actions.map { it.actionIntent }, wearable(notification).map { it.actionIntent })

        val notice = QuestionNotice("h1", "box", "t1", question)
        QuestionNotifications.post(context, notice, QuestionView.Sending(1))
        assertTrue(wearable(shown("t1")).isEmpty())
        QuestionNotifications.post(context, notice, QuestionView.Done("Sent"))
        val open = wearable(shown("t1")).single()
        assertEquals(context.getString(R.string.question_notify_open_app), open.title)
        assertEquals(PushTarget("h1", "t1", chat = true), PushTarget.from(shadowOf(open.actionIntent).savedIntent))
    }

    // Regression: an Always grant must never execute on a watch or bypass the
    // phone card's confirmation through a background notification receiver.
    @Test
    fun alwaysAllowOpensThePhoneCardAndOnlyAllowAndDenyReachTheWatch() {
        val question = PushQuestion("permission:current", "Allow Write?", listOf("Allow", "Allow always", "Deny"))
        val status = status(AgentStatus.Blocked(BlockedReason.PERMISSION, null))
        PushService.show(context, status.copy(content = (status.content as PushContent.Status).copy(question = question))) { true }
        val notification = shown("t1")
        assertEquals(listOf("Allow", "Deny"), wearable(notification).map { it.title.toString() })
        val always = notification.actions[1]
        assertEquals(PushTarget("h1", "t1", chat = true), PushTarget.from(shadowOf(always.actionIntent).savedIntent))
        assertTrue(shadowOf(always.actionIntent).isActivity)
        assertEquals(listOf(notification.actions[0].actionIntent, notification.actions[2].actionIntent),
            wearable(notification).map { it.actionIntent })
        // An ordinary answer with the same text must remain available on the watch.
        val ordinary = status(AgentStatus.Blocked(BlockedReason.QUESTION, null))
        PushService.show(context, ordinary.copy(content = (ordinary.content as PushContent.Status).copy(
            question = question.copy(id = "q1")))) { true }
        assertEquals(question.options, wearable(shown("t1")).map { it.title.toString() })
    }

    @Test
    fun watchKeepsThePhonesOpenAppFallbackForLockedKeysAndOversizedChoices() {
        for ((choices, canAnswer) in listOf(listOf("Yes", "No") to false, listOf("a", "b", "c", "d") to true)) {
            PushService.show(context, call(choices)) { canAnswer }
            val action = wearable(shown()).single()
            assertEquals(context.getString(R.string.call_notify_open_app), action.title)
            assertEquals(PushTarget("h1", "t1"), PushTarget.from(shadowOf(action.actionIntent).savedIntent))

            val status = status(AgentStatus.Blocked(BlockedReason.QUESTION, "Pick?"))
            val question = PushQuestion("q1", "Pick?", choices)
            PushService.show(context, status.copy(content = (status.content as PushContent.Status).copy(question = question))) { canAnswer }
            val questionAction = wearable(shown("t1")).single()
            assertEquals(context.getString(R.string.question_notify_open_app), questionAction.title)
            assertEquals(PushTarget("h1", "t1", chat = true), PushTarget.from(shadowOf(questionAction.actionIntent).savedIntent))
        }
    }

    @Test
    fun callSendingClearsWatchChoicesAndFailureOffersOnlyTheCurrentRetry() {
        val push = call()
        val notice = CallNotice(push.hostId, push.hostName, push.content as PushContent.Call)
        PushService.show(context, push) { true }
        val original = wearable(shown()).first().actionIntent
        CallNotifications.post(context, notice, CallView.Sending(0))
        assertTrue(wearable(shown()).isEmpty())
        CallNotifications.post(context, notice, CallView.Done("Failed", retry = 0, openApp = true))
        val actions = wearable(shown())
        assertEquals(listOf(context.getString(R.string.call_notify_try_again), context.getString(R.string.call_notify_open_app)),
            actions.map { it.title.toString() })
        // A retry is a new accepted attempt; the previous watch action must stay consumed.
        assertNotEquals(original, actions.first().actionIntent)
        CallNotifications.post(context, notice, CallView.Done("Sent"))
        assertTrue(wearable(shown()).isEmpty())
    }

    @Test
    fun aChoiceNamedLikeSnoozeIsStillAvailableAndDoneWithoutATurnHasNoSeen() {
        val label = context.getString(R.string.notification_snooze_button)
        PushService.show(context, call(listOf(label))) { true }
        assertEquals(label, wearable(shown()).single().title)
        PushService.show(context, status(turn = null))
        assertEquals(listOf(context.getString(R.string.notification_reply_button)), wearable(shown("t1")).map { it.title.toString() })
    }

    @Test
    fun localOngoingProgressDoesNotBridgeButItsDoneReplacementCan() {
        PushService.show(context, PushNotice("h1", "box", PushContent.Progress("t1", "agent", 125uL, 3u, 1uL, false)))
        val progress = shown("t1")
        assertTrue(progress.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(progress.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue(wearable(progress).isEmpty())
        PushService.show(context, status())
        val done = shown("t1")
        assertEquals(0, done.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_LOCAL_ONLY or Notification.FLAG_NO_CLEAR))
        assertEquals(listOf(context.getString(R.string.notification_reply_button), context.getString(R.string.notification_seen_button)),
            wearable(done).map { it.title.toString() })
    }

    @Test
    fun existingChannelVibrationPreferencesSurvivePostsAndLegacyMigration() {
        val attention = NotificationChannel("host_notifications", "Custom attention", NotificationManager.IMPORTANCE_LOW).apply {
            vibrationPattern = longArrayOf(0, 800)
            enableVibration(false)
        }
        manager.createNotificationChannel(attention)
        PushService.show(context, status(AgentStatus.Error("failed")))
        PushService.show(context, status())
        for (id in listOf("host_notifications", "status_done")) {
            val channel = manager.getNotificationChannel(id)
            assertFalse(channel.shouldVibrate())
            assertArrayEquals(longArrayOf(0, 800), channel.vibrationPattern)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        }
        val emergency = NotificationChannel("calls_emergency", "Custom emergency", NotificationManager.IMPORTANCE_LOW).apply {
            vibrationPattern = longArrayOf(0, 900)
            enableVibration(false)
        }
        manager.createNotificationChannel(emergency)
        val push = call()
        PushService.show(context, push.copy(content = (push.content as PushContent.Call).copy(urgency = CallUrgency.EMERGENCY)))
        val saved = manager.getNotificationChannel("calls_emergency")
        assertFalse(saved.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 900), saved.vibrationPattern)
        assertEquals(NotificationManager.IMPORTANCE_LOW, saved.importance)
    }

    @Test
    fun newStatusChannelsHaveDistinctVibrationDefaultsAndEmergencyKeepsItsPattern() {
        // Start with attention: Done's default must not accidentally inherit the channel
        // created earlier in this same first post.
        PushService.show(context, status(AgentStatus.Error("failed")))
        val attention = manager.getNotificationChannel(shown("t1").channelId)
        assertTrue(attention.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 300, 150, 300), attention.vibrationPattern)
        PushService.show(context, status(AgentStatus.Blocked(null, null)))
        assertEquals(attention.id, shown("t1").channelId)
        PushService.show(context, status())
        val done = manager.getNotificationChannel(shown("t1").channelId)
        assertTrue(done.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 150), done.vibrationPattern)
        val emergency = call().let { it.copy(content = (it.content as PushContent.Call).copy(urgency = CallUrgency.EMERGENCY)) }
        PushService.show(context, emergency) { true }
        val channel = manager.getNotificationChannel(shown().channelId)
        assertEquals("calls_emergency", channel.id)
        assertTrue(channel.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 600, 300, 600, 300, 600), channel.vibrationPattern)
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(permissions.contains(android.Manifest.permission.VIBRATE))
    }

    @Test
    fun watchOffersAuthenticatedOsReplyAndSeenWhileSnoozeStaysOnThePhone() {
        for (notice in listOf(status(), status(AgentStatus.Error("failed")), call(emptyList()))) {
            PushService.show(context, notice)
            val notification = shown(if (notice.content is PushContent.Call) "call:c1" else "t1")
            val actions = wearable(notification)
            val hasSeen = (notice.content as? PushContent.Status)?.status == AgentStatus.Done
            assertEquals(listOfNotNull(context.getString(R.string.notification_reply_button),
                context.getString(R.string.notification_seen_button).takeIf { hasSeen }), actions.map { it.title.toString() })
            assertTrue(notification.actions.any { it.title == context.getString(R.string.notification_snooze_button) })
            val reply = actions.first()
            assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
            assertEquals(InlineNotifications.REPLY_TEXT, reply.remoteInputs.single().resultKey)
            assertTrue(reply.remoteInputs.single().allowFreeFormInput)
            assertTrue(reply.isAuthenticationRequired)
            assertFalse(reply.actionIntent.isImmutable)
            assertEquals(notification.actions.first().actionIntent, reply.actionIntent)
            if (hasSeen) assertEquals(notification.actions[1].actionIntent, actions[1].actionIntent)
            assertEquals(0, wearable(notification.publicVersion ?: Notification()).size)
        }
    }

    @Test
    fun callChoicesOnTheWatchUseTheSamePhoneReceiversAndOrder() {
        PushService.show(context, call()) { true }
        val notification = shown()
        val actions = wearable(notification)
        assertEquals(listOf("Continue", "Stop"), actions.map { it.title.toString() })
        assertEquals(notification.actions.map { it.actionIntent }, actions.map { it.actionIntent })
        assertTrue(actions.all { it.remoteInputs.isNullOrEmpty() && it.actionIntent.isImmutable })
        assertEquals(0, notification.flags and (Notification.FLAG_LOCAL_ONLY or Notification.FLAG_ONGOING_EVENT))
    }
}
