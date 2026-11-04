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
        val summary = WatchSummary(5, listOf(WatchAgent("host-1", "remote/t7", "질문", "Blocked", hostName = "Studio")), 120_000,
            hostFetchedAtMs = mapOf("host-1" to 120_000, "never-fetched" to 0, "offline" to 60_000))
        val request = summary.toRequest()
        assertEquals("/aries/last-known-state", request.uri.path)
        val wire = DataMap.fromByteArray(request.data!!)
        assertEquals(1, wire.getInt("version"))
        assertEquals(5, wire.getInt("waiting"))
        assertEquals(120_000L, wire.getLong("fetched_at_ms"))
        assertEquals("remote/t7", wire.getDataMapArrayList("agents")!!.single().getString("terminal"))
        assertEquals(summary, WatchSummary.fromBytes(request.data))
    }

    @Test fun mixedPhoneAndWatchVersionsKeepTheLegacyProjectionAndReadOptionalDetails() {
        val v1 = DataMap().apply {
            putInt("version", 1)
            putInt("waiting", 1)
            putLong("fetched_at_ms", 123)
            putDataMapArrayList("agents", arrayListOf(DataMap().apply {
                putString("host", "h1"); putString("terminal", "t1")
                putString("name", "Build"); putString("status", "blocked")
            }))
        }
        val old = WatchSummary.fromBytes(v1.toByteArray())!!
        assertEquals("", old.inbox.single().conversationSummary)
        val rich = old.agents.single().copy(requestBody = "Run cargo test?", conversationSummary = "Build passed.")
        val wire = DataMap.fromByteArray(old.copy(inbox = listOf(rich)).toRequest().data!!)
        // These are the constraints of the already-shipped v1 decoder.
        assertEquals(1, wire.getInt("version"))
        assertTrue(wire.getDataMapArrayList("agents")!!.size <= 3)
        assertEquals("blocked", wire.getDataMapArrayList("agents")!!.single().getString("status"))
        assertEquals("Run cargo test?", WatchSummary.fromBytes(wire.toByteArray())!!.inbox.single().requestBody)
        wire.putInt("version", 2)
        assertEquals("Build passed.", WatchSummary.fromBytes(wire.toByteArray())!!.inbox.single().conversationSummary)
    }

    @Test fun unicodeRequestsAndResponseExcerptsCannotExceedTheDataLayerBudget() {
        val long = "가😀".repeat(1000)
        val row = WatchAgent("h1", "t1", long, "blocked", long, long, long, long, choices = List(20) { long })
        val request = WatchSummary(100, listOf(row), 123, inbox = List(100) { row.copy(terminal = "t$it") }).toRequest()
        assertTrue(request.data!!.size <= 100_000)
        val decoded = WatchSummary.fromBytes(request.data)!!
        assertTrue(decoded.inbox.size <= 32)
        val excerpt = decoded.inbox.first()
        assertTrue(excerpt.requestBody.length <= 1200)
        assertTrue(excerpt.conversationSummary.length <= 600)
        assertFalse(Character.isHighSurrogate(excerpt.conversationSummary.last()))
        val hidden = WatchSummary.fromBytes(WatchSummary(1, listOf(row), 123,
            inbox = listOf(row.copy(contentHidden = true))).toRequest().data)!!.inbox.single()
        assertEquals("", hidden.requestBody)
        assertEquals("", hidden.conversationSummary)
        assertTrue(hidden.choices.isEmpty())
    }

    @Test fun unsupportedAndMalformedSummariesAreIgnored() {
        assertNull(WatchSummary.fromBytes(null))
        assertNull(WatchSummary.fromBytes(byteArrayOf(1, 2)))
        val map = DataMap.fromByteArray(WatchSummary.EMPTY.toRequest().data!!)
        map.putInt("version", 99)
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
