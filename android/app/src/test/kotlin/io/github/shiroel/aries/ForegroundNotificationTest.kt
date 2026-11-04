package io.github.shiroel.aries

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.activity.compose.setContent
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import io.github.shiroel.aries.core.AgentStatus
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
