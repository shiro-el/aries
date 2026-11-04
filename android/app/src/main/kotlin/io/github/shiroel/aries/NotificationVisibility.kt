package io.github.shiroel.aries

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.platform.LocalContext

/** Process-local UI presence: FCM cold starts have no resumed Activities. */
internal class NotificationVisibility : Application.ActivityLifecycleCallbacks {
    private val resumed = mutableSetOf<Activity>()
    private val agents = mutableMapOf<Activity, PushTarget>()

    private fun Activity.canReplaceNotifications() = (application as? AriesApplication)?.appLock?.locked != true

    val foreground: Boolean
        @Synchronized get() = resumed.any { it.canReplaceNotifications() }

    @Synchronized
    fun viewing(activity: Activity, target: PushTarget?) {
        if (target == null) agents.remove(activity) else agents[activity] = target
    }

    @Synchronized
    fun isViewing(hostId: String, terminal: String?): Boolean =
        terminal != null && resumed.any { it.canReplaceNotifications() && agents[it] == PushTarget(hostId, terminal) }

    override fun onActivityResumed(activity: Activity) {
        synchronized(this) { resumed.add(activity) }
        if (activity.canReplaceNotifications()) NotificationGroups.foregrounded(activity)
    }

    @Synchronized
    override fun onActivityPaused(activity: Activity) { resumed.remove(activity) }

    @Synchronized
    override fun onActivityDestroyed(activity: Activity) {
        resumed.remove(activity)
        agents.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    companion object {
        const val PASSIVE = "aries.notification.passive"
        const val TERMINAL = "aries.notification.terminal"
        fun from(context: Context) = (context.applicationContext as? AriesApplication)?.notificationVisibility
    }
}

/** A disposed screen (including the app lock) must never hide an Agent's request. */
@Composable
internal fun ObserveNotificationTarget(target: PushTarget?) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val visibility = NotificationVisibility.from(context) ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(context, target, lifecycle) {
        if (target != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            NotificationGroups.viewing(context, target)
            awaitCancellation()
        }
    }
    DisposableEffect(visibility, activity, target) {
        visibility.viewing(activity, target)
        onDispose { visibility.viewing(activity, null) }
    }
}
