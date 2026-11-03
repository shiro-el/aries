package io.github.shiroel.aries

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Android's public launcher resources: only the OS mask may round off the ram's body. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandAssetsTest {
    @Test
    fun smallMarksKeepAContinuousConstellationAtPhoneDensities() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((name, resource) in listOf("ink" to R.drawable.mark_ink, "light" to R.drawable.mark_light)) {
            for (density in listOf(3, 4)) for (dp in listOf(22, 24, 28)) {
                val side = dp * density
                val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                context.getDrawable(resource)!!.apply {
                    setBounds(0, 0, side, side)
                    draw(Canvas(bitmap))
                }
                System.getenv("ARIES_BRAND_RENDER_DIR")?.let { directory ->
                    File(directory).mkdirs()
                    File(directory, "mark-$name-${dp}dp-${density}x.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                // Sample the long connector away from the stars. At xxhdpi even
                // the smallest mark needs two solid pixels, without a broken hairline.
                for (x in side * 65 / 100..side * 72 / 100) {
                    val gold = (side * 58 / 100..side * 73 / 100).count { y ->
                        val c = bitmap.getPixel(x, y)
                        Color.alpha(c) > 220 && Color.red(c) > 220 &&
                            Color.green(c) in 145..205 && Color.blue(c) < 100
                    }
                    assertTrue("$name ${dp}dp @${density}x connector at x=$x: $gold solid pixels", gold >= 2)
                }
            }
        }
    }

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
