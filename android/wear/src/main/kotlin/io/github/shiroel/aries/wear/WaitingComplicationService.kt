package io.github.shiroel.aries.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.github.shiroel.aries.weardata.WatchSummary

class WaitingComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val store = WatchStateStore(this)
        store.hydrate(this)
        return WaitingComplication.render(this, store.summary(), System.currentTimeMillis())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) WaitingComplication.render(this, WatchSummary(3, emptyList(), 1), 1)
        else null
}
