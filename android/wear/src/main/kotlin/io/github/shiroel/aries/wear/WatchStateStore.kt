package io.github.shiroel.aries.wear

import android.content.ComponentName
import android.content.Context
import android.util.Base64
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.WatchSummary
import io.github.shiroel.aries.weardata.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Persist only the small summary and the DataItem's originating phone node. */
class WatchStateStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("watch-summary", Context.MODE_PRIVATE)

    fun summary(): WatchSummary = runCatching {
        WatchSummary.fromBytes(Base64.decode(prefs.getString("payload", null), Base64.NO_WRAP))
    }.getOrNull() ?: WatchSummary.EMPTY

    fun phoneNode(): String? = prefs.getString("phone", null)

    fun accept(phone: String, bytes: ByteArray?): Boolean {
        val next = WatchSummary.fromBytes(bytes) ?: return false
        if (phone.isBlank()) return false
        val previous = summary()
        val saved = prefs.edit().putString("phone", phone)
            .putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        if (saved) {
            refreshComplication(previous, next)
            refreshProgress()
        }
        return saved
    }

    fun clear(phone: String) {
        if (phone != phoneNode()) return
        val previous = summary()
        if (prefs.edit().clear().commit()) {
            refreshComplication(previous, WatchSummary.EMPTY)
            clearProgress(phone)
        }
    }

    private val progressPrefs = context.getSharedPreferences("watch-progress", Context.MODE_PRIVATE)
    private fun progress(): WatchProgress? = runCatching {
        WatchProgress.fromBytes(Base64.decode(progressPrefs.getString("payload", null), Base64.NO_WRAP))
    }.getOrNull()

    /** Latest single progress DataItem; completion tombstones reject delayed delivery after restart. */
    fun acceptProgress(phone: String, bytes: ByteArray?): Boolean = synchronized(PROGRESS_LOCK) {
        val next = WatchProgress.fromBytes(bytes) ?: return false
        if (phone.isBlank()) return false
        val previous = progress()
        if (progressPrefs.getString("phone", null) == phone && previous != null &&
            (next.atMs < previous.atMs || (next.atMs == previous.atMs && previous.cancelled && !next.cancelled))) return false
        val saved = progressPrefs.edit().putString("phone", phone)
            .putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        if (saved) refreshProgress()
        saved
    }

    fun clearProgress(phone: String) = synchronized(PROGRESS_LOCK) {
        if (progressPrefs.getString("phone", null) != phone) return
        val previous = progress()
        if (previous != null) saveCancelled(previous)
        WatchProgressNotifications.clear(context)
    }

    /** Also restores an accepted activity after the user grants notification permission. */
    fun refreshProgress() = synchronized(PROGRESS_LOCK) {
        val current = progress() ?: return
        val summary = summary()
        if (!current.cancelled && progressPrefs.getString("phone", null) == phoneNode() && summary.fetchedAtMs > current.atMs) {
            val agent = summary.inbox.find { it.hostId == current.hostId && it.terminal == current.terminal }
            val completeList = summary.totalAgents <= summary.inbox.size
            if ((agent != null && agent.status != "working") || (agent == null && completeList)) {
                saveCancelled(current.copy(atMs = summary.fetchedAtMs))
                WatchProgressNotifications.clear(context)
                return
            }
        }
        WatchProgressNotifications.show(context, current, summary)
    }

    private fun saveCancelled(progress: WatchProgress) {
        val bytes = requireNotNull(progress.copy(cancelled = true).toRequest().data)
        progressPrefs.edit().putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }

    companion object { private val PROGRESS_LOCK = Any() }

    private fun refreshComplication(previous: WatchSummary, next: WatchSummary) {
        // Fetch timestamps and lower-ranked Agent changes do not need an event-driven push.
        if (previous.waiting == next.waiting && previous.agents.firstOrNull() == next.agents.firstOrNull() &&
            previous.languageTag == next.languageTag && (previous.fetchedAtMs == 0L) == (next.fetchedAtMs == 0L)) return
        ComplicationDataSourceUpdateRequester.create(context,
            ComponentName(context, WaitingComplicationService::class.java)).requestUpdateAll()
    }

    /** Query GMS's local DataItems too: a newly installed Tile may have missed the event. */
    suspend fun hydrate(context: Context) {
        try {
            withTimeoutOrNull(2_000) {
                val items = Wearable.getDataClient(context).dataItems.await()
                try {
                    items.forEach { item ->
                        item.uri.host?.let { phone ->
                            when (item.uri.path) {
                                WatchSummary.DATA_PATH -> accept(phone, item.data)
                                WatchProgress.DATA_PATH -> acceptProgress(phone, item.data)
                            }
                        }
                    }
                } finally { items.release() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing Play services or a disconnect keeps the persisted value and its age.
        }
    }
}
