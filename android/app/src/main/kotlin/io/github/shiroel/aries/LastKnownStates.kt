package io.github.shiroel.aries

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.LastKnownState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Shared entry points for widgets, QS tiles and the watch. Push only requests a pull. */
object LastKnownStates {
    const val PERIODIC_WORK = "last-known-state:periodic"
    private const val HOST_ID = "host_id"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Idempotent across process starts. WorkManager persists the schedule. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LastKnownStateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Manual surface refresh or an authenticated push: one Host, or all Hosts when null. */
    fun requestRefresh(context: Context, hostId: String? = null) {
        val request = OneTimeWorkRequestBuilder<LastKnownStateWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(HOST_ID to hostId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("last-known-state:refresh:${hostId ?: "all"}", ExistingWorkPolicy.KEEP, request)
    }

    /** Initial persisted lists, then changes from any live connection or background pull. */
    fun observe(core: AriesCoreInterface): Flow<List<LastKnownState>> = flow {
        val watch = core.watchLastKnownStates()
        try {
            while (true) emit(watch.next())
        } finally {
            watch.stop()
            watch.close()
        }
    }.conflate().flowOn(Dispatchers.IO)

    /** Failures keep the old value and its original fetchedAtMs, so its age stays honest. */
    suspend fun refreshInBackground(core: AriesCoreInterface, hostId: String? = null) = withContext(Dispatchers.IO) {
        val ids = hostId?.let(::listOf) ?: core.hosts().map { it.id }
        for (id in ids) {
            try {
                // Includes every jump key and excludes missing keys. Never prompt from work.
                if (core.canAnswerInBackground(id)) core.snapshot(id)
            } catch (_: CoreException) {
                // Offline, deleted, untrusted or locked: another Host can still refresh.
            }
        }
    }

    internal fun hostId(params: WorkerParameters): String? = params.inputData.getString(HOST_ID)
}

/** Short SSH pulls only; no foreground service or persistent connection. */
class LastKnownStateWorker(context: Context, private val params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val core = (applicationContext as? HasCore)?.core ?: return Result.failure()
        LastKnownStates.refreshInBackground(core, LastKnownStates.hostId(params))
        HomeWidgets.update(applicationContext, core)
        return Result.success()
    }
}
