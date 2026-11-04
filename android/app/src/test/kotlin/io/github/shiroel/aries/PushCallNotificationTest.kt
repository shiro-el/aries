package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.annotation.Config
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What an opened Call push shows, and on which channel. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
class PushCallNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun call(id: String, urgency: CallUrgency, body: String = "Tests pass.", choices: List<String> = listOf("Merge", "Wait")) =
        PushNotice("h1", "box", PushContent.Call(id, "t3", true, "Review ready", body, urgency, choices, 1uL))

    private fun shown(): List<Notification> = manager.activeNotifications.map { it.notification }.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun text(n: Notification, key: String) = n.extras.getCharSequence(key).toString()

    @Test
    fun aCallShowsItsTitleHostBodyAndChoices() {
        PushService.show(context, call("c1", CallUrgency.URGENT))

        val n = shown().single()
        assertEquals("Review ready", text(n, Notification.EXTRA_TITLE))
        assertEquals("Tests pass.", text(n, Notification.EXTRA_TEXT))
        assertEquals("box", text(n, Notification.EXTRA_SUB_TEXT))
        assertEquals(
            "Tests pass.\n" + context.getString(R.string.call_choices, "Merge / Wait"),
            text(n, Notification.EXTRA_BIG_TEXT),
        )
    }

    @Test
    fun eachUrgencyHasItsOwnChannelAndTheMoreUrgentInterruptMore() {
        PushService.show(context, call("c1", CallUrgency.CHECK))
        PushService.show(context, call("c2", CallUrgency.URGENT))
        PushService.show(context, call("c3", CallUrgency.EMERGENCY))

        val byId = manager.activeNotifications.associate { it.tag to it.notification }
        val channels = listOf("c1", "c2", "c3").map { byId.getValue("call:$it").channelId }
        assertEquals(3, channels.toSet().size)
        val importance = channels.map { manager.getNotificationChannel(it).importance }
        assertEquals(
            listOf(NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_HIGH),
            importance,
        )
        assertEquals(Notification.CATEGORY_ALARM, byId.getValue("call:c3").category)
        val names = channels.map { manager.getNotificationChannel(it).name.toString() }
        assertEquals(
            listOf(R.string.push_call_channel_check, R.string.push_call_channel_urgent, R.string.push_call_channel_emergency)
                .map { context.getString(it) },
            names,
        )
    }

    @Test
    fun eachCallIsItsOwnNotificationAndAnEmptyBodyShowsTheChoices() {
        PushService.show(context, call("c1", CallUrgency.CHECK, body = ""))
        PushService.show(context, call("c2", CallUrgency.CHECK))

        assertEquals(2, shown().size)
        assertTrue(shown().any { text(it, Notification.EXTRA_TEXT) == context.getString(R.string.call_choices, "Merge / Wait") })
    }
}
