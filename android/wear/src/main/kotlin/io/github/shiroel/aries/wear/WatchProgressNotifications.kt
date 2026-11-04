package io.github.shiroel.aries.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchProgress
import io.github.shiroel.aries.weardata.WatchSummary

internal object WatchProgressNotifications {
    private const val ID = 191
    private const val CHANNEL = "agent_progress"

    fun show(context: Context, progress: WatchProgress, summary: WatchSummary, remainingMs: Long, localStartedAtMs: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = progress.dataPath.hashCode()
        if (progress.cancelled || remainingMs == 0L) { clear(context, progress); return }
        manager.cancel(ID) // Replace the single-Agent notification from an older installation.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val localized = summary.localizedContext(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            localized.getString(R.string.wear_progress_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val agent = WatchAgent(progress.hostId, progress.terminal, progress.name, "working")
        val tap = WatchNavigation.pendingIntent(context, agent)
        val name = progress.name.ifBlank { progress.terminal }
        val status = Status.Builder()
            .addTemplate(localized.getString(R.string.wear_progress_status))
            .addPart("name", Status.TextPart(name))
            .addPart("time", Status.StopwatchPart(SystemClock.elapsedRealtime() -
                (System.currentTimeMillis() - localStartedAtMs).coerceAtLeast(0)))
            .addPart("children", Status.TextPart(progress.childAgents.toString()))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_aries_outline)
            .setContentTitle(name)
            .setContentText(localized.getString(R.string.wear_progress_body, progress.childAgents))
            .setContentIntent(tap)
            .setWhen(localStartedAtMs).setUsesChronometer(true)
            .setTimeoutAfter(remainingMs)
            .setOngoing(true).setLocalOnly(true).setSilent(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        OngoingActivity.Builder(context, id, builder)
            .setStaticIcon(R.drawable.ic_aries_outline).setTouchIntent(tap).setStatus(status)
            .build().apply(context)
        manager.notify(progress.dataPath, id, builder.build())
    }

    fun clear(context: Context, progress: WatchProgress) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(progress.dataPath, progress.dataPath.hashCode())
        manager.cancel(ID) // Retire the previous single-Agent notification after an upgrade.
    }
}
