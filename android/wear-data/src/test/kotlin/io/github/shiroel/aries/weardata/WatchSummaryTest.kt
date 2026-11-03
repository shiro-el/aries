package io.github.shiroel.aries.weardata

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WatchSummaryTest {
    @Test fun dataClientPayloadRetainsAgeUnicodeAndWholeTerminalAddresses() {
        val summary = WatchSummary(5, listOf(WatchAgent("host-1", "remote/t7", "질문", "Blocked")), 120_000)
        val request = summary.toRequest()
        assertEquals("/aries/last-known-state", request.uri.path)
        val wire = DataMap.fromByteArray(request.data!!)
        assertEquals(1, wire.getInt("version"))
        assertEquals(5, wire.getInt("waiting"))
        assertEquals(120_000L, wire.getLong("fetched_at_ms"))
        assertEquals("remote/t7", wire.getDataMapArrayList("agents")!!.single().getString("terminal"))
        assertEquals(summary, WatchSummary.fromBytes(request.data))
    }

    @Test fun unsupportedAndMalformedSummariesAreIgnored() {
        assertNull(WatchSummary.fromBytes(null))
        assertNull(WatchSummary.fromBytes(byteArrayOf(1, 2)))
        val map = DataMap.fromByteArray(WatchSummary.EMPTY.toRequest().data!!)
        map.putInt("version", 2)
        assertNull(WatchSummary.fromBytes(map.toByteArray()))
        map.putInt("version", 1)
        map.putInt("waiting", -1)
        assertNull(WatchSummary.fromBytes(map.toByteArray()))
    }

    @Test fun openRequestSurvivesTransportAndRejectsAnEmptyTarget() {
        val target = OpenWatchAgent("h1", "machine/t7")
        assertEquals(target, OpenWatchAgent.fromBytes(target.toBytes()))
        assertNull(OpenWatchAgent.fromBytes(OpenWatchAgent("", "").toBytes()))
        assertNull(OpenWatchAgent.fromBytes(byteArrayOf(1)))
    }
}
