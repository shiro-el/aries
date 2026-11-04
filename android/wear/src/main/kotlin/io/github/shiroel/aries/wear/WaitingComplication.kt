package io.github.shiroel.aries.wear

import android.content.Context
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import io.github.shiroel.aries.weardata.WatchSummary

object WaitingComplication {
    /** Kept for existing Short Text callers. */
    fun render(context: Context, summary: WatchSummary, now: Long): ShortTextComplicationData =
        render(context, summary, now, ComplicationType.SHORT_TEXT) as ShortTextComplicationData

    fun render(context: Context, summary: WatchSummary, now: Long, type: ComplicationType): ComplicationData? {
        val context = summary.localizedContext(context)
        val synced = summary.fetchedAtMs != 0L
        val description = if (!synced) context.getString(R.string.wear_not_synced)
        else context.getString(R.string.wear_complication_description, summary.waiting, WatchTile.age(context, summary, now))
        val staleHosts = summary.hostFetchedAtMs.values.count { it == 0L || now - it > WatchTile.STALE_AFTER_MS }
        val fullDescription = if (staleHosts == 0) description else context.getString(R.string.wear_row_age,
            description, context.getString(R.string.wear_hosts_stale, staleHosts))
        val title = if (!synced) context.getString(R.string.wear_waiting_label)
        else WatchTile.age(context, summary.fetchedAtMs, now, compact = true)
        val icon = Icon.createWithResource(context,
            if (!synced || summary.waiting == 0) R.drawable.ic_aries_outline else R.drawable.ic_aries)
        val image = MonochromaticImage.Builder(icon).build()
        val open = WatchNavigation.pendingIntent(context)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text(if (!synced) context.getString(R.string.wear_unknown_count) else summary.waiting.toString()),
                text(fullDescription),
            ).setTitle(text(title)).setMonochromaticImage(image).setTapAction(open).build()
            ComplicationType.LONG_TEXT -> {
                val agent = summary.agents.firstOrNull()
                val label = agent?.let { context.getString(R.string.wear_complication_agent,
                    WatchConsole.code(context, it.status), it.name, it.hostName.ifBlank { it.hostId }) }
                    ?: context.getString(if (!synced) R.string.wear_not_synced else R.string.wear_no_agents)
                val agentAge = agent?.let { WatchTile.age(context, summary.fetchedAt(it.hostId), now, compact = true) } ?: title
                LongTextComplicationData.Builder(text(label), text(context.getString(R.string.wear_complication_agent_description,
                    label, context.getString(R.string.wear_row_age, agentAge, fullDescription))))
                    .setTitle(text(agentAge)).setMonochromaticImage(image)
                    .setTapAction(WatchNavigation.pendingIntent(context, agent)).build()
            }
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(image, text(fullDescription))
                .setTapAction(open).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(icon, SmallImageType.ICON).build(), text(fullDescription),
            ).setTapAction(open).build()
            else -> null
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
