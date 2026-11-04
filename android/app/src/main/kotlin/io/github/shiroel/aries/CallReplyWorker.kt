package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CallAnswer
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallReply
import io.github.shiroel.aries.core.CallReplyOutcome
import io.github.shiroel.aries.core.CoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A Choice button on a Call notification. SystemUI sends it as a
 * foreground broadcast, which may run only ~10 s; the SSH reply can take
 * longer, so it only marks the notification "Sending…" and hands the reply
 * to [CallReplyWorker] (prototype 01).
 */
class ChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = synchronized(NotificationGroups) {
        if (intent.action != ACTION) return@synchronized
        val notice = NotificationPayloads.open(context, intent.getStringExtra(EXTRA_NOTICE))?.let { CallNotice.fromJson(it) } ?: return@synchronized
        val choice = intent.getIntExtra(EXTRA_CHOICE, -1)
        if (choice !in notice.call.choices.indices) return@synchronized
        if (!CallNotifications.accepts(context, notice, intent.getStringExtra(CallNotifications.CHOICE_TOKEN))) return@synchronized
        CallNotifications.post(context, notice, CallView.Sending(choice))
        try {
            CallReplyWorker.enqueue(context, notice, choice)
        } catch (_: Exception) {
            CallNotifications.post(context, notice, CallView.Done(
                AppLanguage.context(context).getString(R.string.notification_action_failed), openApp = true))
        }
    }

    companion object {
        const val ACTION = "io.github.shiroel.aries.ANSWER_CALL"
        const val EXTRA_NOTICE = "call_notice"
        const val EXTRA_CHOICE = "choice"
    }
}

/**
 * Answers a Call from its notification in the background (expedited work:
 * it survives the receiver, the screen going off and, as persisted work,
 * the process being killed, which a `goAsync` receiver does not). Ends by
 * putting the outcome on the same notification, so it is never left at
 * "Sending…": a killed run is run again, and a second reply is safe (the
 * Host's claim makes it `Answered`).
 */
class CallReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notice = NotificationPayloads.open(applicationContext, inputData.getString(KEY_NOTICE))?.let { CallNotice.fromJson(it) } ?: return Result.failure()
        val choice = inputData.getInt(KEY_CHOICE, -1)
        val core = (applicationContext as? HasCore)?.core ?: return Result.failure()
        val view = if (runAttemptCount > 0) CallReplies.refresh(applicationContext, core, notice, choice, runAttemptCount)
            else CallReplies.answer(applicationContext, core, notice, choice, online(applicationContext))
        CallNotifications.post(applicationContext, notice, view)
        LastKnownStates.requestRefresh(applicationContext, notice.hostId)
        return if (view.pending) Result.retry() else Result.success()
    }

    /** Only below Android 12, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = AppLanguage.context(applicationContext)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.call_notify_channel_sending),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.call_notify_channel_sending))
            .build()
        return ForegroundInfo(FOREGROUND_ID, n)
    }

    companion object {
        private const val KEY_NOTICE = "notice"
        private const val KEY_CHOICE = "choice"
        private const val CHANNEL = "call_replies"
        private const val FOREGROUND_ID = 0x36

        /** One reply per Call at a time: a second tap while it runs does nothing. */
        fun enqueue(context: Context, notice: CallNotice, choice: Int) {
            val request = OneTimeWorkRequestBuilder<CallReplyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(input(context, notice, choice))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("call-reply:${notice.hostId}/${notice.call.id}", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        internal fun input(context: Context, notice: CallNotice, choice: Int) =
            workDataOf(KEY_NOTICE to NotificationPayloads.seal(context, notice.toJson()), KEY_CHOICE to choice)

        fun online(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}

/** The app's core, as background components find it. */
interface HasCore {
    val core: AriesCoreInterface
}

/**
 * Runs one notification answer and says how it ended. The line depends on
 * whether the reply started on the Host (research `background-choice-reply`):
 * before that it is "Not sent" (nothing typed), after it an unknown result
 * is "Unknown" (it may have been typed; trying again is safe).
 */
object CallReplies {
    /** Longer than the core's own limits (15 s to sign in, 45 s for the result). */
    private const val OVERALL_MS = 90_000L

    suspend fun answer(context: Context, core: AriesCoreInterface, notice: CallNotice, choice: Int, online: Boolean): CallView.Done {
        val context = AppLanguage.context(context)
        val choiceText = notice.call.choices.getOrElse(choice) { "" }
        if (!online) {
            return CallView.Done(context.getString(R.string.call_notify_no_network), retry = choice)
        }
        val reply = try {
            withTimeout(OVERALL_MS) {
                withContext(Dispatchers.IO) { core.replyCall(notice.hostId, notice.call.id, choice.toUInt()) }
            }
        } catch (e: TimeoutCancellationException) {
            return unknown(context, choice, null)
        } catch (e: CoreException) {
            return notSent(context, e, choice)
        }
        return outcome(context, reply, choiceText, choice)
    }

    /** A retry of a pending claim only observes it; it must never submit another answer. */
    suspend fun refresh(context: Context, core: AriesCoreInterface, notice: CallNotice, choice: Int, attempt: Int): CallView.Done {
        val context = AppLanguage.context(context)
        val text = notice.call.choices.getOrElse(choice) { "" }
        fun waiting() = if (attempt >= 3) unconfirmed(context, text) else pending(context, text)
        val call = try {
            withTimeout(OVERALL_MS) { withContext(Dispatchers.IO) {
                if (!core.canAnswerInBackground(notice.hostId)) throw CoreException.KeyLocked()
                core.calls(notice.hostId).firstOrNull { it.id == notice.call.id }
            } }
        } catch (_: TimeoutCancellationException) {
            return waiting()
        } catch (e: CoreException) {
            return if (e is CoreException.KeyLocked) notSent(context, e, choice) else waiting()
        } ?: return CallView.Done(context.getString(R.string.call_reply_no_call), openApp = true)
        val answer = call.answer ?: return unknown(context, choice, null)
        if (answer.state == CallAnswerState.CLAIMED && attempt >= 3) return unconfirmed(context, answer.text)
        return outcome(context, CallReply(CallReplyOutcome.ANSWERED, answer, answer.detail), answer.text, choice)
    }

    /** A crashed sender may never finish its claim. Stop observing without releasing that claim. */
    private fun unconfirmed(context: Context, text: String) = CallView.Done(
        context.getString(R.string.call_answer_unconfirmed, text),
        context.getString(R.string.call_reply_terminal_guidance), openApp = true,
    )

    private fun pending(context: Context, text: String) =
        CallView.Done(context.getString(R.string.call_answer_elsewhere, text), pending = true)

    private fun unknown(context: Context, choice: Int, detail: String?) =
        CallView.Done(context.getString(R.string.call_notify_unknown), detail, retry = choice, openApp = true)

    private fun notSent(context: Context, e: CoreException, choice: Int): CallView.Done = when (e) {
        is CoreException.Unreachable ->
            CallView.Done(context.getString(R.string.call_notify_unreachable), e.detail, retry = choice)
        is CoreException.KeyLocked, is CoreException.KeyInvalidated, is CoreException.BiometricUnavailable ->
            CallView.Done(context.getString(R.string.call_notify_key_locked), openApp = true)
        is CoreException.AuthRejected, is CoreException.JumpAuthRejected ->
            CallView.Done(context.getString(R.string.call_notify_key_rejected), openApp = true)
        is CoreException.HostKeyUnknown, is CoreException.HostKeyChanged,
        is CoreException.JumpHostKeyUnknown, is CoreException.JumpHostKeyChanged ->
            CallView.Done(context.getString(R.string.call_notify_host_key), openApp = true)
        else -> CallView.Done(context.getString(R.string.call_notify_not_sent, e.message.orEmpty()), openApp = true)
    }

    private fun outcome(context: Context, reply: CallReply, choiceText: String, choice: Int): CallView.Done {
        val text = reply.answer?.text ?: choiceText
        return when (reply.outcome) {
            CallReplyOutcome.DELIVERED -> CallView.Done(context.getString(R.string.call_answer_delivered, text))
            CallReplyOutcome.FAILED ->
                CallView.Done(context.getString(R.string.call_answer_failed, text), reply.detail, openApp = true)
            CallReplyOutcome.UNCONFIRMED ->
                CallView.Done(context.getString(R.string.call_answer_unconfirmed, text), reply.detail, openApp = true)
            CallReplyOutcome.ANSWERED -> CallView.Done(
                reply.answer?.let { answerLine(context, it) } ?: context.getString(R.string.call_reply_answered),
                openApp = reply.answer?.state != CallAnswerState.CLAIMED,
                pending = reply.answer?.state == CallAnswerState.CLAIMED,
            )
            CallReplyOutcome.NO_CALL -> CallView.Done(context.getString(R.string.call_reply_no_call), openApp = true)
            CallReplyOutcome.NO_CHOICE -> CallView.Done(context.getString(R.string.call_reply_no_choice), openApp = true)
            CallReplyOutcome.NOT_ANSWERABLE -> CallView.Done(context.getString(R.string.call_unconfirmed), openApp = true)
            CallReplyOutcome.NO_BEACON -> CallView.Done(context.getString(R.string.call_reply_no_beacon), openApp = true)
            CallReplyOutcome.UNKNOWN -> unknown(context, choice, reply.detail)
        }
    }

    /** The answer the Host already has (another phone, or an earlier run of this one). */
    private fun answerLine(context: Context, a: CallAnswer) = context.getString(
        when (a.state) {
            CallAnswerState.CLAIMED -> R.string.call_answer_elsewhere
            CallAnswerState.DELIVERED -> R.string.call_answer_delivered
            CallAnswerState.FAILED -> R.string.call_answer_failed
            CallAnswerState.UNCONFIRMED -> R.string.call_answer_unconfirmed
        },
        a.text,
    )
}
