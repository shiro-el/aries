package io.github.shiroel.aries.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.github.shiroel.aries.weardata.WatchSummary
import io.github.shiroel.aries.weardata.WatchProgress

class WatchDataService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val store = WatchStateStore(this)
        for (event in events) {
            val item = event.dataItem
            val phone = item.uri.host ?: continue
            when (item.uri.path) {
                WatchSummary.DATA_PATH -> when (event.type) {
                    DataEvent.TYPE_CHANGED -> store.accept(phone, item.data)
                    DataEvent.TYPE_DELETED -> store.clear(phone)
                }
                WatchProgress.DATA_PATH -> when (event.type) {
                    DataEvent.TYPE_CHANGED -> store.acceptProgress(phone, item.data)
                    DataEvent.TYPE_DELETED -> store.clearProgress(phone)
                }
            }
        }
        TileService.getUpdater(this).requestUpdate(AgentsTileService::class.java)
        // WatchStateStore requests complication updates only when displayed state changes.
    }
}
