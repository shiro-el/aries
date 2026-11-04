package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ProgressNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun progress(at: ULong = 10uL, cancelled: Boolean = false) = PushNotice("h1", "box",
        PushContent.Progress("t1", "Builder", 125uL, 3u, at, cancelled))
    private fun card() = manager.activeNotifications.single { it.tag == "t1" }.notification

    @Test @Config(sdk = [35], application = FakeCoreApp::class)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun olderAndroidShowsQuietOngoingProgressAndDoneReplacesTheSameTag() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        PushService.show(context, progress())
        val n = card()
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertEquals("2 min elapsed · Child Agents: 3", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(n.channelId).importance)
        assertNull(n.extras.getString(Notification.EXTRA_TEMPLATE))
        PushService.show(context, PushNotice("h1", "box", PushContent.Status("t1", "Builder", null, true, AgentStatus.Done, 11uL, null, null, null)))
        assertEquals(0, card().flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals("Done", card().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // Normal priority progress can arrive after a high priority completion.
        PushService.show(context, progress())
        assertEquals(0, card().flags and Notification.FLAG_ONGOING_EVENT)
        (context as FakeCoreApp).core.storedStates["h1"] = io.github.shiroel.aries.core.LastKnownState("h1",
            listOf(io.github.shiroel.aries.core.TerminalInfo("t2", null, "Fix login", emptyList(), null, "live")), 1uL)
        PushService.show(context, PushNotice("h1", "box", PushContent.Progress("t2", null, 125uL, 0u, 20uL, false)))
        renderNotification(context, manager.activeNotifications.single { it.tag == "t2" }.notification, "progress-no-children")
    }

    @Test @Config(sdk = [36])
    fun android16RequestsPromotedProgressAndCancellationRemovesIt() {
        PushService.show(context, progress())
        assertEquals("android.app.Notification\$ProgressStyle", card().extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(card().extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        // Robolectric's Android 16 preview uses the old colorized eligibility
        // rule. Assert the shipping contract without requesting colorization.
        assertNull(card().contentView)
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(permissions.orEmpty().contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
        PushService.show(context, progress(at = 11uL, cancelled = true))
        assertFalse(manager.activeNotifications.any { it.tag == "t1" })
        PushService.show(context, progress())
        assertFalse(manager.activeNotifications.any { it.tag == "t1" })
    }
}
