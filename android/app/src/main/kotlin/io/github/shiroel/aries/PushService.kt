package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.shiroel.aries.ui.ThemeSettings
import android.util.Base64
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushDevice
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.ui.agentStatusLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Receives Relay pushes from FCM (high-priority data messages, also while
 * the app is not running or the phone is locked) and shows them. The
 * envelope is opened by the core with a key that needs no unlock.
 */
class PushService : FirebaseMessagingService() {
    private val core get() = (application as HasCore).core

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["v"] != "1") return
        val envelope = try {
            Base64.decode(data["e"] ?: return, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return
        }
        val notice = try {
            core.openPush(envelope)
        } catch (e: CoreException) {
            // Not from a paired Host, tampered with, or a newer format: drop it.
            Log.w(TAG, "push rejected: ${e.message}")
            return
        }
        LastKnownStates.requestRefresh(this, notice.hostId)
        show(this, notice) { hostId ->
            try {
                core.canAnswerInBackground(hostId)
            } catch (e: CoreException) {
                false
            }
        }
    }

    override fun onNewToken(token: String) {
        scope.launch {
            try {
                core.updatePushToken(PushDevice("fcm", token), HttpRelayClient())
            } catch (e: CoreException) {
                Log.w(TAG, "push token update failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AriesPush"
        private const val CALL_CHECK = "calls_check"
        private const val CALL_URGENT = "calls_urgent"
        private const val CALL_EMERGENCY = "calls_emergency"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Shows [notice]. A Call gets its Choices as buttons when
         * [canAnswerInBackground] says its Host's key opens while the phone
         * is locked, otherwise an "Open app" button (ticket 36).
         */
        fun show(context: Context, notice: PushNotice, canAnswerInBackground: (hostId: String) -> Boolean = { false }) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val content = notice.content
            if (content is PushContent.Progress) {
                ProgressNotifications.post(context, notice, content)
                return
            }
            if (content is PushContent.Status && !ProgressNotifications.acceptStatus(context, notice.hostId, content.terminal, content.at)) return
            // A child explicitly watched by the user can complete its local card.
            val wasWatched = content is PushContent.Status && manager.activeNotifications.any {
                it.id == notice.hostId.hashCode() && it.tag == content.terminal && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            }
            // Sub-agents report to their parent, even if an older Beacon pushes them.
            if (content is PushContent.Status && !content.topLevel && !wasWatched) return
            if (content is PushContent.Call && !content.topLevel) return
            if (content is PushContent.Call) {
                val call = CallNotice(notice.hostId, notice.hostName, content)
                val view = when {
                    content.choices.isEmpty() -> CallView.Plain
                    CallNotifications.fitsAsButtons(content) && canAnswerInBackground(call.hostId) -> CallView.Choices
                    else -> CallView.OpenApp
                }
                CallNotifications.post(context, call, view)
                return
            }
            val channel = StatusChannels.channel(context, manager, content is PushContent.Status && content.status == AgentStatus.Done)
            val (title, body) = when (content) {
                is PushContent.Test -> context.getString(R.string.push_test_title, notice.hostName) to
                    context.getString(R.string.push_test_body, notice.hostName)
                is PushContent.Status -> {
                    val agent = content.name ?: content.title ?: content.terminal
                    val label = context.getString(agentStatusLabel(content.status))
                    val message = when (val s = content.status) {
                        is AgentStatus.Blocked -> s.message
                        is AgentStatus.Error -> s.message
                        else -> null
                    }
                    context.getString(R.string.push_status_title, agent, notice.hostName) to
                        (message?.let { context.getString(R.string.push_status_body, label, it) } ?: label)
                }
                is PushContent.Progress -> return // handled above
                is PushContent.Call -> return // shown by showCall
            }
            val builder = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_aries)
                .setColor(ThemeSettings.from(context).accentArgb)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
            // One notification per Agent: its newer status replaces the older.
            if (content is PushContent.Status) {
                QuestionNotifications.decorate(context, notice, content, builder, canAnswerInBackground)
                ProgressNotifications.completed(context, notice.hostId, content.terminal, content.at)
                val tag = content.terminal
                NotificationGroups.decorate(context, builder, notice.hostId, notice.hostName, tag, content.terminal)
                val status = content.status
                if ((status == AgentStatus.Done || status is AgentStatus.Blocked || status is AgentStatus.Error) &&
                    QuietHoursSettings.from(context).hours.contains(LocalTime.now())
                ) {
                    // Android suppresses sound AND vibration even on an audible channel.
                    // The summary independently uses GROUP_ALERT_CHILDREN, so neither rings.
                    builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                }
                NotificationGroups.post(context, notice.hostId, notice.hostName, tag, builder.build())
            } else {
                manager.notify(null, notice.hostId.hashCode(), builder.build())
            }
        }

        /**
         * One channel per urgency, so the user can tune each in Android
         * settings: Check is a normal notification, Urgent and Emergency
         * pop up, Emergency is also an alarm. `record` Calls are never
         * pushed; one that arrives anyway goes to Check.
         */
        private fun callChannel(context: Context, manager: NotificationManager, urgency: CallUrgency): String {
            val (id, name, importance) = when (urgency) {
                CallUrgency.RECORD, CallUrgency.CHECK ->
                    Triple(CALL_CHECK, R.string.push_call_channel_check, NotificationManager.IMPORTANCE_DEFAULT)
                CallUrgency.URGENT ->
                    Triple(CALL_URGENT, R.string.push_call_channel_urgent, NotificationManager.IMPORTANCE_HIGH)
                CallUrgency.EMERGENCY ->
                    Triple(CALL_EMERGENCY, R.string.push_call_channel_emergency, NotificationManager.IMPORTANCE_HIGH)
            }
            val channel = NotificationChannel(id, context.getString(name), importance)
            if (urgency == CallUrgency.EMERGENCY) {
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                // Honoured only if the user lets Aries override Do Not Disturb.
                channel.setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
            return id
        }

        /** A Call's notification without buttons; [CallNotifications] adds them and posts it. */
        internal fun callBuilder(context: Context, manager: NotificationManager, hostId: String, hostName: String, call: PushContent.Call): Notification.Builder {
            val choices = call.choices.takeIf { it.isNotEmpty() }
                ?.let { context.getString(R.string.call_choices, it.joinToString(" / ")) }
            val text = call.body.ifEmpty { choices ?: "" }
            val big = listOfNotNull(call.body.ifEmpty { null }, choices).joinToString("\n")
            val builder = Notification.Builder(context, callChannel(context, manager, call.urgency))
                .setSmallIcon(R.drawable.ic_stat_aries)
                .setColor(ThemeSettings.from(context).accentArgb)
                .setContentTitle(call.title)
                .setContentText(text)
                .setSubText(hostName)
                .setStyle(Notification.BigTextStyle().bigText(big))
                .setWhen(call.atMs.toLong())
                .setShowWhen(true)
                .setAutoCancel(true)
            NotificationGroups.decorate(context, builder, hostId, hostName, "call:${call.id}", call.terminal)
            if (call.urgency == CallUrgency.EMERGENCY) builder.setCategory(Notification.CATEGORY_ALARM)
            return builder
        }
    }
}
