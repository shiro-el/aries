package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.ui.ThemeSettings
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What an opened Agent status push shows. */
@RunWith(RobolectricTestRunner::class)
class PushNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun status(terminal: String, name: String?, status: AgentStatus) =
        PushNotice("h1", "box", PushContent.Status(terminal, name, null, true, status, 1uL))

    private fun shown(): List<Notification> = manager.activeNotifications.map { it.notification }.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    @Test
    fun openingANotificationUpdatesTheSummaryAndPreservesItsAgentTarget() {
        PushService.show(context, status("t1", null, AgentStatus.Done))
        PushService.show(context, status("t2", null, AgentStatus.Done))
        val first = manager.activeNotifications.single { it.tag == "t1" }.notification
        val intent = shadowOf(first.contentIntent).savedIntent
        assertEquals(PushTarget("h1", "t1"), PushTarget.from(intent))
        NotificationGroups.opened(context, intent)
        val summary = manager.activeNotifications.single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.notification
        assertEquals("box: 1 waiting", summary.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val last = manager.activeNotifications.single { it.tag == "t2" }.notification
        NotificationGroups.opened(context, shadowOf(last.contentIntent).savedIntent)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun quietHoursSilenceStatusCardsAndSummariesWithoutMutatingChannelsOrEmergencyCalls() {
        assertEquals(false, QuietHoursSettings.from(context).hours.enabled)
        QuietHoursSettings.from(context).update(QuietHours(true, 0, 0))
        PushService.show(context, status("t1", null, AgentStatus.Done))
        PushService.show(context, status("t2", null, AgentStatus.Blocked(null, null)))
        PushService.show(context, status("t3", null, AgentStatus.Error(null)))
        val statuses = manager.activeNotifications.filter { it.tag in setOf("t1", "t2", "t3") }.map { it.notification }
        statuses.forEach { assertEquals(Notification.GROUP_ALERT_SUMMARY, it.groupAlertBehavior) }
        val channelIds = statuses.map { it.channelId }
        val sounds = channelIds.map { manager.getNotificationChannel(it).sound }
        PushService.show(context, PushNotice("h1", "box", PushContent.Call("emergency", "t1", true, "Help", "", CallUrgency.EMERGENCY, emptyList(), 1uL)))
        val emergency = manager.activeNotifications.single { it.tag == "call:emergency" }.notification
        assertEquals("calls_emergency", emergency.channelId)
        assertEquals(Notification.GROUP_ALERT_ALL, emergency.groupAlertBehavior)
        assertEquals(Notification.CATEGORY_ALARM, emergency.category)
        val summary = manager.activeNotifications.single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.notification
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        QuietHoursSettings.from(context).update(QuietHours(false, 0, 0))
        PushService.show(context, status("t1", null, AgentStatus.Done))
        val daytime = manager.activeNotifications.single { it.tag == "t1" }.notification
        assertEquals(Notification.GROUP_ALERT_ALL, daytime.groupAlertBehavior)
        assertEquals(channelIds.first(), daytime.channelId)
        assertEquals(sounds, channelIds.map { manager.getNotificationChannel(it).sound })
    }

    @Test
    fun swipingChildrenUpdatesTheSummaryAndRemovesItWithTheLastCard() {
        PushService.show(context, status("t1", null, AgentStatus.Done))
        PushService.show(context, status("t2", null, AgentStatus.Done))
        manager.activeNotifications.single { it.tag == "t1" }.notification.deleteIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val summary = manager.activeNotifications.single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.notification
        assertEquals("box: 1 waiting", summary.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        manager.activeNotifications.single { it.tag == "t2" }.notification.deleteIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun doneChannelPreferencesAreNotOverwrittenOnLaterPosts() {
        val done = android.app.NotificationChannel("status_done", "Custom", NotificationManager.IMPORTANCE_NONE)
        manager.createNotificationChannel(done)
        PushService.show(context, status("t1", null, AgentStatus.Done))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("status_done").importance)
    }

    @Test
    fun hostsGetSeparateSummariesAndReplacingAnAgentDoesNotIncreaseTheCount() {
        PushService.show(context, status("t1", "one", AgentStatus.Done))
        PushService.show(context, status("t2", "two", AgentStatus.Error(null)))
        PushService.show(context, status("t1", "one", AgentStatus.Blocked(null, null)))
        PushService.show(context, PushNotice("h2", "other", PushContent.Status("t1", null, null, true, AgentStatus.Done, 1uL)))
        val all = manager.activeNotifications.map { it.notification }
        val summaries = all.filter { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals(2, summaries.size)
        assertEquals(setOf("box: 2 waiting", "other: 1 waiting"), summaries.map {
            it.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }.toSet())
        assertEquals(2, all.map { it.group }.toSet().size)
        summaries.forEach { summary ->
            assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
            assertEquals(true, all.any { it !== summary && it.group == summary.group })
        }
    }

    @Test
    fun doneAndAttentionHaveSeparateChannelsAndKeepLegacyPreferences() {
        val legacy = android.app.NotificationChannel("host_notifications", "Old", NotificationManager.IMPORTANCE_LOW)
        legacy.setSound(null, null)
        legacy.enableVibration(false)
        legacy.lockscreenVisibility = Notification.VISIBILITY_SECRET
        manager.createNotificationChannel(legacy)
        PushService.show(context, status("t1", "done", AgentStatus.Done))
        PushService.show(context, status("t2", "blocked", AgentStatus.Blocked(null, null)))
        PushService.show(context, status("t3", "error", AgentStatus.Error(null)))
        val byTag = manager.activeNotifications.associate { it.tag to it.notification }
        val done = manager.getNotificationChannel(byTag.getValue("t1").channelId)
        val blocked = manager.getNotificationChannel(byTag.getValue("t2").channelId)
        assertEquals(false, done.id == blocked.id)
        assertEquals(blocked.id, byTag.getValue("t3").channelId)
        for (channel in listOf(done, blocked)) {
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound)
            assertEquals(false, channel.shouldVibrate())
            assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
        }
    }

    @Test
    fun subAgentStatusAndCallsNeverNotifyTheHuman() {
        PushService.show(context, PushNotice("h1", "box", PushContent.Status("t2", "child", null, false, AgentStatus.Error("parent only"), 1uL)))
        PushService.show(context, PushNotice("h1", "box", PushContent.Call("child-call", "t2", false, "Report", "", CallUrgency.URGENT, listOf("Continue"), 1uL)))
        assertEquals(0, shown().size)
    }

    @Test
    fun statusAndCallNotificationsUseTheBrandMarkAndSavedAccentEvenWithoutAnActivity() {
        ThemeSettings.from(context).setAccent("#3FB68B")
        PushService.show(context, status("t3", "claude", AgentStatus.Done))
        PushService.show(context, PushNotice("h1", "box", PushContent.Call("c1", "t3", true, "Review", "", CallUrgency.CHECK, emptyList(), 1uL)))

        assertEquals(2, shown().size)
        for (notification in shown()) {
            assertEquals(R.drawable.ic_stat_aries, notification.smallIcon.resId)
            assertEquals(android.graphics.Color.rgb(63, 182, 139), notification.color)
        }
    }

    @Test
    fun aBlockedAgentShowsItsReasonAndMessage() {
        PushService.show(context, status("t3", "claude", AgentStatus.Blocked(BlockedReason.PERMISSION, "Allow Bash?")))

        val n = shown().single()
        assertEquals(
            context.getString(R.string.push_status_title, "claude", "box"),
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(
            context.getString(R.string.push_status_body, context.getString(R.string.agent_status_blocked_permission), "Allow Bash?"),
            n.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun tappingAnAgentNotificationOpensItsTerminalOnItsHost() {
        PushService.show(context, status("box/t3", "claude", AgentStatus.Blocked(null, null)))
        PushService.show(context, status("t3", "codex", AgentStatus.Error(null)))

        val targets = shown().map { n ->
            val intent = shadowOf(n.contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            PushTarget.from(intent)
        }.toSet()
        assertEquals(setOf(PushTarget("h1", "box/t3"), PushTarget("h1", "t3")), targets)
    }

    @Test
    fun tappingACallNotificationOpensTheTerminalThatCalled() {
        PushService.show(context, PushNotice("h1", "box", PushContent.Call("c1", "box/t2", true, "Review ready", "", CallUrgency.CHECK, emptyList(), 1uL)))

        val n = shown().single()
        assertEquals(PushTarget("h1", "box/t2"), PushTarget.from(shadowOf(n.contentIntent).savedIntent))
    }

    @Test
    fun onlyATappedNotificationCarriesATarget() {
        val tapped = PushTarget("h1", "box/t3").intent(context)
        assertEquals(PushTarget("h1", "box/t3"), PushTarget.from(tapped))
        assertNull("a launcher start", PushTarget.from(Intent(Intent.ACTION_MAIN)))
        assertNull("reopened from Recents", PushTarget.from(Intent(tapped).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }

    @Test
    fun eachAgentHasItsOwnNotificationAndANewStatusReplacesIt() {
        PushService.show(context, status("t3", "claude", AgentStatus.Blocked(null, null)))
        PushService.show(context, status("t4", null, AgentStatus.Error(null)))
        PushService.show(context, status("t3", "claude", AgentStatus.Error(null)))

        val texts = shown().map { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }
        assertEquals(listOf(context.getString(R.string.agent_status_error)), texts.distinct())
        assertEquals(2, texts.size)
        val titles = shown().map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }.toSet()
        assertEquals(setOf(context.getString(R.string.push_status_title, "claude", "box"), context.getString(R.string.push_status_title, "t4", "box")), titles)
    }
}
