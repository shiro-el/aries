package io.github.shiroel.aries

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Reuse the original channel for attention so Android keeps its user-locked settings. */
internal object StatusChannels {
    private const val ATTENTION = "host_notifications"
    private const val DONE = "status_done"

    fun channel(context: Context, manager: NotificationManager, done: Boolean): String {
        val legacy = manager.getNotificationChannel(ATTENTION)
        if (manager.getNotificationChannel(DONE) == null) {
            val channel = NotificationChannel(
                DONE, context.getString(R.string.push_channel_done),
                legacy?.importance ?: NotificationManager.IMPORTANCE_DEFAULT,
            )
            if (legacy != null) {
                channel.setSound(legacy.sound, legacy.audioAttributes)
                channel.vibrationPattern = legacy.vibrationPattern
                channel.enableVibration(legacy.shouldVibrate())
                channel.enableLights(legacy.shouldShowLights())
                channel.lightColor = legacy.lightColor
                channel.lockscreenVisibility = legacy.lockscreenVisibility
                channel.setBypassDnd(legacy.canBypassDnd())
                channel.setShowBadge(legacy.canShowBadge())
            }
            manager.createNotificationChannel(channel)
        }
        manager.createNotificationChannel(NotificationChannel(
            ATTENTION, context.getString(R.string.push_channel_attention), NotificationManager.IMPORTANCE_HIGH,
        ))
        return if (done) DONE else ATTENTION
    }
}
