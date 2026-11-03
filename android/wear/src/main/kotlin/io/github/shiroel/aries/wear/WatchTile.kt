package io.github.shiroel.aries.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.TileBuilders
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchSummary

object WatchTile {
    fun age(context: Context, summary: WatchSummary, now: Long): String {
        val localized = summary.localizedContext(context)
        return if (summary.fetchedAtMs == 0L) localized.getString(R.string.wear_not_synced)
        else localized.getString(R.string.wear_age_minutes, ((now - summary.fetchedAtMs).coerceAtLeast(0) / 60_000))
    }

    fun render(context: Context, summary: WatchSummary, now: Long): TileBuilders.Tile {
        val context = summary.localizedContext(context)
        val column = Column.Builder().setWidth(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(text(context.getString(R.string.wear_waiting_count, summary.waiting), 18f))
        if (summary.agents.isEmpty()) {
            column.addContent(text(context.getString(if (summary.fetchedAtMs == 0L) R.string.wear_open_phone else R.string.wear_no_agents), 12f))
        } else summary.agents.forEach { agent ->
            val state = context.getString(when (agent.status) {
                "blocked" -> R.string.wear_status_blocked
                "error" -> R.string.wear_status_error
                "done" -> R.string.wear_status_done
                "working" -> R.string.wear_status_working
                else -> R.string.wear_status_idle
            })
            column.addContent(agentRow(context, agent, context.getString(R.string.wear_agent_row, state, agent.name)))
        }
        column.addContent(text(age(context, summary, now), 12f))
        val root = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(0xff101820.toInt())).build())
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(16f)).setEnd(dp(16f)).build()).build())
            .addContent(column.build()).build()
        return TileBuilders.Tile.Builder().setResourcesVersion("1")
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(TimelineBuilders.Timeline.Builder().addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder().setLayout(Layout.Builder().setRoot(root).build()).build(),
            ).build()).build()
    }

    private fun agentRow(context: Context, agent: WatchAgent, label: String): Box =
        Box.Builder().setWidth(expand()).setHeight(dp(48f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(ModifiersBuilders.Clickable.Builder()
                .setId("${agent.hostId}/${agent.terminal}")
                .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder().setPackageName(context.packageName)
                        .setClassName("io.github.shiroel.aries.wear.OpenOnPhoneActivity")
                        .addKeyToExtraMapping("host", ActionBuilders.stringExtra(agent.hostId))
                        .addKeyToExtraMapping("terminal", ActionBuilders.stringExtra(agent.terminal))
                        .build(),
                ).build()).build()).build())
            .addContent(text(label, 12f)).build()

    private fun text(value: String, size: Float): Text =
        Text.Builder().setText(value).setMaxLines(1)
            .setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(0xfff2f4f5.toInt())).build())
            .build()
}
