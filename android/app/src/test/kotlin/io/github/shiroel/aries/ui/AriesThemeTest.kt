package io.github.shiroel.aries.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rendered app theme: device mode, explicit modes, accent, typography and status meanings. */
@RunWith(RobolectricTestRunner::class)
class AriesThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun appearanceChangesReachTheWholeThemeAndSystemModeFollowsTheDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("theme-render-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = ThemeSettings(preferences)
        val config = mutableStateOf(Configuration(context.resources.configuration).apply {
            uiMode = Configuration.UI_MODE_NIGHT_NO
        })
        lateinit var colors: ColorScheme
        var font: FontFamily? = null
        var working = Color.Unspecified
        var blocked = Color.Unspecified
        var done = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides config.value) {
                AriesTheme(settings) {
                    val c = MaterialTheme.colorScheme
                    val f = MaterialTheme.typography.bodyMedium.fontFamily
                    val w = statusColor(AgentStatus.Working)
                    val b = statusColor(AgentStatus.Blocked(BlockedReason.PERMISSION, null))
                    val d = statusColor(AgentStatus.Done)
                    SideEffect { colors = c; font = f; working = w; blocked = b; done = d }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(Color(0xFFFAF9F7), colors.background)
            assertEquals(FontFamily.Monospace, font)
            assertEquals(Color(0xFFC4441F), blocked)
            assertEquals(Color(0xFF2E7D4A), done)
            config.value = Configuration(config.value).apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        }
        compose.runOnIdle {
            assertEquals(Color(0xFF121315), colors.background)
            assertEquals(Color(0xFFF4B73A), colors.primary)
            assertEquals(colors.primary, working)
            assertEquals(Color(0xFFFF8A65), blocked)
            assertEquals(Color(0xFF7CC891), done)
            settings.chooseMode(ThemeMode.LIGHT)
        }
        compose.runOnIdle {
            assertEquals(Color(0xFFFAF9F7), colors.background)
            settings.chooseMode(ThemeMode.DARK)
            config.value = Configuration(config.value).apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
            settings.setAccent("#8C7CF0")
        }
        compose.runOnIdle {
            assertEquals(Color(0xFF121315), colors.background)
            assertNotEquals(Brand.Saffron, colors.primary)
            assertTrue(contrast(colors.primary, colors.surfaceContainerHighest) >= 4.5f)
            assertEquals(colors.primary, working)
            assertNotEquals(colors.primary, blocked)
            settings.chooseMode(ThemeMode.SYSTEM)
        }
        compose.runOnIdle { assertEquals(Color(0xFFFAF9F7), colors.background) }
    }
    // Regression: a near-black custom accent in dark mode made primary text disappear; lint cannot check dynamic colours.
    @Test fun customAccentTextAndButtonsStayReadableInEveryLayout() {
        for (layout in AppLayout.entries) for (dark in listOf(false, true)) {
            for (accent in listOf(Color.Black, Color.White, Color(0xFF123456), Brand.Saffron)) {
                val colors = schemeFor(accent, dark, layout)
                assertTrue("$layout dark=$dark accent=$accent text", contrast(colors.primary, colors.surfaceContainerHighest) >= 4.5f)
                assertTrue("$layout dark=$dark accent=$accent button", contrast(colors.primary, colors.onPrimary) >= 4.5f)
            }
        }
    }

    // D107: layout-specific/custom accent containers can obscure selected answer text and radio marks; lint does not evaluate runtime palettes.
    @Test fun selectedAnswersMeetTextAndControlContrast() {
        for (layout in AppLayout.entries) for (dark in listOf(false, true)) {
            for (accent in listOf(Brand.Saffron, Color(0xFFE66B4E), Color(0xFF5BAE89),
                Color(0xFF54A8B8), Color(0xFF8C7CF0), Color(0xFFDA7BA0), Color.Black, Color.White, Color(0xFF123456))) {
                val colors = schemeFor(accent, dark, layout)
                assertTrue("$layout dark=$dark accent=$accent selected text", contrast(colors.onPrimaryContainer, colors.primaryContainer) >= 4.5f)
                assertTrue("$layout dark=$dark accent=$accent selected control", contrast(colors.primary, colors.primaryContainer) >= 3f)
            }
        }
    }

    // D112: small Tasks status text must meet AA after tint compositing, which lint cannot evaluate.
    @Test fun tasksStatusChipsMeetSmallTextContrast() {
        for (dark in listOf(false, true)) {
            val colors = schemeFor(Brand.Saffron, dark, AppLayout.TASKS)
            val accents = if (dark) listOf(Brand.Saffron, Color(0xFF7CC891), colors.onSurfaceVariant, colors.error)
                else listOf(Color(0xFF8F5E06), Color(0xFF2E7D4A), colors.onSurfaceVariant, colors.error)
            for (surface in listOf(colors.background, colors.surfaceContainer)) for (accent in accents) for (urgent in listOf(false, true)) {
                val chip = taskStatusChipColors(accent, urgent, surface)
                assertTrue("dark=$dark accent=$accent urgent=$urgent chip text", contrast(chip.foreground, chip.background) >= 4.5f)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

}
