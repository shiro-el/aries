package io.github.shiroel.aries.wear

import android.content.Context
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import io.github.shiroel.aries.weardata.WatchSummary

object WaitingComplication {
    fun render(context: Context, summary: WatchSummary, now: Long): ShortTextComplicationData {
        val description = if (summary.fetchedAtMs == 0L) context.getString(R.string.wear_not_synced)
        else context.getString(R.string.wear_complication_description, summary.waiting, WatchTile.age(context, summary, now))
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(if (summary.fetchedAtMs == 0L) context.getString(R.string.wear_unknown_count) else summary.waiting.toString()).build(),
            PlainComplicationText.Builder(description).build(),
        ).setTitle(PlainComplicationText.Builder(context.getString(R.string.wear_waiting_label)).build()).build()
    }
}
