package io.github.shiroel.aries.wear

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.ongoing.OngoingActivity
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchProgress
import io.github.shiroel.aries.weardata.WatchSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WatchProgressTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    @Before fun allowNotifications() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun progress(at: Long = 100_000, cancelled: Boolean = false, terminal: String = "remote/t7") =
        WatchProgress("host", terminal, "Builder", 50_000, 2, at, cancelled)

    @Test fun newerNonWorkingSnapshotRetiresOngoingEvenOutsideTheTopThree() {
        val store = WatchStateStore(context)
        for ((index, status) in listOf("done", "blocked", "error", "idle").withIndex()) {
            val at = 100_000L + index * 10_000
            assertTrue(store.acceptProgress("phone", progress(at).toRequest().data))
            val notification = manager.activeNotifications.single().notification
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
            assertNotNull(OngoingActivity.recoverOngoingActivity(context))
            val channel = manager.getNotificationChannel(notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertFalse(channel.shouldVibrate())
            assertNull(channel.sound)
            val agent = WatchAgent("host", "remote/t7", "Builder", status)
            // The full Inbox, not just the Tile's three-Agent projection, owns status.
            assertTrue(store.accept("phone", WatchSummary(0, emptyList(), at + 1_000, inbox = listOf(agent)).toRequest().data))
            assertTrue(manager.activeNotifications.isEmpty())
        }
    }

    @Test fun completionAndDeletionSurviveRestartWithoutDelayedProgressResurrectingTheActivity() {
        val store = WatchStateStore(context)
        assertTrue(store.acceptProgress("phone", progress().toRequest().data))
        // Cached snapshots from before the push cannot prematurely retire it.
        store.accept("phone", WatchSummary(0, emptyList(), 90_000).toRequest().data)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(store.acceptProgress("phone", progress(110_000, cancelled = true).toRequest().data))
        assertTrue(manager.activeNotifications.isEmpty())
        val restarted = WatchStateStore(context)
        assertFalse(restarted.acceptProgress("phone", progress().toRequest().data))
        assertFalse(restarted.acceptProgress("phone", progress(110_000).toRequest().data))
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(restarted.acceptProgress("phone", progress(120_000, terminal = "remote/t8").toRequest().data))
        assertEquals(1, manager.activeNotifications.size)
        val tap = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals("host", tap.getStringExtra("host"))
        assertEquals("remote/t8", tap.getStringExtra("terminal"))
        restarted.clearProgress("other-phone")
        assertEquals(1, manager.activeNotifications.size)
        restarted.clearProgress("phone")
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(WatchStateStore(context).acceptProgress("phone", progress(120_000, terminal = "remote/t8").toRequest().data))
    }
}
