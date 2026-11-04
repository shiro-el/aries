package io.github.shiroel.aries.wear

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.argb as layoutColor
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.TileBuilders
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchSummary

object WatchTile {
    private const val RESOURCES_VERSION = "console-1"

    const val STALE_AFTER_MS = 30 * 60_000L

    fun age(context: Context, summary: WatchSummary, now: Long): String =
        age(summary.localizedContext(context), summary.fetchedAtMs, now)

    fun age(context: Context, fetchedAtMs: Long, now: Long, compact: Boolean = false): String {
        if (fetchedAtMs == 0L) return context.getString(R.string.wear_not_synced)
        val minutes = (now - fetchedAtMs).coerceAtLeast(0) / 60_000
        val (resource, value) = when {
            minutes >= 1440 -> (if (compact) R.string.wear_age_days_compact else R.string.wear_age_days) to minutes / 1440
            minutes >= 60 -> (if (compact) R.string.wear_age_hours_compact else R.string.wear_age_hours) to minutes / 60
            else -> (if (compact) R.string.wear_age_compact else R.string.wear_age_minutes) to minutes
        }
        return context.getString(resource, value)
    }

    fun render(context: Context, summary: WatchSummary, now: Long,
        device: DeviceParameters = DeviceParameters.Builder().setScreenWidthDp(192).setScreenHeightDp(192)
            .setScreenDensity(1f).setFontScale(1f).build(),
    ): TileBuilders.Tile {
        val context = summary.localizedContext(context)
        val synced = summary.fetchedAtMs != 0L
        val stale = synced && now - summary.fetchedAtMs > STALE_AFTER_MS
        val title = when {
            !synced -> context.getString(R.string.wear_tile_unknown)
            summary.waiting == 0 -> context.getString(R.string.wear_tile_all_clear)
            else -> context.getString(R.string.wear_tile_waiting, summary.waiting)
        }
        val age = age(context, summary, now)
        val staleHosts = summary.hostFetchedAtMs.values.count { it == 0L || now - it > STALE_AFTER_MS }
        val ageLabel = if (stale) context.getString(R.string.wear_tile_stale, age) else age
        val rows = if (device.screenWidthDp >= 225 && device.screenHeightDp >= 225) 3 else 2
        val root = materialScope(context, device, allowDynamicTheme = false,
            defaultColorScheme = ColorScheme(
                primary = WatchConsole.SAFFRON.layoutColor, onPrimary = WatchConsole.NIGHT.layoutColor,
                background = WatchConsole.NIGHT.layoutColor, onBackground = WatchConsole.TEXT.layoutColor,
                surfaceContainer = WatchConsole.CARD.layoutColor, onSurface = WatchConsole.TEXT.layoutColor,
            ),
        ) {
            primaryLayout(
                titleSlot = { label(title, 12f, WatchConsole.SAFFRON) },
                mainSlot = {
                    Column.Builder().setWidth(expand()).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).apply {
                        if (!synced || summary.totalAgents == 0) {
                            addContent(label(context.getString(if (!synced) R.string.wear_open_phone else R.string.wear_no_agents), 12f))
                        } else if (summary.waiting == 0) {
                            addContent(label(context.getString(R.string.wear_tile_working,
                                summary.inbox.count { it.status == "working" }), 12f, WatchConsole.MUTED))
                        } else {
                            summary.agents.filter { it.status == "blocked" || it.status == "error" }.take(rows).forEachIndexed { index, agent ->
                                if (index > 0) addContent(Spacer.Builder().setHeight(dp(3f)).build())
                                addContent(agentRow(context, agent, summary.fetchedAt(agent.hostId), now))
                            }
                        }
                        addContent(Spacer.Builder().setHeight(dp(4f)).build())
                        // Waiting rows already show their own Host ages. Keep one footer line
                        // on small watches so the stale-Host warning remains visible in Korean.
                        if (staleHosts == 0 || summary.agents.none { it.status == "blocked" || it.status == "error" })
                            addContent(label(ageLabel, 10f, if (stale) WatchConsole.ERROR else WatchConsole.MUTED))
                        if (staleHosts > 0) addContent(label(context.getString(R.string.wear_hosts_stale, staleHosts), 10f, WatchConsole.ERROR))
                    }.build()
                },
                bottomSlot = { textEdgeButton(onClick = WatchNavigation.clickable(context)) {
                    text(context.getString(R.string.wear_tile_open).layoutString)
                } },
            )
        }
        val background = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setBackground(
                ModifiersBuilders.Background.Builder().setColor(argb(WatchConsole.NIGHT)).build()).build())
            .addContent(root).build()
        return TileBuilders.Tile.Builder().setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(TimelineBuilders.Timeline.Builder().addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder().setLayout(Layout.Builder().setRoot(background).build()).build(),
            ).build()).build()
    }

    fun resources(): ResourceBuilders.Resources = ResourceBuilders.Resources.Builder()
        .setVersion(RESOURCES_VERSION).apply {
            listOf("blocked", "error", "working", "done", "idle").forEach { status ->
                addIdToImageMapping(status, ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder()
                        .setResourceId(WatchConsole.icon(status)).build()).build())
            }
        }.build()

    private fun agentRow(context: Context, agent: WatchAgent, fetchedAt: Long, now: Long): Box {
        val status = agent.status.takeIf { it in setOf("blocked", "error", "working", "done") } ?: "idle"
        val color = WatchConsole.color(status)
        val code = WatchConsole.code(context, status, agent.requestKind)
        val content = Row.Builder().setWidth(expand()).setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .addContent(Image.Builder().setResourceId(status).setWidth(dp(14f)).setHeight(dp(14f))
                .setColorFilter(ColorFilter.Builder().setTint(argb(color)).build()).build())
            .addContent(Spacer.Builder().setWidth(dp(5f)).build())
            .addContent(label(code, 11f, color))
            .addContent(Spacer.Builder().setWidth(dp(6f)).build())
            .addContent(Box.Builder().setWidth(expand()).setHorizontalAlignment(HORIZONTAL_ALIGN_START)
                .addContent(Column.Builder().setHorizontalAlignment(HORIZONTAL_ALIGN_START)
                    .addContent(label(agent.name, 12f))
                    .addContent(label(context.getString(R.string.wear_row_age, agent.hostName.ifBlank { agent.hostId },
                        age(context, fetchedAt, now, compact = true)), 10f, WatchConsole.MUTED)).build()).build()).build()
        return Box.Builder().setWidth(expand()).setHeight(dp(32f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setClickable(WatchNavigation.clickable(context, agent))
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(
                    context.getString(R.string.wear_agent_row, WatchConsole.status(context, status), agent.name)).build())
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(8f)).setEnd(dp(8f)).build())
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(WatchConsole.CARD))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(WatchConsole.CORNER_RADIUS_DP)).build()).build()).build())
            .addContent(content).build()
    }

    private fun label(value: String, size: Float, color: Int = WatchConsole.TEXT): Text =
        Text.Builder().setText(value).setMaxLines(1).setOverflow(TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setSettings(FontSetting.tabularNum()).build()).build()
}
