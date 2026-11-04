package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.os.Build
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice

/**
 * One quiet local card per Watched Agent, replaced by its ordinary status tag.
 * Wear OS excludes ongoing notifications from bridging; localOnly also makes
 * that boundary explicit. Its later dismissible Done card can bridge normally.
 */
internal object ProgressNotifications {
    private const val CHANNEL = "agent_progress"
    private fun stampKey(host: String, terminal: String) = "$host/$terminal"
    private fun stamps(context: Context) = context.getSharedPreferences("progress_completed", Context.MODE_PRIVATE)

    /** Persist completion watermarks: normal FCM can arrive after high-priority Done. */
    fun acceptStatus(context: Context, host: String, terminal: String, at: ULong): Boolean {
        val old = stamps(context).getLong(stampKey(host, terminal), 0).toULong()
        if (at < old) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val progress = manager.activeNotifications.find { it.id == host.hashCode() && it.tag == terminal && it.notification.channelId == CHANNEL }
        return progress == null || at >= (progress.notification.extras.getLong(NotificationGroups.AT_MS, progress.notification.`when`) / 1000).toULong()
    }

    fun completed(context: Context, host: String, terminal: String, at: ULong) {
        val prefs = stamps(context)
        val key = stampKey(host, terminal)
        val old = prefs.getLong(key, 0).toULong()
        if (at >= old) prefs.edit().putLong(key, at.toLong()).apply()
        WatchProgressPhone.completed(context, host, terminal, at)
    }

    fun post(context: Context, notice: PushNotice, p: PushContent.Progress) = synchronized(NotificationGroups) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (p.at <= stamps(context).getLong(stampKey(notice.hostId, p.terminal), 0).toULong()) return
        val existing = manager.activeNotifications.find { it.id == notice.hostId.hashCode() && it.tag == p.terminal }
        if (existing?.notification?.channelId == CHANNEL && p.at.toLong() < existing.notification.extras.getLong(NotificationGroups.AT_MS, existing.notification.`when`) / 1000) return
        if (p.cancelled) {
            completed(context, notice.hostId, p.terminal, p.at)
            // A delayed cleanup must not erase a newer attention or Done card.
            if (existing == null || existing.notification.channelId == CHANNEL) {
                NotificationGroups.advanced(context, notice.hostId, notice.hostName, p.terminal, "progress:${p.at}")
            }
            if (existing?.notification?.channelId == CHANNEL) manager.cancel(p.terminal, notice.hostId.hashCode())
            return
        }
        NotificationGroups.advanced(context, notice.hostId, notice.hostName, p.terminal, "progress:${p.at}")
        WatchProgressPhone.progress(context, notice.hostId, p)
        if (NotificationVisibility.from(context)?.foreground == true) {
            if (existing?.notification?.channelId == CHANNEL) manager.cancel(p.terminal, notice.hostId.hashCode())
            return
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.push_progress_channel), NotificationManager.IMPORTANCE_LOW))
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aries)
            .setContentTitle(context.getString(R.string.push_status_title, NotificationAgentName.resolve(context, notice.hostId, p.terminal, p.name), notice.hostName))
            .setContentText(if (p.childAgents == 0u) context.getString(R.string.push_progress_elapsed, (p.elapsedSecs / 60uL).toLong())
                else context.getString(R.string.push_progress_body, (p.elapsedSecs / 60uL).toLong(), p.childAgents.toLong()))
            .addExtras(Bundle().apply { putLong(NotificationGroups.AT_MS, p.at.toLong() * 1000) })
            .setWhen((p.at - p.elapsedSecs.coerceAtMost(p.at)).toLong() * 1000)
            .setUsesChronometer(true)
            .setTimeoutAfter(io.github.shiroel.aries.weardata.WatchProgress.MAX_AGE_MS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setContentIntent(PendingIntent.getActivity(context, p.terminal.hashCode(), PushTarget(notice.hostId, p.terminal).intent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            // The platform method starts at 36.1; 36.0 uses the same public extra.
            if (Build.VERSION.SDK_INT_FULL >= 3_600_001) {
                builder.setRequestPromotedOngoing(true)
            } else {
                builder.addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            }
        } else {
            builder.setProgress(0, 0, true)
        }
        manager.notify(p.terminal, notice.hostId.hashCode(), builder.build())
    }
}
