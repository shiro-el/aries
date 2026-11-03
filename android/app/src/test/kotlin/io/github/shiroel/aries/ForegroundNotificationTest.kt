package io.github.shiroel.aries

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
