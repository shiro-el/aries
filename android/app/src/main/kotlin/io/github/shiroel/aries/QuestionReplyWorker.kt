package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.shiroel.aries.core.AnswerProblem
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.ui.answerProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class QuestionChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = synchronized(NotificationGroups) {
        if (intent.action != ACTION) return@synchronized
        val notice = NotificationPayloads.open(context, intent.getStringExtra(EXTRA_NOTICE))?.let { QuestionNotice.fromJson(it) } ?: return@synchronized
        val choice = intent.getIntExtra(EXTRA_CHOICE, -1)
        if (notice.question.options.size !in 1..3 || choice !in notice.question.options.indices) return@synchronized
        if (!QuestionNotifications.accepts(context, notice)) return@synchronized
        QuestionNotifications.post(context, notice, QuestionView.Sending(choice))
        try {
            QuestionReplyWorker.enqueue(context, notice, choice)
        } catch (_: Exception) {
            QuestionNotifications.post(context, notice, QuestionView.Done(
                AppLanguage.context(context).getString(R.string.notification_action_failed), openApp = true))
        }
    }

    companion object {
        const val ACTION = "io.github.shiroel.aries.ANSWER_QUESTION"
        const val EXTRA_NOTICE = "question_notice"
        const val EXTRA_CHOICE = "choice"
    }
}

/** One-shot expedited work, using only saved Host settings; no foreground HostConnection. */
class QuestionReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notice = NotificationPayloads.open(applicationContext, inputData.getString(KEY_NOTICE))?.let { QuestionNotice.fromJson(it) } ?: return Result.failure()
        val choice = inputData.getInt(KEY_CHOICE, -1)
        if (notice.question.options.size !in 1..3 || choice !in notice.question.options.indices) return Result.failure()
        // WorkManager persists the attempt count before starting work. A
        // restarted attempt may follow delivered input and a lost reply.
        if (runAttemptCount > 0) {
            QuestionNotifications.post(applicationContext, notice, QuestionView.Done(
                AppLanguage.context(applicationContext).getString(R.string.question_notify_unknown)))
            return Result.success()
        }
        val core = (applicationContext as? HasCore)?.core ?: return Result.failure()
        val view = QuestionReplies.answer(applicationContext, core, notice, choice, CallReplyWorker.online(applicationContext))
        QuestionNotifications.post(applicationContext, notice, view)
        // No offline queue or automatic retry: a lost answer result may already have pressed keys.
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = AppLanguage.context(applicationContext)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.call_notify_channel_sending), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aries)
            .setContentTitle(context.getString(R.string.call_notify_channel_sending)).build()
        return ForegroundInfo(0x72, notification)
    }

    companion object {
        private const val KEY_NOTICE = "notice"
        private const val KEY_CHOICE = "choice"
        private const val CHANNEL = "question_replies"

        fun input(context: Context, notice: QuestionNotice, choice: Int) = workDataOf(KEY_NOTICE to NotificationPayloads.seal(context, notice.toJson()), KEY_CHOICE to choice)

        fun enqueue(context: Context, notice: QuestionNotice, choice: Int) {
            val request = OneTimeWorkRequestBuilder<QuestionReplyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(input(context, notice, choice)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "question-reply:${notice.hostId}/${notice.terminal}/${notice.question.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}

object QuestionReplies {
    suspend fun answer(context: Context, core: AriesCoreInterface, notice: QuestionNotice, choice: Int, online: Boolean): QuestionView.Done {
        val context = AppLanguage.context(context)
        // A notification/work request can survive an app upgrade. Older
        // Always actions must also go through the phone card's confirmation.
        if (notice.question.id.startsWith(PERMISSION_ID_PREFIX) && notice.question.options.getOrNull(choice) == "Allow always")
            return QuestionView.Done(context.getString(R.string.question_notify_confirm_in_app))
        if (!online) return QuestionView.Done(context.getString(R.string.call_notify_no_network))
        return try {
            // The core bounds sign-in (15 s) and the Beacon result (30 s).
            withTimeout(60_000L) {
                withContext(Dispatchers.IO) {
                    if (!core.canAnswerInBackground(notice.hostId)) throw CoreException.KeyLocked()
                    core.answerQuestionBackground(notice.hostId, notice.terminal, notice.question.id, listOf(listOf(choice.toUInt())))
                }
            }
            QuestionView.Done(context.getString(R.string.question_notify_sent, notice.choiceLabel(context, choice)), openApp = false)
        } catch (_: TimeoutCancellationException) {
            QuestionView.Done(context.getString(R.string.question_notify_unknown))
        } catch (e: CoreException) {
            val line = when (e) {
                is CoreException.KeyLocked, is CoreException.KeyInvalidated, is CoreException.BiometricUnavailable -> context.getString(R.string.call_notify_key_locked)
                is CoreException.Unreachable -> context.getString(R.string.call_notify_unreachable)
                is CoreException.AuthRejected, is CoreException.JumpAuthRejected -> context.getString(R.string.call_notify_key_rejected)
                is CoreException.HostKeyUnknown, is CoreException.HostKeyChanged,
                is CoreException.JumpHostKeyUnknown, is CoreException.JumpHostKeyChanged -> context.getString(R.string.call_notify_host_key)
                is CoreException.AnswerRefused -> if (e.reason == AnswerProblem.FAILED)
                    context.getString(R.string.question_notify_unknown)
                else answerProblem(e).let { context.getString(it.res, *it.args.toTypedArray()) }
                else -> answerProblem(e).let { context.getString(it.res, *it.args.toTypedArray()) }
            }
            QuestionView.Done(line)
        }
    }
}
