package io.github.shiroel.aries

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.activity.compose.setContent
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    // Regression: a resumed screen, even the caller's, must not silence urgent Calls; reply updates must stay silent.
    @Test
    @Config(application = InlineCoreApp::class)
    fun urgentCallsStillAlertWhileViewingTheirCallerButOrdinaryCallsAndReplyUpdatesStayQuiet() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            NotificationVisibility.from(context)!!.viewing(activity.get(), PushTarget("h1", "t1"))
            for ((id, urgency, channel) in listOf(
                Triple("check", CallUrgency.CHECK, "foreground_attention"),
                Triple("urgent", CallUrgency.URGENT, "calls_urgent"),
                Triple("emergency", CallUrgency.EMERGENCY, "calls_emergency"),
            )) {
                val call = PushContent.Call(id, "t1", true, "Review", "Please check", urgency, emptyList(), 1uL)
                PushService.show(context, PushNotice("h1", "box", call))
                if (urgency == CallUrgency.CHECK) {
                    assertTrue(manager.activeNotifications.none { it.tag == "call:$id" })
                } else {
                    val card = manager.activeNotifications.single { it.tag == "call:$id" }.notification
                    assertEquals(channel, card.channelId)
                    assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(card.channelId).importance)
                    assertEquals(android.app.Notification.GROUP_ALERT_ALL, card.groupAlertBehavior)
                    CallNotifications.post(context, CallNotice("h1", "box", call), CallView.Done("Delivered"))
                    assertEquals("notification_updates", manager.activeNotifications.single { it.tag == "call:$id" }.notification.channelId)
                }
            }
            NotificationVisibility.from(context)!!.viewing(activity.get(), null)
            PushService.show(context, PushNotice("h1", "box",
                PushContent.Call("other", "t2", true, "Review", "Please check", CallUrgency.CHECK, emptyList(), 1uL)))
            assertEquals("foreground_attention", manager.activeNotifications.single { it.tag == "call:other" }.notification.channelId)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun completionAndProgressStayInAppWhileResumedAndResumeNotifyingAfterPause() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            PushService.show(context, PushNotice("h1", "box", PushContent.Progress("t1", "Builder", 125uL, 3u, 10uL, false)))
            PushService.show(context, PushNotice("h1", "box", PushContent.Status("t1", "Builder", null, true, AgentStatus.Done, 11uL, null, null, null)))
            PushService.show(context, PushNotice("h1", "box", PushContent.Progress("t2", "Watched child", 125uL, 0u, 10uL, false)))
            PushService.show(context, PushNotice("h1", "box", PushContent.Status("t2", "Watched child", null, false, AgentStatus.Done, 11uL, null, null, null)))
            assertEquals(0, manager.activeNotifications.size)

            activity.pause().stop()
            // A delayed normal-priority Progress cannot resurrect the suppressed completion.
            PushService.show(context, PushNotice("h1", "box", PushContent.Progress("t1", "Builder", 125uL, 3u, 10uL, false)))
            PushService.show(context, PushNotice("h1", "box", PushContent.Progress("t2", "Watched child", 125uL, 0u, 10uL, false)))
            assertEquals(0, manager.activeNotifications.size)
            PushService.show(context, PushNotice("h1", "box", PushContent.Status("t1", "Builder", null, true, AgentStatus.Done, 12uL, null, null, null)))
            assertEquals("status_done", manager.activeNotifications.single { it.tag == "t1" }.notification.channelId)
        } finally {
            activity.destroy()
        }
    }
    // Regression: opening an Agent must retire its existing shade card only while the screen is resumed.
    @Test
    @Config(application = InlineCoreApp::class)
    fun viewingAnAgentRetiresItsStatusOnResumeButDoesNotRetireAnotherAgent() {
        PushService.show(context, PushNotice("h1", "box", PushContent.Status("t1", "Builder", null, true, AgentStatus.Error("Review"), 1uL, null, null, null)))
        PushService.show(context, PushNotice("h1", "box", PushContent.Status("t2", "Other", null, true, AgentStatus.Error("Other"), 1uL, null, null, null)))
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).create().start()
        try {
            activity.get().setContent { ObserveNotificationTarget(PushTarget("h1", "t1")) }
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            assertTrue(manager.activeNotifications.any { it.tag == "t1" })
            activity.resume().visible()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            assertTrue(manager.activeNotifications.none { it.tag == "t1" })
            assertTrue(manager.activeNotifications.any { it.tag == "t2" })
        } finally {
            activity.pause().stop().destroy()
        }
    }

}
