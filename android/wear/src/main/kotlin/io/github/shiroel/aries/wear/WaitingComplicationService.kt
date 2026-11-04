package io.github.shiroel.aries.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.github.shiroel.aries.weardata.WatchSummary
import io.github.shiroel.aries.weardata.WatchAgent

class WaitingComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val store = WatchStateStore(this)
        store.hydrate(this)
        return WaitingComplication.render(this, store.summary(), System.currentTimeMillis(), request.complicationType)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        WaitingComplication.render(this, WatchSummary(2,
            listOf(WatchAgent("prod", "t7", "api-refactor", "blocked")), 1), 240_001, type)
}
