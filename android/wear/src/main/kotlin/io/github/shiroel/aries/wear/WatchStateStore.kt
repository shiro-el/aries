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
class WatchStateStore(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("watch-summary", Context.MODE_PRIVATE)

    fun summary(): WatchSummary = runCatching {
        WatchSummary.fromBytes(Base64.decode(prefs.getString("payload", null), Base64.NO_WRAP))
    }.getOrNull() ?: WatchSummary.EMPTY

    fun phoneNode(): String? = prefs.getString("phone", null)

    fun accept(phone: String, bytes: ByteArray?): Boolean {
        val next = WatchSummary.fromBytes(bytes) ?: return false
        if (phone.isBlank()) return false
        val previous = summary()
        val editor = prefs.edit().putString("phone", phone)
            .putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val hosts = next.hostFetchedAtMs.keys + next.inbox.map { it.hostId } + progresses().map { it.second.hostId }
        hosts.forEach { host ->
            if (next.fetchedAt(host) > previous.fetchedAt(host) || phone != phoneNode())
                editor.putLong("received:$host", now())
        }
        val saved = editor.commit()
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
    private fun progresses(): List<Pair<String, WatchProgress>> = progressPrefs.all.mapNotNull { (key, value) ->
        if (key != "payload" && !key.startsWith("payload:")) null else runCatching {
            key to requireNotNull(WatchProgress.fromBytes(Base64.decode(value as String, Base64.NO_WRAP)))
        }.getOrNull()
    }

    /** Completion tombstones and ordering belong to each Host + Terminal, across restarts. */
    fun acceptProgress(phone: String, bytes: ByteArray?, path: String? = null): Boolean = synchronized(PROGRESS_LOCK) {
        val next = WatchProgress.fromBytes(bytes) ?: return false
        if (phone.isBlank()) return false
        val key = "payload:${next.dataPath}"
        val previous = progresses().firstOrNull { (_, p) -> p.hostId == next.hostId && p.terminal == next.terminal }
        if (previous != null && progressPhone(previous.first) == phone &&
            (next.atMs < previous.second.atMs ||
                (next.atMs == previous.second.atMs && previous.second.cancelled && !next.cancelled))) return false
        val editor = progressPrefs.edit().putString("phone:$key", phone)
            .putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (previous == null || progressPhone(previous.first) != phone || next.atMs > previous.second.atMs)
            editor.putLong("received:$key", now())
        val source = path ?: next.dataPath
        // The legacy mirror must not replace ownership of a per-Agent DataItem.
        if (source != WatchProgress.DATA_PATH || progressPrefs.getString("path:$key", null) != next.dataPath)
            editor.putString("path:$key", source)
        if (previous?.first == "payload") editor.remove("payload").remove("phone")
        val saved = editor.commit()
        if (saved) refreshProgress()
        saved
    }

    private fun progressPhone(key: String): String? = progressPrefs.getString(if (key == "payload") "phone" else "phone:$key", null)

    fun clearProgress(phone: String, path: String? = null) = synchronized(PROGRESS_LOCK) {
        progresses().forEach { (key, previous) ->
            val source = progressPrefs.getString("path:$key", if (key == "payload") WatchProgress.DATA_PATH else previous.dataPath)
            if (progressPhone(key) == phone && (path == null || path == source)) {
                saveCancelled(key, previous)
                WatchProgressNotifications.clear(context, previous)
            }
        }
    }

    /** A newer pull for this Host retires completed Agents; stale progress expires even without a push. */
    fun refreshProgress() = synchronized(PROGRESS_LOCK) {
        val summary = summary()
        progresses().forEach { (key, current) ->
            val fetched = summary.fetchedAt(current.hostId)
            val agent = summary.inbox.find { it.hostId == current.hostId && it.terminal == current.terminal }
            val completeList = summary.totalAgents <= summary.inbox.size
            val newerPull = current.observedAtMs != 0L && fetched > current.observedAtMs
            val retired = !current.cancelled && progressPhone(key) == phoneNode() && newerPull &&
                ((agent != null && agent.status != "working") || (agent == null && completeList))
            if (retired) {
                saveCancelled(key, current)
                WatchProgressNotifications.clear(context, current)
            } else {
                // Receipt and lifetime use this watch's clock. Both fetch ordering fields
                // originate on the phone; Host timestamps only order progress generations.
                val receivedKey = "received:$key"
                val received = if (progressPrefs.contains(receivedKey)) progressPrefs.getLong(receivedKey, 0)
                    else now().also { progressPrefs.edit().putLong(receivedKey, it).commit() }
                val confirmed = if (newerPull && agent?.status == "working" && progressPhone(key) == phoneNode())
                    maxOf(received, prefs.getLong("received:${current.hostId}", received)) else received
                WatchProgressNotifications.show(context, current, summary,
                    (WatchProgress.MAX_AGE_MS - (now() - confirmed).coerceAtLeast(0)).coerceAtLeast(0),
                    (received - (current.atMs - current.startedAtMs)).coerceAtLeast(0))
            }
        }
    }

    private fun saveCancelled(key: String, progress: WatchProgress) {
        val bytes = requireNotNull(progress.copy(cancelled = true).toRequest().data)
        progressPrefs.edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
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
                            when {
                                item.uri.path == WatchSummary.DATA_PATH -> accept(phone, item.data)
                                WatchProgress.isDataPath(item.uri.path) -> acceptProgress(phone, item.data, item.uri.path)
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
