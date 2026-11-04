package io.github.shiroel.aries

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.util.Base64
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.KeyVault
import org.json.JSONObject

/** Injectable platform boundary; production always uses a device Keystore key. */
internal interface HasNotificationVault {
    val notificationVault: KeyVault
}

/** No plaintext fallback: old intents/work must never be interpreted as sealed content. */
internal object NotificationPayloads {
    private const val PREFIX = "sealed-v1:"
    private val deviceVault by lazy { KeystoreVault("aries-notifications") }
    private fun vault(context: Context) =
        (context.applicationContext as? HasNotificationVault)?.notificationVault ?: deviceVault

    fun seal(context: Context, text: String): String = PREFIX + Base64.encodeToString(
        vault(context).seal(text.toByteArray(Charsets.UTF_8), KeyProtection.PLAIN), Base64.NO_WRAP)

    fun open(context: Context, sealed: String?): String? = try {
        if (sealed == null || !sealed.startsWith(PREFIX)) null
        else vault(context).open(Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP), KeyProtection.PLAIN)
            .toString(Charsets.UTF_8)
    } catch (_: Exception) { null }

    private const val REMINDER = "aries.sealed-reminder"
    private val messageFields = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG,
        Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT,
        Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT)

    /** Snooze only handles status/choice-less Call cards. Keep their generic
     * actions and routing intact, but never give AlarmManager a second message.
     * App notifications use standard templates, with no custom RemoteViews.
     */
    fun sealReminder(context: Context, notification: Notification): Notification {
        val content = JSONObject()
        val shell = notification.clone()
        shell.extras = Bundle(notification.extras)
        for (field in messageFields) {
            shell.extras.getCharSequence(field)?.let { content.put(field, it.toString()) }
            shell.extras.remove(field)
        }
        shell.tickerText = null
        shell.contentView = null
        shell.bigContentView = null
        shell.headsUpContentView = null
        shell.extras.putString(REMINDER, seal(context, content.toString()))
        return shell
    }

    fun openReminder(context: Context, shell: Notification): Notification? = try {
        val content = open(context, shell.extras.getString(REMINDER))?.let(::JSONObject)
        if (content == null) null else shell.clone().apply {
            extras = Bundle(shell.extras).apply {
                remove(REMINDER)
                for (field in messageFields) if (content.has(field)) putString(field, content.getString(field))
            }
        }
    } catch (_: Exception) { null }
}
