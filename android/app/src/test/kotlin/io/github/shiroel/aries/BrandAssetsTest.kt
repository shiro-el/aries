package io.github.shiroel.aries

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Android's public launcher resources: only the OS mask may round off the ram's body. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandAssetsTest {
    @Test
    fun launcherLayersExtendToTheEdgesAndHaveAMonochromeVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        for (res in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(res) as AdaptiveIconDrawable
            assertEquals(Color.rgb(43, 39, 36), (icon.background as ColorDrawable).color)
            assertNotNull(icon.monochrome)
            for (layer in listOf(icon.foreground, icon.monochrome!!)) {
                val bitmap = (layer as BitmapDrawable).bitmap
                val right = bitmap.width - 1
                val bottom = bitmap.height - 1
                assertEquals("body reaches the right edge", 255, Color.alpha(bitmap.getPixel(right, bottom * 4 / 5)))
                assertEquals("body reaches the bottom edge", 255, Color.alpha(bitmap.getPixel(right * 4 / 5, bottom)))
                assertEquals("no baked lower-right corner", 255, Color.alpha(bitmap.getPixel(right, bottom)))
                assertEquals("foreground has no tile", 0, Color.alpha(bitmap.getPixel(0, 0)))
            }
        }
    }
}
