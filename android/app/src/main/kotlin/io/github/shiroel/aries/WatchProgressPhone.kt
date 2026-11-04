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
import io.github.shiroel.aries.core.LastKnownState
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.weardata.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Durable latest progress, independent of phone notification visibility. Never opens SSH. */
internal object WatchProgressPhone {
    private fun prefs(context: Context) = context.getSharedPreferences("watch-progress", Context.MODE_PRIVATE)

    fun all(context: Context): List<WatchProgress> = prefs(context).all.mapNotNull { (key, value) ->
        if (key != "payload" && !key.startsWith("payload:")) null else runCatching {
            WatchProgress.fromBytes(Base64.decode(value as String, Base64.NO_WRAP))
        }.getOrNull()
    }

    @Synchronized fun progress(context: Context, host: String, progress: PushContent.Progress) {
        val at = millis(progress.at)
        val path = WatchProgress(host, progress.terminal, "", 0, 0, at).dataPath
        if (at <= prefs(context).getLong("completed:$path", -1)) return
        val previous = all(context).find { it.hostId == host && it.terminal == progress.terminal }
        if (previous != null && (at < previous.atMs ||
            (at == previous.atMs && previous.cancelled && previous.hostId == host && previous.terminal == progress.terminal))) return
        save(context, WatchProgress(host, progress.terminal, NotificationMarkdown.render(progress.name ?: progress.terminal).plain.take(128),
            (at - millis(progress.elapsedSecs)).coerceAtLeast(0),
            progress.childAgents.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt(), at,
            observedAtMs = previous?.observedAtMs?.takeIf { previous.atMs == at && it != 0L } ?: System.currentTimeMillis()))
    }

    @Synchronized fun completed(context: Context, host: String, terminal: String, at: ULong) {
        val previous = all(context).find { it.hostId == host && it.terminal == terminal } ?: return
        if (previous.hostId != host || previous.terminal != terminal || millis(at) < previous.atMs) return
        prefs(context).edit().putLong("completed:${previous.dataPath}", millis(at))
            .putLong("completed-recorded:${previous.dataPath}", System.currentTimeMillis()).commit()
        save(context, previous.copy(atMs = millis(at), cancelled = true))
    }

    @Synchronized fun reconcile(context: Context, states: List<LastKnownState>) {
        val byHost = states.associateBy { it.hostId }
        all(context).filterNot { it.cancelled }.forEach { progress ->
            val state = byHost[progress.hostId]
            val terminal = state?.agents?.find { it.address == progress.terminal }
            // Legacy payloads acquire a phone observation before participating in pull recall.
            if (progress.observedAtMs == 0L) {
                save(context, progress.copy(observedAtMs = System.currentTimeMillis()))
                return@forEach
            }
            if ((state != null && state.fetchedAtMs >= progress.observedAtMs.toULong() &&
                    (terminal == null || terminal.state != "live" || terminal.agentStatus != AgentStatus.Working)) ||
                System.currentTimeMillis() - progress.observedAtMs >= WatchProgress.MAX_AGE_MS) {
                completed(context, progress.hostId, progress.terminal, (progress.atMs / 1000).toULong())
            }
        }
    }

    private fun save(context: Context, progress: WatchProgress) {
        val bytes = requireNotNull(progress.toRequest().data)
        val editor = prefs(context).edit()
        // Carry the legacy single record forward when the first per-Agent update arrives.
        prefs(context).getString("payload", null)?.let { legacy ->
            val old = runCatching { WatchProgress.fromBytes(Base64.decode(legacy, Base64.NO_WRAP)) }.getOrNull()
            if (old != null) editor.putString("payload:${old.dataPath}", legacy)
        }
        editor.remove("payload").putString("payload:${progress.dataPath}", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (editor.commit()) enqueue(context)
    }

    /** Drop acknowledged payloads, retaining a small replay watermark rather than a DataItem. */
    @Synchronized fun acknowledged(context: Context, progress: WatchProgress) {
        if (!progress.cancelled || all(context).none { it == progress }) return
        val editor = prefs(context).edit().remove("payload:${progress.dataPath}")
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        prefs(context).all.forEach { (key, value) ->
            if (key.startsWith("completed:") && value is Long) {
                val recorded = "completed-recorded:${key.removePrefix("completed:")}"
                val time = prefs(context).getLong(recorded, 0)
                if (time == 0L) editor.putLong(recorded, System.currentTimeMillis())
                else if (time < cutoff) editor.remove(key).remove(recorded)
            }
        }
        editor.commit()
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
    private val delete: suspend (String) -> Unit = { path ->
        // This phone owns its items; never delete another paired phone's matching path.
        val node = Wearable.getNodeClient(context).localNode.await().id
        Wearable.getDataClient(context).deleteDataItems(android.net.Uri.Builder()
            .scheme("wear").authority(node).path(path).build()).await()
        Unit
    },
    private val publish: suspend (PutDataRequest) -> Unit = { request ->
        Wearable.getDataClient(context).putDataItem(request).await()
        Unit
    },
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WatchProgressPhone.reconcile(applicationContext, emptyList())
        val progress = WatchProgressPhone.all(applicationContext)
        if (progress.isEmpty()) return Result.success()
        return try {
            // GMS retains the DataItem while disconnected and delivers it when the watch returns.
            progress.forEach {
                publish(it.toRequest())
                if (it.cancelled) delete(it.dataPath)
            }
            // Older watches still subscribe to the single-record path.
            val latest = progress.filterNot { it.cancelled }.maxByOrNull { it.atMs }
            if (latest == null) delete(WatchProgress.DATA_PATH)
            else publish(PutDataRequest.create(WatchProgress.DATA_PATH).setData(requireNotNull(latest.toRequest().data)).setUrgent())
            // Only forget after both the per-Agent and legacy DataItems were cleaned up.
            progress.filter { it.cancelled }.forEach { WatchProgressPhone.acknowledged(applicationContext, it) }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
