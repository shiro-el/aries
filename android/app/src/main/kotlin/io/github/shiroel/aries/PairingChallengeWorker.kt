package io.github.shiroel.aries

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.shiroel.aries.core.CoreException
import java.util.concurrent.TimeUnit

/** Confirms token ownership while locked, without a notification or user action. */
class PairingChallengeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("verification_id") ?: return Result.success()
        val proof = inputData.getString("challenge") ?: return Result.success()
        return try {
            // The core ignores unsolicited, stale or already completed IDs.
            // It resolves the Relay from private local state, never from FCM.
            (applicationContext as HasCore).core.confirmPushToken(id, proof, HttpRelayClient())
            Result.success()
        } catch (_: CoreException) {
            // Retrying is safe; the core stops sending once the ID expires.
            Result.retry()
        }
    }

    companion object {
        private val token = Regex("[A-Za-z0-9_-]{43}")

        fun enqueue(context: Context, data: Map<String, String>) {
            val id = data["verification_id"] ?: return
            val proof = data["challenge"] ?: return
            if (!token.matches(id) || !token.matches(proof)) return
            val work = OneTimeWorkRequestBuilder<PairingChallengeWorker>()
                .setInputData(workDataOf("verification_id" to id, "challenge" to proof))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("push-ownership:$id", ExistingWorkPolicy.KEEP, work)
        }
    }
}
