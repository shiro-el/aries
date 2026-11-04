package io.github.shiroel.aries

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.PutDataRequest
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.weardata.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Durable latest progress, independent of phone notification visibility. Never opens SSH. */
internal object WatchProgressPhone {
    private fun prefs(context: Context) = context.getSharedPreferences("watch-progress", Context.MODE_PRIVATE)

    fun latest(context: Context): WatchProgress? = runCatching {
        WatchProgress.fromBytes(Base64.decode(prefs(context).getString("payload", null), Base64.NO_WRAP))
    }.getOrNull()

    @Synchronized fun progress(context: Context, host: String, progress: PushContent.Progress) {
        val at = millis(progress.at)
        val previous = latest(context)
        if (previous != null && (at < previous.atMs ||
            (at == previous.atMs && previous.cancelled && previous.hostId == host && previous.terminal == progress.terminal))) return
        save(context, WatchProgress(host, progress.terminal, (progress.name ?: progress.terminal).take(128),
            (at - millis(progress.elapsedSecs)).coerceAtLeast(0),
            progress.childAgents.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt(), at))
    }

    @Synchronized fun completed(context: Context, host: String, terminal: String, at: ULong) {
        val previous = latest(context) ?: return
        if (previous.hostId != host || previous.terminal != terminal || millis(at) < previous.atMs) return
        save(context, previous.copy(atMs = millis(at), cancelled = true))
    }

    private fun save(context: Context, progress: WatchProgress) {
        val bytes = requireNotNull(progress.toRequest().data)
        if (prefs(context).edit().putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) enqueue(context)
    }

    fun enqueue(context: Context) {
        // Serialized workers always read the latest durable value, including a completion
        // that arrived while an earlier DataClient publication was in flight.
        WorkManager.getInstance(context).enqueueUniqueWork("watch-progress:publish", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WatchProgressWorker>().build())
    }

    private fun millis(seconds: ULong): Long = seconds.coerceAtMost((Long.MAX_VALUE / 1000).toULong()).toLong() * 1000
}

class WatchProgressWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val publish: suspend (PutDataRequest) -> Unit = { request ->
        Wearable.getDataClient(context).putDataItem(request).await()
        Unit
    },
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val progress = WatchProgressPhone.latest(applicationContext) ?: return Result.success()
        return try {
            // GMS retains the DataItem while disconnected and delivers it when the watch returns.
            publish(progress.toRequest())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
