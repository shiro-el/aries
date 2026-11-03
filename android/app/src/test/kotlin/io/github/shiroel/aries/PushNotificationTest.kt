package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What an opened Agent status push shows. */
@RunWith(RobolectricTestRunner::class)
class PushNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun status(terminal: String, name: String?, status: AgentStatus) =
        PushNotice("h1", "box", PushContent.Status(terminal, name, null, true, status, 1uL))

    private fun shown(): List<Notification> = manager.activeNotifications.map { it.notification }

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
