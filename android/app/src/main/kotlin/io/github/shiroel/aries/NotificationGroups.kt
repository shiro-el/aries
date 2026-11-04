package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.shiroel.aries.ui.ThemeSettings
import org.json.JSONObject
import java.util.UUID

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
    internal const val GENERATION = "aries.notification.generation"
    private const val FOREGROUND_CHANNEL = "foreground_attention"
    private const val UPDATE_CHANNEL = "notification_updates"
    private fun records(context: Context) = context.getSharedPreferences("notification_cards", Context.MODE_PRIVATE)
    private fun key(hostId: String, tag: String) = org.json.JSONArray(listOf(hostId, tag)).toString()
    private fun record(context: Context, hostId: String, tag: String): JSONObject? =
        records(context).getString(key(hostId, tag), null)?.let { JSONObject(it) }

    /** Persist identities even while snoozed or dismissed; absence from the shade is not freshness. */
    private fun remember(context: Context, hostId: String, tag: String, generation: String, hidden: Boolean, updated: Boolean = false) {
        records(context).edit().putString(key(hostId, tag), JSONObject()
            .put("generation", generation).put("hidden", hidden).put("updated", updated).toString()).apply()
    }

    /** A fresh Working update invalidates old status reminders, even when progress stays in-app. */
    @Synchronized
    internal fun advanced(context: Context, hostId: String, hostName: String, terminal: String, generation: String) {
        remember(context, hostId, terminal, generation, hidden = false)
        refresh(context, context.getSystemService(NotificationManager::class.java), hostId, hostName, excludedTag = terminal)
    }

    internal fun canRestore(context: Context, hostId: String, tag: String, notification: Notification): Boolean {
        val saved = record(context, hostId, tag) ?: return false
        return !saved.optBoolean("hidden") && !saved.optBoolean("resolved") &&
            saved.optString("generation") == notification.extras.getString(GENERATION)
    }

    @Synchronized
    fun answeredCall(context: Context, hostId: String, hostName: String, callId: String) {
        val tag = "call:$callId"
        val saved = record(context, hostId, tag) ?: JSONObject()
        saved.put("resolved", true).put("hidden", true)
        records(context).edit().putString(key(hostId, tag), saved.toString()).apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(tag, hostId.hashCode())
        refresh(context, manager, hostId, hostName)
    }

    /** Looking at an Agent retires its status card and any snoozed copy, without answering Calls. */
    @Synchronized
    fun viewing(context: Context, target: PushTarget) {
        val saved = record(context, target.hostId, target.terminal)
        if (saved != null) {
            saved.put("hidden", true)
            records(context).edit().putString(key(target.hostId, target.terminal), saved.toString()).apply()
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val current = manager.activeNotifications.find { it.id == target.hostId.hashCode() && it.tag == target.terminal }
        manager.cancel(target.terminal, target.hostId.hashCode())
        refresh(context, manager, target.hostId, current?.notification?.extras?.getString(NAME).orEmpty())
    }

    internal fun openIntent(context: Context, hostId: String, hostName: String, tag: String, terminal: String,
        chat: Boolean = false, generation: String? = null): Intent = PushTarget(hostId, terminal, chat).intent(context)
        .setData(Uri.Builder().scheme("aries-notification-open").authority(hostId).appendPath(tag)
            .apply { if (generation != null) appendPath(generation) }
            .apply { if (chat) appendQueryParameter("view", "chat") }.build())
        .putExtra(HOST, hostId).putExtra(NAME, hostName).putExtra(TAG, tag).putExtra(GENERATION, generation)

    internal fun openApp(context: Context, hostId: String, hostName: String, tag: String, terminal: String,
        chat: Boolean = false, generation: String? = null): PendingIntent = PendingIntent.getActivity(context, 0,
        openIntent(context, hostId, hostName, tag, terminal, chat, generation),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun decorate(context: Context, builder: Notification.Builder, hostId: String, hostName: String, tag: String, terminal: String,
        generation: String = UUID.randomUUID().toString()): Notification.Builder {
        val dismiss = Intent(context, NotificationDismissReceiver::class.java)
            .setData(Uri.Builder().scheme("aries-notification-dismiss").authority(hostId).appendPath(tag).appendPath(generation).build())
            .putExtra(HOST, hostId).putExtra(NAME, hostName).putExtra(TAG, tag).putExtra(GENERATION, generation)
        return builder.addExtras(android.os.Bundle().apply {
            putString(NotificationVisibility.TERMINAL, terminal)
            putString(GENERATION, generation)
            putString(NAME, hostName)
        }).setGroup(group(hostId)).setContentIntent(openApp(context, hostId, hostName, tag, terminal, generation = generation))
            .setDeleteIntent(PendingIntent.getBroadcast(context, 0, dismiss,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    @Synchronized
    fun post(context: Context, hostId: String, hostName: String, tag: String, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val generation = notification.extras.getString(GENERATION) ?: return
        val previous = record(context, hostId, tag)
        val update = notification.extras.getBoolean(InlineNotifications.ACTION_UPDATE)
        if (previous?.optBoolean("resolved") == true ||
            (previous?.optString("generation") == generation &&
                (previous.optBoolean("hidden") || (!update && previous.optBoolean("updated"))))) return
        remember(context, hostId, tag, generation, hidden = false, updated = update)
        val visibility = NotificationVisibility.from(context)
        val foreground = visibility?.foreground == true
        // Urgent Calls retain their channel's heads-up, sound and vibration even on the caller's screen.
        val urgentCall = tag.startsWith("call:") && notification.channelId in setOf("calls_urgent", "calls_emergency")
        if (foreground && !urgentCall && !update &&
            (notification.extras.getBoolean(NotificationVisibility.PASSIVE) ||
                visibility.isViewing(hostId, notification.extras.getString(NotificationVisibility.TERMINAL)))) {
            remember(context, hostId, tag, generation, hidden = true)
            manager.cancel(tag, hostId.hashCode())
            refresh(context, manager, hostId, hostName, excludedTag = tag)
            return
        }
        val card = if ((foreground && !urgentCall) || update) {
            val localized = AppLanguage.context(context)
            manager.createNotificationChannel(NotificationChannel(
                if (update) UPDATE_CHANNEL else FOREGROUND_CHANNEL,
                localized.getString(if (update) R.string.notification_updates_channel else R.string.push_channel_foreground), NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            })
            Notification.Builder.recoverBuilder(context, notification)
                .setChannelId(if (update) UPDATE_CHANNEL else FOREGROUND_CHANNEL)
                // Keep both the child and its GROUP_ALERT_CHILDREN summary silent.
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build()
        } else notification
        manager.notify(tag, hostId.hashCode(), WearNotificationBridge.prepare(context, card))
        refresh(context, manager, hostId, hostName)
    }

    private fun refresh(context: Context, manager: NotificationManager, hostId: String, hostName: String, excludedTag: String? = null) {
        val context = AppLanguage.context(context)
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
            .setContentTitle(context.getString(R.string.push_host_summary, hostName.ifEmpty { first.extras.getString(NAME).orEmpty() }, children.size))
            .setNumber(children.size)
            .setGroup(group(hostId))
            .setGroupSummary(true)
            // Each child owns its channel's alert behavior; a summary must never ring a second time.
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setContentIntent(PushTarget(hostId, "", agents = true).pendingIntent(context))
            .build()
        manager.notify(summaryTag(hostId), hostId.hashCode(), summary)
    }

    @Synchronized
    internal fun dismissed(context: Context, intent: Intent, snoozed: Boolean = false) {
        val hostId = intent.getStringExtra(HOST) ?: return
        val hostName = intent.getStringExtra(NAME) ?: return
        val tag = intent.getStringExtra(TAG) ?: return
        val saved = record(context, hostId, tag)
        val generation = intent.getStringExtra(GENERATION)
        if (generation != null && saved != null && generation != saved.optString("generation")) return
        if (!snoozed && saved != null) {
            saved.put("hidden", true)
            records(context).edit().putString(key(hostId, tag), saved.toString()).apply()
        }
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
