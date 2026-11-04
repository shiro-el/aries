package io.github.shiroel.aries

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.shiroel.aries.weardata.OpenWatchAgent

/**
 * What tapping an Agent notification opens: the Terminal [terminal] (whole
 * address, `machine/tN` included) on the Host [hostId]. Travels to
 * [MainActivity] as the notification's content intent.
 */
data class PushTarget(val hostId: String, val terminal: String, val chat: Boolean = false) {
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION)
        // Distinct data per Agent, so each notification keeps its own PendingIntent.
        .setData(Uri.Builder().scheme("aries-push").authority(hostId).appendPath(terminal)
            .apply { if (chat) appendQueryParameter("view", "chat") }.build())
        .putExtra(EXTRA_HOST, hostId)
        .putExtra(EXTRA_TERMINAL, terminal)
        .putExtra(EXTRA_CHAT, chat)
        // Warm start: the running activity gets it in onNewIntent.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, (hostId + "\u0000" + terminal).hashCode(), intent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION = "io.github.shiroel.aries.OPEN_AGENT_TERMINAL"
        private const val EXTRA_CHAT = "open_chat"
        private const val EXTRA_HOST = "host_id"
        private const val EXTRA_TERMINAL = "terminal"

        /** The target of a tapped notification, or null (a launcher start, or a relaunch from Recents). */
        fun from(intent: Intent?): PushTarget? {
            if (intent == null) return null
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            OpenWatchAgent.fromIntent(intent)?.let { return PushTarget(it.hostId, it.terminal, it.chat) }
            if (intent.action != ACTION) return null
            val host = intent.getStringExtra(EXTRA_HOST) ?: return null
            val terminal = intent.getStringExtra(EXTRA_TERMINAL) ?: return null
            return PushTarget(host, terminal, intent.getBooleanExtra(EXTRA_CHAT, false))
        }
    }
}
