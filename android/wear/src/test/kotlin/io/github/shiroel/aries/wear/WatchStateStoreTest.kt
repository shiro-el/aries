package io.github.shiroel.aries.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.weardata.WatchSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WatchStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
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
