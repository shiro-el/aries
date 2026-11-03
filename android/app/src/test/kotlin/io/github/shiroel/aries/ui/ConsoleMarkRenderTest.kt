package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsoleMarkRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun topBarFitsTheSmallMarkBesideTheTitleInBothThemes() {
        var appearance by mutableStateOf(false to 3)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            val (dark, density) = appearance
            val colors = if (dark) darkColorScheme() else lightColorScheme()
            CompositionLocalProvider(LocalDensity provides Density(density.toFloat())) {
                MaterialTheme(colorScheme = colors) {
                    Surface(color = colors.background) {
                        Column {
                            ConsoleTopBar(stringResource(R.string.app_name), onMenu = {})
                        }
                    }
                }
            }
        }
        for (dark in listOf(false, true)) for (density in listOf(3, 4)) {
            compose.runOnIdle { appearance = dark to density }
            val header = compose.onNodeWithTag("console-top-bar")
            val title = compose.onNodeWithText(context.getString(R.string.app_name)).fetchSemanticsNode().boundsInRoot
            // 44dp navigation + 26dp mark + 8dp gap; both share the header's centre.
            assertEquals(78f * density, title.left, .5f)
            assertEquals(22f * density, title.center.y, .5f)
            assertEquals(44f * density, header.fetchSemanticsNode().boundsInRoot.height, .5f)
            System.getenv("ARIES_BRAND_RENDER_DIR")?.let { directory ->
                File(directory).mkdirs()
                File(directory, "topbar-${if (dark) "dark" else "light"}-${density}x.png").outputStream().use {
                    header.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
