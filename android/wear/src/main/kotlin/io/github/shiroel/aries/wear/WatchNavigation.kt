package io.github.shiroel.aries.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import io.github.shiroel.aries.weardata.WatchAgent

/** One seam for Tile and watch-face entry points; a future Inbox can own the launcher. */
internal object WatchNavigation {
    fun intent(context: Context, agent: WatchAgent? = null): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, OpenOnPhoneActivity::class.java)).apply {
            flags = 0
            data = Uri.Builder().scheme("aries-wear").authority("open").apply {
                agent?.let {
                    appendQueryParameter("host", it.hostId)
                    appendQueryParameter("terminal", it.terminal)
                }
            }.build()
            agent?.let { putExtra("host", it.hostId); putExtra("terminal", it.terminal) }
        }

    fun pendingIntent(context: Context, agent: WatchAgent? = null): PendingIntent =
        PendingIntent.getActivity(context, 188, intent(context, agent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun clickable(context: Context, agent: WatchAgent? = null): Clickable {
        val intent = intent(context, agent)
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(context.packageName).setClassName(requireNotNull(intent.component).className)
        agent?.let {
            activity.addKeyToExtraMapping("host", ActionBuilders.stringExtra(it.hostId))
                .addKeyToExtraMapping("terminal", ActionBuilders.stringExtra(it.terminal))
        }
        return Clickable.Builder().setId(agent?.let { "${it.hostId}/${it.terminal}" } ?: "open-aries")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()).build()
    }
}
