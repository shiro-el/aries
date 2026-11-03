package io.github.shiroel.aries

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.util.UUID

/** A notification generation, rather than just its reusable Host/Terminal tag. */
internal data class InlineTarget(
    val hostId: String,
    val hostName: String,
    val terminal: String,
    val tag: String,
    val turn: ULong?,
    val generation: String = UUID.randomUUID().toString(),
) {
    fun toJson(): String = JSONObject().put("host", hostId).put("name", hostName)
        .put("terminal", terminal).put("tag", tag).put("turn", turn?.toString())
        .put("generation", generation).toString()

    companion object {
        fun fromJson(json: String): InlineTarget? = try {
            val o = JSONObject(json)
            InlineTarget(o.getString("host"), o.getString("name"), o.getString("terminal"),
                o.getString("tag"), o.optString("turn").toULongOrNull(), o.getString("generation"))
        } catch (_: Exception) { null }
    }
}

/** Shared actions for status notifications and Calls without Choices. */
object InlineNotifications {
    internal const val TARGET = "inline_target"
    internal const val GENERATION = "inline_generation"
    const val REPLY_TEXT = "inline_reply_text"
    internal const val REPLY = "io.github.shiroel.aries.INLINE_REPLY"
    internal const val SEEN = "io.github.shiroel.aries.INLINE_SEEN"
    internal const val RESTORE = "io.github.shiroel.aries.INLINE_RESTORE"
    internal const val NOTIFICATION = "inline_notification"
    internal const val SNOOZE = "io.github.shiroel.aries.INLINE_SNOOZE"

    fun add(context: Context, builder: Notification.Builder, hostId: String, hostName: String,
        terminal: String, tag: String, reply: Boolean, turn: ULong? = null) {
        val target = InlineTarget(hostId, hostName, terminal, tag, turn)
        builder.addExtras(Bundle().apply { putString(GENERATION, target.generation) })
        if (reply) {
            val action = Notification.Action.Builder(null, context.getString(R.string.notification_reply_button), pending(context, target, REPLY, mutable = true))
                .addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel(context.getString(R.string.notification_reply_hint)).build())
                .setAllowGeneratedReplies(false)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            if (Build.VERSION.SDK_INT >= 31) action.setAuthenticationRequired(true)
            builder.addAction(action.build())
        }
        if (turn != null) builder.addAction(Notification.Action.Builder(null,
            context.getString(R.string.notification_seen_button), pending(context, target, SEEN)).build())
        builder.addAction(Notification.Action.Builder(null,
            context.getString(R.string.notification_snooze_button), pending(context, target, SNOOZE)).build())
    }

    internal fun current(context: Context, target: InlineTarget): Notification? =
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == target.hostId.hashCode() && it.tag == target.tag &&
                it.notification.extras.getString(GENERATION) == target.generation }?.notification

    internal fun update(context: Context, target: InlineTarget, line: String) {
        val original = current(context, target) ?: return
        val notification = Notification.Builder.recoverBuilder(context, original)
            .setActions(*emptyArray<Notification.Action>()).setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line)).setOnlyAlertOnce(true)
            .addExtras(Bundle().apply { putBoolean("inline_consumed", true) }).build().apply { actions = null }
        NotificationGroups.post(context, target.hostId, target.hostName, target.tag, notification)
    }

    internal fun dismiss(context: Context, target: InlineTarget) {
        if (current(context, target) == null) return
        NotificationGroups.dismissed(context, Intent().putExtra("host", target.hostId)
            .putExtra("hostName", target.hostName).putExtra("tag", target.tag))
    }

    internal fun snooze(context: Context, target: InlineTarget, notification: Notification) {
        val intent = Intent(context, InlineActionReceiver::class.java).setAction(RESTORE)
            .setData(Uri.Builder().scheme("aries-inline-snooze").authority(target.hostId).appendPath(target.tag).build())
            .putExtra(TARGET, target.toJson()).putExtra(NOTIFICATION, notification)
        val operation = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // A window starting in one hour: no exact-alarm permission or Doze exemption.
        context.getSystemService(android.app.AlarmManager::class.java).setWindow(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 3_600_000, 15 * 60_000L, operation)
        dismiss(context, target)
    }

    private fun pending(context: Context, target: InlineTarget, action: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, InlineActionReceiver::class.java).setAction(action)
            .setData(Uri.Builder().scheme("aries-inline").authority(target.hostId).appendPath(target.tag)
                .appendPath(target.generation).appendPath(action).build())
            .putExtra(TARGET, target.toJson())
        val flags = if (mutable) {
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        } else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

class InlineActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getStringExtra(InlineNotifications.TARGET)?.let(InlineTarget::fromJson) ?: return
        if (intent.action == InlineNotifications.RESTORE) {
            @Suppress("DEPRECATION")
            val notification = intent.getParcelableExtra<Notification>(InlineNotifications.NOTIFICATION) ?: return
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            // A reminder must not replace a newer card for the same Terminal/Call.
            if (manager.activeNotifications.none { it.id == target.hostId.hashCode() && it.tag == target.tag }) {
                NotificationGroups.post(context, target.hostId, target.hostName, target.tag, notification)
            }
            return
        }
        val original = InlineNotifications.current(context, target) ?: return
        if (original.extras.getBoolean("inline_consumed")) return
        val action = intent.action ?: return
        if (action == InlineNotifications.SNOOZE) {
            InlineNotifications.snooze(context, target, original)
            return
        }
        val text = if (action == InlineNotifications.REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(InlineNotifications.REPLY_TEXT)?.toString()
                ?.takeIf { it.isNotBlank() } ?: return
        } else null
        if (action != InlineNotifications.REPLY && action != InlineNotifications.SEEN) return
        if (!CallReplyWorker.online(context)) {
            InlineNotifications.update(context, target, context.getString(R.string.notification_action_offline))
            return
        }
        InlineNotifications.update(context, target, context.getString(
            if (action == InlineNotifications.REPLY) R.string.chat_sending else R.string.notification_marking_seen))
        InlineActionWorker.enqueue(context, target, action, text)
    }
}
