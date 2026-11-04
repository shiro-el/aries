package io.github.shiroel.aries

import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.LastKnownState
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
    internal const val PHASE = "aries.notification.phase"
    internal const val AT_MS = "aries.notification.at_ms"
    internal const val TURN = "aries.notification.turn"
    internal const val QUESTION = "aries.notification.question"
    private const val RETENTION_MS = 7 * 24 * 60 * 60_000L
    private const val FOREGROUND_CHANNEL = "foreground_attention"
    private const val UPDATE_CHANNEL = "notification_updates"
    private fun records(context: Context) = context.getSharedPreferences("notification_cards", Context.MODE_PRIVATE)
    private fun key(hostId: String, tag: String) = org.json.JSONArray(listOf(hostId, tag)).toString()
    private fun record(context: Context, hostId: String, tag: String): JSONObject? =
        records(context).getString(key(hostId, tag), null)?.let { JSONObject(it) }

    /** Persist identities even while snoozed or dismissed; absence from the shade is not freshness. */
    private fun remember(context: Context, hostId: String, tag: String, generation: String, hidden: Boolean,
        updated: Boolean = false, notification: Notification? = null) {
        val saved = record(context, hostId, tag)?.takeIf { it.optString("generation") == generation } ?: JSONObject()
        if (!saved.has("postedAt")) saved.put("postedAt", System.currentTimeMillis())
        saved.put("generation", generation).put("hidden", hidden).put("updated", updated)
            .put("recordedAt", System.currentTimeMillis())
        notification?.extras?.let { extras ->
            if (extras.containsKey(PHASE)) saved.put("phase", extras.getString(PHASE))
                .put("at", extras.getLong(AT_MS)).put("turn", extras.getString(TURN))
                .put("question", extras.getString(QUESTION))
        }
        records(context).edit().putString(key(hostId, tag), saved.toString()).apply()
    }

    internal fun phase(status: AgentStatus?): String = when (status) {
        AgentStatus.Working -> "working"
        AgentStatus.Idle -> "idle"
        AgentStatus.Done -> "done"
        is AgentStatus.Blocked -> "blocked:${status.reason?.name}"
        is AgentStatus.Error -> "error"
        null -> "none"
    }

    /** Local storage observations only. Never opens another Host connection or Chat watch. */
    internal suspend fun reconcile(context: Context, core: AriesCoreInterface, states: List<LastKnownState>) {
        val byHost = states.associateBy { it.hostId }
        // Read encrypted Chat outside the notification monitor. Only a cache containing
        // this request can prove it ended; an older or absent cache cannot retract it.
        val questions = synchronized(this) {
            records(context).all.mapNotNull { (key, value) ->
                val identity = org.json.JSONArray(key)
                val saved = JSONObject(value as String)
                if (saved.optBoolean("hidden") || saved.optString("question").isEmpty()) null
                else Triple(identity.getString(0), identity.getString(1), saved.optString("question"))
            }
        }
        val ended = questions.filter { (host, terminal, id) ->
            val entries = try { core.cachedChat(host, terminal)?.entries.orEmpty() }
            catch (_: io.github.shiroel.aries.core.CoreException) { emptyList() }
            val index = entries.indexOfFirst { it.id == id }
            val pending = entries.drop(entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false } + 1)
                .filter {
                    val body = it.body
                    body is ChatEntryBody.Question && body.status == ChatToolStatus.RUNNING &&
                        (body.name == PERMISSION_TOOL) == id.startsWith(PERMISSION_ID_PREFIX)
                }
            // Codex consumes request_user_input entries FIFO, just like Chat and WatchPhone.
            val current = if ((pending.firstOrNull()?.body as? ChatEntryBody.Question)?.name == "request_user_input")
                pending.firstOrNull() else pending.lastOrNull()
            val lastUser = entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false }
            // A stale cache may still put q1 ahead of a newly pushed q2. Only
            // a final result or progress *after* this request proves retirement.
            index >= 0 && ((entries[index].body as? ChatEntryBody.Question)?.status != ChatToolStatus.RUNNING ||
                lastUser > index || (current != null && entries.indexOfFirst { it.id == current.id } > index))
        }.toSet()
        synchronized(this) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val editor = records(context).edit()
            val now = System.currentTimeMillis()
            records(context).all.forEach { (key, value) ->
                val identity = org.json.JSONArray(key)
                val host = identity.getString(0)
                val tag = identity.getString(1)
                val saved = JSONObject(value as String)
                val state = byHost[host]
                val terminal = state?.agents?.find { it.address == tag }
                // Upgrade the old generation-only status records, including snoozed cards.
                if (!saved.has("phase") && !tag.startsWith("call:") && !tag.startsWith("inline-recovery:")) {
                    val parts = saved.optString("generation").split('/', limit = 4)
                    if (parts.size == 4 && parts[0].toLongOrNull() != null) {
                        saved.put("at", parts[0].toLong() * 1000).put("turn", parts[1])
                            .put("phase", parts[2]).put("question", parts[3].takeUnless { it == "null" })
                    }
                }
                val changed = state != null && saved.has("phase") && state.fetchedAtMs >= maxOf(saved.optLong("at"), saved.optLong("postedAt")).toULong() &&
                    (terminal == null || terminal.state != "live" || phase(terminal.agentStatus) != saved.optString("phase") ||
                        saved.optString("turn").toULongOrNull()?.let { it != terminal.turn } == true ||
                        Triple(host, tag, saved.optString("question")) in ended)
                if (changed && !saved.optBoolean("recalled")) {
                    saved.put("hidden", true).put("recalled", true).put("recordedAt", now)
                    if (saved.optString("phase") == "working") {
                        // A late normal-priority duplicate must not resurrect recalled progress.
                        ProgressNotifications.completed(context, host, tag, (saved.optLong("at") / 1000).toULong())
                    }
                    manager.cancel(tag, host.hashCode()) // Android recalls the bridged watch card too.
                    refresh(context, manager, host, "")
                }
                if (!saved.has("recordedAt")) saved.put("recordedAt", now)
                if ((saved.optBoolean("hidden") || saved.optBoolean("resolved")) &&
                    now - saved.optLong("recordedAt") > RETENTION_MS) editor.remove(key)
                else if (saved.toString() != value) editor.putString(key, saved.toString())
            }
            editor.apply()
        }
        WatchProgressPhone.reconcile(context, states)
    }

    /** A fresh Working update invalidates old status reminders, even when progress stays in-app. */
    @Synchronized
    internal fun advanced(context: Context, hostId: String, hostName: String, terminal: String, generation: String) {
        remember(context, hostId, terminal, generation, hidden = false)
        val saved = record(context, hostId, terminal)!!
        saved.put("phase", "working").put("at", generation.removePrefix("progress:").toLong() * 1000)
        records(context).edit().putString(key(hostId, terminal), saved.toString()).apply()
        context.getSystemService(NotificationManager::class.java).cancel(terminal, hostId.hashCode())
        refresh(context, context.getSystemService(NotificationManager::class.java), hostId, hostName, excludedTag = terminal)
    }

    internal fun canRestore(context: Context, hostId: String, tag: String, notification: Notification): Boolean {
        val saved = record(context, hostId, tag) ?: return false
        return !saved.optBoolean("hidden") && !saved.optBoolean("resolved") &&
            saved.optString("generation") == notification.extras.getString(GENERATION)
    }

    @Synchronized
    fun answeredCall(context: Context, hostId: String, hostName: String, callId: String,
        state: CallAnswerState = CallAnswerState.DELIVERED) {
        if (state == CallAnswerState.CLAIMED) return
        if (record(context, hostId, "call:$callId")?.optBoolean("resolved") == true) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // The notification worker owns its in-flight result. A feed failure
        // must leave room for that result, while a swipe still wins.
        if (state != CallAnswerState.DELIVERED && manager.activeNotifications.any {
            it.id == hostId.hashCode() && it.tag == "call:$callId" &&
                it.notification.extras.getBoolean(InlineNotifications.ACTION_UPDATE)
        }) return
        val tag = "call:$callId"
        val saved = record(context, hostId, tag) ?: JSONObject()
        saved.put("resolved", true).put("hidden", true).put("recordedAt", System.currentTimeMillis())
        records(context).edit().putString(key(hostId, tag), saved.toString()).apply()
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
        remember(context, hostId, tag, generation, hidden = false, updated = update, notification = notification)
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
