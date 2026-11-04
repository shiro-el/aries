package io.github.shiroel.aries.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.weardata.WatchSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.app.Application
import io.github.shiroel.aries.weardata.WatchAgent
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WatchStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun changedWaitingOrLeadingAgentRequestsComplicationRefreshWithoutTimestampPushLoop() {
        val store = WatchStateStore(context)
        store.clear(store.phoneNode() ?: "phone")
        val app = shadowOf(context as Application)
        fun requests() = app.broadcastIntents.filter {
            it.action == "android.support.wearable.complications.ACTION_REQUEST_UPDATE_ALL"
        }
        val first = WatchAgent("host", "remote/t7", "Codex", "blocked")
        val second = WatchAgent("host", "t8", "Claude", "working")
        val summary = WatchSummary(1, listOf(first, second), 60_000)
        val baseline = requests().size
        assertTrue(store.accept("phone", summary.toRequest().data))
        assertEquals(baseline + 1, requests().size)
        assertTrue(store.accept("phone", summary.copy(fetchedAtMs = 120_000).toRequest().data))
        assertTrue(store.accept("phone", summary.copy(agents = listOf(first, second.copy(status = "done"))).toRequest().data))
        assertFalse(store.accept("phone", byteArrayOf(1, 2)))
        store.clear("other-phone")
        assertEquals(baseline + 1, requests().size)
        assertTrue(store.accept("phone", summary.copy(waiting = 2).toRequest().data))
        assertEquals(baseline + 2, requests().size)
        assertTrue(store.accept("phone", summary.copy(waiting = 2, agents = listOf(second, first)).toRequest().data))
        assertEquals(baseline + 3, requests().size)
        store.clear("phone")
        assertEquals(baseline + 4, requests().size)
        assertEquals(android.content.ComponentName(context, WaitingComplicationService::class.java),
            requests().last().getParcelableExtra("android.support.wearable.complications.EXTRA_PROVIDER_COMPONENT", android.content.ComponentName::class.java))
    }

    @Test fun syncedValueSurvivesRestartInvalidDataKeepsItsAgeAndDeletionClearsIt() {
        val store = WatchStateStore(context)
        val summary = WatchSummary(3, emptyList(), 60_000)
        assertTrue(store.accept("phone-node", summary.toRequest().data))
        assertEquals(summary, WatchStateStore(context).summary())
        assertEquals("phone-node", WatchStateStore(context).phoneNode())
        assertFalse(store.accept("other-node", byteArrayOf(1, 2)))
        assertEquals(summary, store.summary())
        assertEquals("phone-node", store.phoneNode())
        store.clear("other-node")
        assertEquals(summary, store.summary())
        store.clear("phone-node")
        assertEquals(WatchSummary.EMPTY, WatchStateStore(context).summary())
        assertNull(store.phoneNode())
    }
}
