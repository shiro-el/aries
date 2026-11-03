package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.shiroel.aries.ui.ThemeSettings

/** Counts notification cards, never live Host state. No process-local counter can survive FCM restarts. */
object NotificationGroups {
    /** Called on cold and warm notification starts; ordinary launcher intents have no metadata. */
    fun opened(context: Context, intent: Intent) {
        if (PushTarget.from(intent) != null) dismissed(context, intent)
    }
    private fun group(hostId: String) = "host:$hostId"
    private fun summaryTag(hostId: String) = "host-summary:$hostId"
    private const val HOST = "host"
    private const val NAME = "hostName"
    private const val TAG = "tag"

    fun decorate(context: Context, builder: Notification.Builder, hostId: String, hostName: String, tag: String, terminal: String): Notification.Builder {
        val dismiss = Intent(context, NotificationDismissReceiver::class.java)
            .setData(Uri.Builder().scheme("aries-notification-dismiss").authority(hostId).appendPath(tag).build())
            .putExtra(HOST, hostId).putExtra(NAME, hostName).putExtra(TAG, tag)
        val open = PushTarget(hostId, terminal).intent(context)
            .setData(Uri.Builder().scheme("aries-notification-open").authority(hostId).appendPath(tag).build())
            .putExtra(HOST, hostId).putExtra(NAME, hostName).putExtra(TAG, tag)
        return builder.setGroup(group(hostId)).setContentIntent(PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )).setDeleteIntent(PendingIntent.getBroadcast(
            context, 0, dismiss, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
    }

    @Synchronized
    fun post(context: Context, hostId: String, hostName: String, tag: String, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(tag, hostId.hashCode(), notification)
        refresh(context, manager, hostId, hostName)
    }

    private fun refresh(context: Context, manager: NotificationManager, hostId: String, hostName: String, excludedTag: String? = null) {
        val children = manager.activeNotifications.filter {
            it.tag != excludedTag && it.notification.group == group(hostId) && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        if (children.isEmpty()) {
            manager.cancel(summaryTag(hostId), hostId.hashCode())
            return
        }
        val first = children.first().notification
        val summary = Notification.Builder(context, first.channelId)
            .setSmallIcon(R.drawable.ic_stat_aries)
            .setColor(ThemeSettings.from(context).accentArgb)
            .setContentTitle(context.getString(R.string.push_host_summary, hostName, children.size))
            .setNumber(children.size)
            .setGroup(group(hostId))
            .setGroupSummary(true)
            // Each child owns its channel's alert behavior; a summary must never ring a second time.
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setContentIntent(first.contentIntent)
            .build()
        manager.notify(summaryTag(hostId), hostId.hashCode(), summary)
    }

    @Synchronized
    internal fun dismissed(context: Context, intent: Intent) {
        val hostId = intent.getStringExtra(HOST) ?: return
        val hostName = intent.getStringExtra(NAME) ?: return
        val tag = intent.getStringExtra(TAG) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Cancel explicitly: the delete broadcast can race NotificationManager's active list.
        manager.cancel(tag, hostId.hashCode())
        refresh(context, manager, hostId, hostName, excludedTag = tag)
    }
}

/** A swipe updates the phone's summary only; it never marks an Agent seen on its Host. */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = NotificationGroups.dismissed(context, intent)
}
