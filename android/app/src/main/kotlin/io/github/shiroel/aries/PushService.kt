package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushDevice
import io.github.shiroel.aries.core.PushNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives Relay pushes from FCM (high-priority data messages, also while
 * the app is not running or the phone is locked) and shows them. The
 * envelope is opened by the core with a key that needs no unlock.
 */
class PushService : FirebaseMessagingService() {
    private val core get() = (application as AriesApplication).core

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
        show(this, notice)
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
        private const val CHANNEL = "host_notifications"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun show(context: Context, notice: PushNotice) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_HIGH),
            )
            val (title, body) = when (notice.content) {
                is PushContent.Test -> context.getString(R.string.push_test_title, notice.hostName) to
                    context.getString(R.string.push_test_body, notice.hostName)
            }
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()
            manager.notify(notice.hostId.hashCode(), notification)
        }
    }
}
