package io.github.shiroel.aries.wear

import android.content.ComponentName
import android.content.Context
import android.util.Base64
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.WatchSummary
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
        if (saved) refreshComplication(previous, next)
        return saved
    }

    fun clear(phone: String) {
        if (phone != phoneNode()) return
        val previous = summary()
        if (prefs.edit().clear().commit()) refreshComplication(previous, WatchSummary.EMPTY)
    }

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
                    items.filter { it.uri.path == WatchSummary.DATA_PATH }.forEach { item ->
                        item.uri.host?.let { accept(it, item.data) }
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
