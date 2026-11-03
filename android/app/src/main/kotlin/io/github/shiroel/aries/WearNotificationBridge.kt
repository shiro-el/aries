package io.github.shiroel.aries

import android.app.Notification
import android.content.Context

/** Watch actions execute the phone's existing receivers, including OS RemoteInput replies. */
internal object WearNotificationBridge {
    const val PHONE_ONLY = "aries.wear.phone_only"

    fun prepare(context: Context, notification: Notification): Notification {
        // Rebuild on every post: recovered Sending/Done cards must not retain old Choices.
        val wearable = Notification.WearableExtender(notification)
            .clearActions()
            // Keep Snooze on the phone; do not filter by a localized title (a Choice may match it).
            .addActions(notification.actions.orEmpty().filterNot { it.extras.getBoolean(PHONE_ONLY) })
        // A recovered builder can merge its inherited extras back over an empty
        // extension. Encode on a fresh builder, then replace the extension on a
        // clone so clearing actions really clears them without rebuilding the card.
        val extensions = Notification.Builder(context, notification.channelId).extend(wearable).extras
        return notification.clone().apply { extras.putAll(extensions) }
    }
}
