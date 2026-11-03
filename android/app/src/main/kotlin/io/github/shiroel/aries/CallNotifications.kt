package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import org.json.JSONArray
import org.json.JSONObject

/**
 * A pushed Call and its Host: everything needed to show its notification
 * again (with a new state) from a Choice button, without the app running.
 */
data class CallNotice(val hostId: String, val hostName: String, val call: PushContent.Call) {
    /** The notification's tag; its id is the Host's. */
    val tag get() = "call:${call.id}"

    fun toJson(): String = JSONObject()
        .put("host", hostId)
        .put("hostName", hostName)
        .put("id", call.id)
        .put("terminal", call.terminal)
        .put("topLevel", call.topLevel)
        .put("title", call.title)
        .put("body", call.body)
        .put("urgency", call.urgency.name)
        .put("choices", JSONArray(call.choices))
        .put("at", call.atMs.toLong())
        .toString()

    companion object {
        fun fromJson(json: String): CallNotice? = try {
            val o = JSONObject(json)
            val choices = o.getJSONArray("choices")
            CallNotice(
                o.getString("host"),
                o.getString("hostName"),
                PushContent.Call(
                    o.getString("id"),
                    o.getString("terminal"),
                    o.getBoolean("topLevel"),
                    o.getString("title"),
                    o.getString("body"),
                    CallUrgency.valueOf(o.getString("urgency")),
                    List(choices.length()) { choices.getString(it) },
                    o.getLong("at").toULong(),
                ),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** What a Call notification shows under its text. */
sealed interface CallView {
    /** No Choices: nothing to answer (a tap still opens the Terminal). */
    data object Plain : CallView

    /** One button per Choice: answers without opening the app. */
    data object Choices : CallView

    /**
     * "Open app" instead of the Choices: the Host's key needs a biometric
     * unlock (ticket 23), or there are more Choices than buttons.
     */
    data object OpenApp : CallView

    /** Choice [choice] is on its way; no buttons. */
    data class Sending(val choice: Int) : CallView

    /**
     * How the answer ended: [line] (and [detail]) replace the text.
     * [retry] is the Choice a "Try again" button sends again, when trying
     * again is safe and may help; [openApp] adds "Open app".
     */
    data class Done(val line: String, val detail: String? = null, val retry: Int? = null, val openApp: Boolean = false) : CallView
}

/** Posts a Call's notification in each [CallView]; all views share one notification per Call. */
object CallNotifications {
    /** Android shows at most three buttons on a notification. */
    private const val MAX_BUTTONS = 3

    fun fitsAsButtons(call: PushContent.Call) = call.choices.size in 1..MAX_BUTTONS

    fun post(context: Context, notice: CallNotice, view: CallView) {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationGroups.post(context, notice.hostId, notice.hostName, notice.tag, build(context, manager, notice, view))
    }

    fun build(context: Context, manager: NotificationManager, notice: CallNotice, view: CallView): Notification {
        val call = notice.call
        val builder = PushService.callBuilder(context, manager, notice.hostId, notice.hostName, call)
            // A new state of the same Call must not ring again.
            .setOnlyAlertOnce(true)
        fun status(line: String, detail: String? = null) {
            builder.setContentText(line)
            builder.setStyle(
                Notification.BigTextStyle().bigText(listOfNotNull(call.body.ifEmpty { null }, line, detail).joinToString("\n")),
            )
        }
        when (view) {
            CallView.Plain -> {}
            CallView.Choices -> call.choices.forEachIndexed { i, choice ->
                builder.addAction(action(choice, choiceIntent(context, notice, i)))
            }
            CallView.OpenApp -> builder.addAction(action(context.getString(R.string.call_notify_open_app), openApp(context, notice)))
            is CallView.Sending -> status(context.getString(R.string.call_notify_sending, call.choices.getOrElse(view.choice) { "" }))
            is CallView.Done -> {
                status(view.line, view.detail)
                view.retry?.let { builder.addAction(action(context.getString(R.string.call_notify_try_again), choiceIntent(context, notice, it))) }
                if (view.openApp) builder.addAction(action(context.getString(R.string.call_notify_open_app), openApp(context, notice)))
            }
        }
        return builder.build()
    }

    private fun action(title: String, intent: PendingIntent) = Notification.Action.Builder(null, title, intent).build()

    private fun openApp(context: Context, notice: CallNotice) =
        PushTarget(notice.hostId, notice.call.terminal).pendingIntent(context)

    /**
     * Goes to [ChoiceReceiver]. No `setAuthenticationRequired`: a Choice
     * answers from the lock screen without unlocking (prototype 01).
     */
    private fun choiceIntent(context: Context, notice: CallNotice, choice: Int): PendingIntent {
        val intent = Intent(context, ChoiceReceiver::class.java)
            .setAction(ChoiceReceiver.ACTION)
            // Distinct data per Call and Choice, so each button keeps its own PendingIntent.
            .setData(
                Uri.Builder().scheme("aries-call").authority(notice.hostId)
                    .appendPath(notice.call.id).appendPath(choice.toString()).build(),
            )
            .putExtra(ChoiceReceiver.EXTRA_NOTICE, notice.toJson())
            .putExtra(ChoiceReceiver.EXTRA_CHOICE, choice)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
