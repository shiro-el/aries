package io.github.shiroel.aries.wear

import android.content.Context
import android.util.Base64
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.WatchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Persist only the small summary and the DataItem's originating phone node. */
class WatchStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("watch-summary", Context.MODE_PRIVATE)

    fun summary(): WatchSummary = runCatching {
        WatchSummary.fromBytes(Base64.decode(prefs.getString("payload", null), Base64.NO_WRAP))
    }.getOrNull() ?: WatchSummary.EMPTY

    fun phoneNode(): String? = prefs.getString("phone", null)

    fun accept(phone: String, bytes: ByteArray?): Boolean {
        if (phone.isBlank() || WatchSummary.fromBytes(bytes) == null) return false
        return prefs.edit().putString("phone", phone)
            .putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }

    fun clear(phone: String) {
        if (phone == phoneNode()) prefs.edit().clear().commit()
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
