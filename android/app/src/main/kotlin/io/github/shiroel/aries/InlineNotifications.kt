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
    internal const val ACTION_UPDATE = "inline_action_update"
    const val REPLY_TEXT = "inline_reply_text"
    internal const val REPLY = "io.github.shiroel.aries.INLINE_REPLY"
    internal const val SEEN = "io.github.shiroel.aries.INLINE_SEEN"
    internal const val RESTORE = "io.github.shiroel.aries.INLINE_RESTORE"
    internal const val NOTIFICATION = "inline_notification"
    internal const val SNOOZE = "io.github.shiroel.aries.INLINE_SNOOZE"

    fun add(context: Context, builder: Notification.Builder, hostId: String, hostName: String,
        terminal: String, tag: String, reply: Boolean, turn: ULong? = null) {
        add(context, builder, InlineTarget(hostId, hostName, terminal, tag, turn), reply)
    }

    private fun add(context: Context, builder: Notification.Builder, target: InlineTarget,
        reply: Boolean, draft: String? = null, snooze: Boolean = true) {
        builder.addExtras(Bundle().apply { putString(GENERATION, target.generation) })
        if (reply) {
            val input = RemoteInput.Builder(REPLY_TEXT).setLabel(context.getString(R.string.notification_reply_hint))
            if (draft != null) {
                input.setChoices(arrayOf(draft))
                if (Build.VERSION.SDK_INT >= 29) input.setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED)
            }
            val action = Notification.Action.Builder(null, context.getString(R.string.notification_reply_button), pending(context, target, REPLY, mutable = true))
                .addRemoteInput(input.build())
                .setAllowGeneratedReplies(false)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            if (Build.VERSION.SDK_INT >= 31) action.setAuthenticationRequired(true)
            builder.addAction(action.build())
        }
        if (target.turn != null) builder.addAction(Notification.Action.Builder(null,
            context.getString(R.string.notification_seen_button), pending(context, target, SEEN)).build())
        if (snooze) builder.addAction(Notification.Action.Builder(null,
            context.getString(R.string.notification_snooze_button), pending(context, target, SNOOZE))
            .addExtras(Bundle().apply { putBoolean(WearNotificationBridge.PHONE_ONLY, true) }).build())
    }

    internal fun current(context: Context, target: InlineTarget): Notification? =
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == target.hostId.hashCode() && it.tag == target.tag &&
                it.notification.extras.getString(GENERATION) == target.generation }?.notification

    internal fun update(context: Context, target: InlineTarget, line: String) = synchronized(NotificationGroups) {
        val original = current(context, target)?.clone()?.apply {
            actions = null
            extras = Bundle(extras).apply { putBoolean("inline_consumed", true); putBoolean(ACTION_UPDATE, true) }
        } ?: return@synchronized
        val notification = Notification.Builder.recoverBuilder(context, original)
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line)).setOnlyAlertOnce(true)
            .build()
        NotificationGroups.post(context, target.hostId, target.hostName, target.tag, notification)
    }

    /** Keep failed text on the private card, also offered as an editable Reply choice.
     * A fresh generation makes an explicit retry independent of the previous unique work.
     * Never replace a newer push: if the original vanished, give the recovery its own tag.
     */
    internal fun failed(context: Context, target: InlineTarget, line: String, text: String? = null,
        reply: Boolean = false, persistDraft: Boolean = true) = synchronized(NotificationGroups) {
        if (persistDraft && text != null) {
            try {
                (context.applicationContext as? HasCore)?.core?.let { core ->
                    io.github.shiroel.aries.ui.ChatReplyRecovery.recover(core,
                        io.github.shiroel.aries.ui.AgentKey(target.hostId, target.terminal),
                        io.github.shiroel.aries.ui.ChatReplyRecovery.message(target.generation, text, line, uncertain = false))
                }
            } catch (_: Exception) {
                // An unreadable journal is never replaced. The private recovery card still keeps the text.
            }
        }
        val original = current(context, target)?.clone()?.apply { actions = null }
        if (original == null && text == null) return@synchronized
        val retry = target.copy(tag = if (original == null) "inline-recovery:${target.generation}" else target.tag,
            generation = UUID.randomUUID().toString())
        // recoverBuilder merges inherited extras over addExtras on build. Change the
        // cloned notification too, so its old generation/consumed bit cannot win.
        original?.let {
            it.extras = Bundle(it.extras).apply {
                putString(GENERATION, retry.generation)
                putBoolean("inline_consumed", false)
                putBoolean(ACTION_UPDATE, true)
            }
        }
        val builder = if (original != null) Notification.Builder.recoverBuilder(context, original) else {
            val channel = "inline_actions"
            context.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
                android.app.NotificationChannel(channel, context.getString(R.string.call_notify_channel_sending),
                    android.app.NotificationManager.IMPORTANCE_LOW))
            val title = context.getString(R.string.push_status_title,
                NotificationAgentName.resolve(context, target.hostId, target.terminal), target.hostName)
            NotificationGroups.decorate(context, Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_aries).setContentTitle(title)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_aries)
                    .setContentTitle(title).setContentText(line).build()),
                target.hostId, target.hostName, retry.tag, target.terminal)
        }
        builder.setContentText(line).setStyle(Notification.BigTextStyle().bigText(
            if (text == null) line else "$line\n\n$text"))
            .setOnlyAlertOnce(true).addExtras(Bundle().apply {
                putBoolean("inline_consumed", false)
                putBoolean(ACTION_UPDATE, true)
            })
        // Snooze serializes action metadata in AlarmManager. Do not copy a draft there.
        add(context, builder, retry, reply, text, snooze = false)
        NotificationGroups.post(context, retry.hostId, retry.hostName, retry.tag, builder.build())
    }

    internal fun dismiss(context: Context, target: InlineTarget, snoozed: Boolean = false) = synchronized(NotificationGroups) {
        val notification = current(context, target) ?: return@synchronized
        NotificationGroups.dismissed(context, Intent().putExtra("host", target.hostId)
            .putExtra("hostName", target.hostName).putExtra("tag", target.tag)
            .putExtra(NotificationGroups.GENERATION, notification.extras.getString(NotificationGroups.GENERATION)), snoozed)
    }

    internal fun snooze(context: Context, target: InlineTarget, notification: Notification) {
        val intent = Intent(context, InlineActionReceiver::class.java).setAction(RESTORE)
            .setData(Uri.Builder().scheme("aries-inline-snooze").authority(target.hostId).appendPath(target.tag).build())
            .putExtra(TARGET, target.toJson()).putExtra(NOTIFICATION, NotificationPayloads.sealReminder(context, notification))
        val operation = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // A window starting in one hour: no exact-alarm permission or Doze exemption.
        // Scheduling is application-wide; locale wrappers are only needed for card text.
        context.applicationContext.getSystemService(android.app.AlarmManager::class.java).setWindow(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 3_600_000, 15 * 60_000L, operation)
        dismiss(context, target, snoozed = true)
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
    override fun onReceive(context: Context, intent: Intent) = synchronized(NotificationGroups) {
        receive(context, intent)
    }

    private fun receive(context: Context, intent: Intent) {
        val context = AppLanguage.context(context)
        val target = intent.getStringExtra(InlineNotifications.TARGET)?.let(InlineTarget::fromJson) ?: return
        if (intent.action == InlineNotifications.RESTORE) {
            @Suppress("DEPRECATION")
            val shell = intent.getParcelableExtra<Notification>(InlineNotifications.NOTIFICATION) ?: return
            val notification = NotificationPayloads.openReminder(context, shell) ?: return
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            // A reminder must not replace a newer card for the same Terminal/Call.
            synchronized(NotificationGroups) {
                if (NotificationGroups.canRestore(context, target.hostId, target.tag, notification) &&
                    manager.activeNotifications.none { it.id == target.hostId.hashCode() && it.tag == target.tag }) {
                    NotificationGroups.post(context, target.hostId, target.hostName, target.tag, notification)
                }
            }
            return
        }
        val original = InlineNotifications.current(context, target) ?: return
        if (original.extras.getBoolean("inline_consumed")) return
        val action = intent.action ?: return
        if (action == InlineNotifications.SNOOZE) {
            try {
                InlineNotifications.snooze(context, target, original)
            } catch (_: Exception) {
                InlineNotifications.failed(context, target, context.getString(R.string.notification_action_failed),
                    reply = original.actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() })
            }
            return
        }
        val text = if (action == InlineNotifications.REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(InlineNotifications.REPLY_TEXT)?.toString()
                ?.takeIf { it.isNotBlank() } ?: return
        } else null
        if (action != InlineNotifications.REPLY && action != InlineNotifications.SEEN) return
        if (!CallReplyWorker.online(context)) {
            InlineNotifications.failed(context, target, context.getString(R.string.notification_action_offline), text,
                reply = action == InlineNotifications.REPLY)
            return
        }
        InlineNotifications.update(context, target, context.getString(
            if (action == InlineNotifications.REPLY) R.string.chat_sending else R.string.notification_marking_seen))
        try {
            InlineActionWorker.enqueue(context, target, action, text)
        } catch (_: Exception) {
            // Encryption failure or WorkManager's 10 KiB limit must fail
            // visibly instead of crashing the receiver at Sending.
            InlineNotifications.failed(context, target, context.getString(R.string.notification_action_failed), text,
                reply = action == InlineNotifications.REPLY)
        }
    }
}
