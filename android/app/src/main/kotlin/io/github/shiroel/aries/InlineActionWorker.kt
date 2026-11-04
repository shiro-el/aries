package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.SendListener
import io.github.shiroel.aries.core.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One immediate attempt. No network constraint or retry queues a message
 * for reconnection. WorkManager can restart interrupted work, but unlike a
 * Call Choice, terminal.send is not idempotent: a restarted attempt only
 * reports the uncertain outcome. It never sends the text a second time.
 */
class InlineActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val target = inputData.getString(TARGET)?.let(InlineTarget::fromJson) ?: return@withContext Result.failure()
        val action = inputData.getString(ACTION)
        val context = AppLanguage.context(applicationContext)
        val text = NotificationPayloads.open(applicationContext, inputData.getString(TEXT))?.takeIf { it.isNotBlank() }
        fun show(resource: Int) = InlineNotifications.update(context, target, context.getString(resource))
        suspend fun failed(resource: Int, uncertain: Boolean = runAttemptCount > 0) {
            if (action == InlineNotifications.REPLY && text != null) {
                (applicationContext as? HasCore)?.core?.let { core ->
                    try {
                        io.github.shiroel.aries.ui.ChatReplyRecovery.recoverFromWorker(core,
                            io.github.shiroel.aries.ui.AgentKey(target.hostId, target.terminal),
                            io.github.shiroel.aries.ui.ChatReplyRecovery.message(target.generation, text,
                                context.getString(resource), uncertain = uncertain))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the text on its private recovery card. */ }
                }
            }
            InlineNotifications.failed(context, target, context.getString(resource), text,
                reply = action == InlineNotifications.REPLY, persistDraft = false)
        }
        val core = (applicationContext as? HasCore)?.core ?: run {
            failed(R.string.notification_action_failed)
            return@withContext Result.failure()
        }
        // Never deliver a stale queued draft or repeat an interrupted send.
        if (runAttemptCount > 0 || System.currentTimeMillis() - inputData.getLong(CREATED, 0) > 60_000) {
            failed(R.string.notification_action_failed)
            LastKnownStates.requestRefresh(applicationContext, target.hostId)
            return@withContext Result.success()
        }
        // The receiver validated and consumed this generation before persisting
        // its unique work. Dismissing/replacing a card does not revoke that tap.
        // Card generation checks belong only to result updates.
        if (!CallReplyWorker.online(context)) {
            failed(R.string.notification_action_offline)
            return@withContext Result.success()
        }
        try {
            withTimeout(90_000) {
                when (action) {
                    InlineNotifications.REPLY -> {
                        if (text == null) {
                            failed(R.string.notification_action_failed)
                            return@withTimeout
                        }
                        if (io.github.shiroel.aries.ui.ChatReplyRecovery.enqueueFromWorker(core,
                                io.github.shiroel.aries.ui.AgentKey(target.hostId, target.terminal), target.generation, text)) {
                            show(R.string.notification_message_queued)
                            return@withTimeout
                        }
                        val outcome = core.sendMessageBackground(target.hostId, target.terminal, text, object : SendListener {
                            override fun onHeld() { show(R.string.chat_send_held) }
                        })
                        if (outcome == SendOutcome.Sent) show(R.string.notification_message_sent)
                        else failed(R.string.chat_send_refused)
                    }
                    InlineNotifications.SEEN -> {
                        val turn = target.turn ?: return@withTimeout
                        core.markSeenBackground(target.hostId, target.terminal, turn)
                        InlineNotifications.dismiss(context, target)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            failed(R.string.notification_action_failed, uncertain = true)
        } catch (e: CoreException) {
            failed(when {
                e is CoreException.KeyLocked || e is CoreException.KeyInvalidated -> R.string.notification_action_key_locked
                e is CoreException.Rpc && e.kind == "no_agent" -> R.string.chat_input_agent_ended
                else -> R.string.notification_action_failed
            }, uncertain = e !is CoreException.KeyLocked && e !is CoreException.KeyInvalidated &&
                (e !is CoreException.Rpc || e.kind !in setOf("no_agent", "status_mismatch")))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed(R.string.notification_action_failed, uncertain = false)
        } finally {
            LastKnownStates.requestRefresh(applicationContext, target.hostId)
        }
        Result.success()
    }

    /** Expedited work on Android 9–11 needs a foreground notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = AppLanguage.context(applicationContext)
        val channel = "inline_actions"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel,
            context.getString(R.string.call_notify_channel_sending), NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(7401, Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_aries)
            .setContentTitle(context.getString(R.string.call_notify_channel_sending)).build())
    }

    companion object {
        private const val TARGET = "target"
        private const val ACTION = "action"
        private const val TEXT = "text"
        private const val CREATED = "created"
        internal fun input(context: Context, target: String, action: String, text: String? = null, created: Long = System.currentTimeMillis()) =
            workDataOf(TARGET to target, ACTION to action, TEXT to text?.let { NotificationPayloads.seal(context, it) }, CREATED to created)

        internal fun enqueue(context: Context, target: InlineTarget, action: String, text: String? = null) {
            val work = OneTimeWorkRequestBuilder<InlineActionWorker>()
                .setInputData(input(context, target.toJson(), action, text))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("inline:${target.generation}", ExistingWorkPolicy.KEEP, work)
        }
    }
}
