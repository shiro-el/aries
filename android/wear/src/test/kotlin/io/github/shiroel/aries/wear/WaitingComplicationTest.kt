package io.github.shiroel.aries.wear

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.weardata.WatchSummary
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WaitingComplicationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun complicationShowsCountAndDescribesItsOriginalAge() {
        val data = WaitingComplication.render(context, WatchSummary(7, emptyList(), 60_000), 360_000)
        assertEquals("7", data.text.getTextAt(context.resources, Instant.EPOCH).toString())
        assertEquals("7 waiting · 5 min ago", data.contentDescription!!.getTextAt(context.resources, Instant.EPOCH).toString())
    }

    @Test fun complicationDoesNotPresentUnsyncedAsZero() {
        val data = WaitingComplication.render(context, WatchSummary.EMPTY, 360_000)
        assertEquals("--", data.text.getTextAt(context.resources, Instant.EPOCH).toString())
        assertEquals("Not synced yet", data.contentDescription!!.getTextAt(context.resources, Instant.EPOCH).toString())
    }

    @Test fun registeredComplicationUsesAtLeastFiveMinuteUpdatesAndWearBindingPermission() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, WaitingComplicationService::class.java), PackageManager.GET_META_DATA,
        )
        assertTrue(service.exported)
        assertEquals("com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER", service.permission)
        assertEquals("SHORT_TEXT", service.metaData.getString("android.support.wearable.complications.SUPPORTED_TYPES"))
        assertTrue(service.metaData.getInt("android.support.wearable.complications.UPDATE_PERIOD_SECONDS") >= 300)
        val tile = context.packageManager.getServiceInfo(ComponentName(context, AgentsTileService::class.java), PackageManager.GET_META_DATA)
        assertEquals("com.google.android.wearable.permission.BIND_TILE_PROVIDER", tile.permission)
        assertTrue(tile.metaData.getInt("androidx.wear.tiles.PREVIEW") != 0)
    }
}
