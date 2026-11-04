package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.core.PushQuestion
import org.json.JSONArray
import org.json.JSONObject

/** The question as pushed, persisted with the work so a cold process can finish its notification. */
data class QuestionNotice(
    val hostId: String,
    val hostName: String,
    val terminal: String,
    val question: PushQuestion,
) {
    fun toJson(): String = JSONObject()
        .put("host", hostId).put("hostName", hostName).put("terminal", terminal)
        .put("id", question.id)
        .put("text", question.text).put("options", JSONArray(question.options)).toString()

    companion object {
        fun fromJson(json: String): QuestionNotice? = try {
            val o = JSONObject(json)
            val options = o.getJSONArray("options")
            QuestionNotice(o.getString("host"), o.getString("hostName"), o.getString("terminal"),
                PushQuestion(o.getString("id"), o.getString("text"),
                    List(options.length()) { options.getString(it) }))
        } catch (_: Exception) {
            null
        }
    }
}

sealed interface QuestionView {
    data class Sending(val choice: Int) : QuestionView
    data class Done(val line: String, val openApp: Boolean = true) : QuestionView
}

object QuestionNotifications {
    fun decorate(context: Context, notice: PushNotice, status: PushContent.Status, builder: Notification.Builder,
        canAnswerInBackground: (String) -> Boolean) {
        val blocked = status.status as? AgentStatus.Blocked ?: return
        val question = status.question
        val permission = blocked.reason == BlockedReason.PERMISSION
        if (permission) {
            // Older/arbitrary permission statuses keep their original notification.
            if (question?.id?.startsWith(PERMISSION_ID_PREFIX) != true ||
                question.options !in listOf(listOf("Allow", "Deny"), listOf("Allow", "Allow always", "Deny"))) return
        } else if (blocked.reason != BlockedReason.QUESTION) return
        if (question != null && question.options.size in 1..3 && canAnswerInBackground(notice.hostId)) {
            val q = QuestionNotice(notice.hostId, notice.hostName, status.terminal, question)
            // Preserves the question identity through Sending/Done and guards against a late worker
            // replacing a newer status notification for this Terminal.
            builder.addExtras(android.os.Bundle().apply { putString(QUESTION_ID, question.id) })
            question.options.forEachIndexed { choice, label ->
                val always = permission && label == "Allow always"
                val label = if (permission) permissionChoiceResource(label)?.let(context::getString) ?: label else label
                // Persistent grants require the phone card's explicit confirmation.
                // Allow/Deny keep their existing background path and original indices.
                val intent = if (always) PushTarget(notice.hostId, status.terminal, chat = true).pendingIntent(context)
                    else choiceIntent(context, q, choice)
                val action = Notification.Action.Builder(null, NotificationMarkdown.render(label).plain, intent)
                if (always) action.addExtras(android.os.Bundle().apply { putBoolean(WearNotificationBridge.PHONE_ONLY, true) })
                builder.addAction(action.build())
            }
            builder.setStyle(Notification.BigTextStyle().bigText(NotificationMarkdown.render(question.text).expanded))
        } else {
            builder.addAction(openAction(context, notice.hostId, status.terminal))
        }
    }

    fun post(context: Context, notice: QuestionNotice, view: QuestionView) {
        val context = AppLanguage.context(context)
        // Use the same monitor as grouped posts/dismissals, so the identity
        // check and replacement cannot race a newer push.
        synchronized(NotificationGroups) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val current = manager.activeNotifications.find { it.id == notice.hostId.hashCode() && it.tag == notice.terminal }
            // A newer push or dismissal wins over an old answer's completion.
            if (current == null || current.notification.extras.getString(QUESTION_ID) != notice.question.id) return
            // Clear the recovered Notification's action array as well: an empty
            // builder action list can otherwise retain the original buttons.
            val previous = current.notification.clone().apply { actions = null }
            val builder = Notification.Builder.recoverBuilder(context, previous)
                .setOnlyAlertOnce(true)
            val line = when (view) {
                is QuestionView.Sending -> context.getString(R.string.call_notify_sending, notice.choiceLabel(context, view.choice))
                is QuestionView.Done -> view.line
            }
            val display = NotificationMarkdown.render(line)
            builder.setContentText(display.plain)
                .setStyle(Notification.BigTextStyle().bigText(TextUtils.concat(
                    NotificationMarkdown.render(notice.question.text).expanded, "\n", display.expanded)))
            if (view is QuestionView.Done && view.openApp) builder.addAction(openAction(context, notice.hostId, notice.terminal))
            NotificationGroups.post(context, notice.hostId, notice.hostName, notice.terminal, builder.build())
        }
    }

    private const val QUESTION_ID = "aries.question.id"

    private fun openAction(context: Context, hostId: String, terminal: String) = Notification.Action.Builder(
        null, context.getString(R.string.question_notify_open_app), PushTarget(hostId, terminal, chat = true).pendingIntent(context),
    ).build()

    private fun choiceIntent(context: Context, notice: QuestionNotice, choice: Int): PendingIntent {
        val intent = Intent(context, QuestionChoiceReceiver::class.java)
            .setAction(QuestionChoiceReceiver.ACTION)
            .setData(Uri.Builder().scheme("aries-question").authority(notice.hostId).appendPath(notice.terminal)
                .appendPath(notice.question.id).appendPath(choice.toString()).build())
            .putExtra(QuestionChoiceReceiver.EXTRA_NOTICE, NotificationPayloads.seal(context, notice.toJson()))
            .putExtra(QuestionChoiceReceiver.EXTRA_CHOICE, choice)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

internal fun QuestionNotice.choiceLabel(context: Context, choice: Int): String {
    val label = question.options[choice]
    return if (question.id.startsWith(PERMISSION_ID_PREFIX)) permissionChoiceResource(label)?.let(context::getString) ?: label else label
}
