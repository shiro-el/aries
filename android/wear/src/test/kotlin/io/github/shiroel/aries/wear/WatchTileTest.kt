package io.github.shiroel.aries.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.ActionBuilders.LaunchAction
import androidx.wear.protolayout.ActionBuilders.AndroidStringExtra
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WatchTileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun texts(element: LayoutElement): List<Text> = when (element) {
        is Text -> listOf(element)
        is Column -> element.contents.flatMap(::texts)
        is Box -> element.contents.flatMap(::texts)
        is Row -> element.contents.flatMap(::texts)
        else -> emptyList()
    }

    private fun boxes(element: LayoutElement): List<Box> = when (element) {
        is Box -> listOf(element) + element.contents.flatMap(::boxes)
        is Column -> element.contents.flatMap(::boxes)
        is Row -> element.contents.flatMap(::boxes)
        else -> emptyList()
    }

    @Test fun tileKeepsMachineQualifiedAgentTargetsDistinct() {
        val agents = listOf(
            WatchAgent("h1", "remote/t7", "Claude", "blocked"),
            WatchAgent("h2", "t7", "Codex", "error"),
            WatchAgent("h1", "t8", "Pi", "blocked"),
        )
        val device = DeviceParameters.Builder().setScreenWidthDp(240).setScreenHeightDp(240)
            .setScreenDensity(2f).setFontScale(1f).build()
        val tile = WatchTile.render(context, WatchSummary(4, agents, 60_000), 240_000, device)
        val rows = boxes(tile.tileTimeline!!.timelineEntries.single().layout!!.root!!)
            .filter { it.modifiers?.clickable?.id in agents.map { agent -> "${agent.hostId}/${agent.terminal}" } }
        val targets = rows.mapNotNull { it.modifiers?.clickable?.onClick as? LaunchAction }
            .map { it.androidActivity!! }
        assertEquals(3, targets.size)
        assertEquals("remote/t7", (targets[0].keyToExtraMapping["terminal"] as AndroidStringExtra).value)
        assertEquals("h2", (targets[1].keyToExtraMapping["host"] as AndroidStringExtra).value)
        assertEquals(context.packageName, targets[0].packageName)
        assertTrue(tile.freshnessIntervalMillis >= 60_000)
    }

    @Test @Config(qualifiers = "ko") fun noSyncIsExplicitAndLocalized() {
        val tile = WatchTile.render(context, WatchSummary.EMPTY, 240_000)
        val labels = texts(tile.tileTimeline!!.timelineEntries.single().layout!!.root!!).map { it.text!!.value }
        assertTrue(labels.contains("아직 동기화되지 않음"))
        assertFalse(labels.contains("대기 없음"))
    }
}
