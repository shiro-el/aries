// Console palette and status meanings, ported from prototype/ui-revamp ProtoTheme.kt.
package io.github.shiroel.aries.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.shiroel.aries.core.AgentStatus

object Brand {
    val Saffron = Color(0xFFF4B73A)
    val Ink = Color(0xFF2B2724)
    val GoldTop = Color(0xFFF8D98A)
    val GoldBottom = Color(0xFFD9952E)
    val Night = Color(0xFF121315)
    val Gold = Brush.linearGradient(listOf(GoldTop, GoldBottom))
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    1f,
)

internal val LocalAccentColor = staticCompositionLocalOf { Brand.Saffron }

internal fun onAccent(c: Color): Color {
    val luminance = c.luminance()
    return when {
        (luminance + 0.05f) / (Brand.Ink.luminance() + 0.05f) >= 4.5f -> Brand.Ink
        1.05f / (luminance + 0.05f) >= 4.5f -> Color.White
        else -> Color.Black
    }
}

/** Text must read even on the darkest light-theme surface container. Fills keep the original accent. */
private fun forLight(c: Color): Color {
    var x = c
    var i = 0
    val surface = Color(0xFFE3DFDA).luminance()
    while ((surface + 0.05f) / (x.luminance() + 0.05f) < 4.5f && i < 40) { x = mix(x, Color.Black, 0.08f); i++ }
    return x
}

fun hueShift(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun schemeFor(accent: Color, dark: Boolean): ColorScheme {
    val tertiarySeed = hueShift(accent, 165f)
    return if (dark) {
        val bg = Brand.Night
        darkColorScheme(
            primary = accent, onPrimary = onAccent(accent),
            primaryContainer = mix(accent, bg, 0.72f), onPrimaryContainer = mix(accent, Color.White, 0.55f),
            secondary = mix(accent, Color(0xFFB8B2AB), 0.6f), onSecondary = Brand.Ink,
            secondaryContainer = mix(accent, bg, 0.84f), onSecondaryContainer = Color(0xFFE6E1DB),
            tertiary = mix(tertiarySeed, Color.White, 0.2f), onTertiary = Brand.Ink,
            tertiaryContainer = mix(tertiarySeed, bg, 0.75f), onTertiaryContainer = mix(tertiarySeed, Color.White, 0.6f),
            background = bg, onBackground = Color(0xFFECE8E3),
            surface = bg, onSurface = Color(0xFFECE8E3),
            surfaceVariant = Color(0xFF2A2A2C), onSurfaceVariant = Color(0xFFA9A49E),
            surfaceContainerLowest = Color(0xFF0D0E10), surfaceContainerLow = Color(0xFF18191B),
            surfaceContainer = Color(0xFF1D1E20), surfaceContainerHigh = Color(0xFF242527),
            surfaceContainerHighest = Color(0xFF2C2D2F), surfaceBright = Color(0xFF333436), surfaceDim = bg,
            outline = Color(0xFF79736C), outlineVariant = Color(0xFF34332F),
            error = Color(0xFFF2756A), onError = Brand.Ink,
            errorContainer = Color(0xFF4A1E1A), onErrorContainer = Color(0xFFFFD9D3),
            inverseSurface = Color(0xFFECE8E3), inverseOnSurface = Brand.Ink, inversePrimary = forLight(accent),
        )
    } else {
        val p = forLight(accent)
        val bg = Color(0xFFFAF9F7)
        lightColorScheme(
            primary = p, onPrimary = onAccent(p),
            primaryContainer = mix(accent, Color.White, 0.62f), onPrimaryContainer = mix(p, Color.Black, 0.45f),
            secondary = mix(p, Color(0xFF5E5852), 0.6f), onSecondary = Color.White,
            secondaryContainer = mix(accent, Color.White, 0.84f), onSecondaryContainer = Brand.Ink,
            tertiary = forLight(tertiarySeed), onTertiary = Color.White,
            tertiaryContainer = mix(tertiarySeed, Color.White, 0.78f), onTertiaryContainer = mix(tertiarySeed, Color.Black, 0.6f),
            background = bg, onBackground = Color(0xFF1F1C1A),
            surface = bg, onSurface = Color(0xFF1F1C1A),
            surfaceVariant = Color(0xFFE9E5E0), onSurfaceVariant = Color(0xFF5E5852),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F2EF),
            surfaceContainer = Color(0xFFEFECE8), surfaceContainerHigh = Color(0xFFE9E6E2),
            surfaceContainerHighest = Color(0xFFE3DFDA), surfaceBright = bg, surfaceDim = Color(0xFFDCD8D3),
            outline = Color(0xFF8C857E), outlineVariant = Color(0xFFD5D0CA),
            error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFFCDAD5), onErrorContainer = Color(0xFF410E0B),
            inverseSurface = Brand.Ink, inverseOnSurface = Color(0xFFF2EDE8), inversePrimary = accent,
        )
    }
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AriesTheme(settings: ThemeSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings.mode)
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalAccentColor provides Color(settings.accentArgb)) {
        MaterialTheme(
            colorScheme = schemeFor(Color(settings.accentArgb), dark),
            typography = ConsoleTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp),
                medium = RoundedCornerShape(0.dp), large = RoundedCornerShape(0.dp), extraLarge = RoundedCornerShape(0.dp),
            ),
            content = content,
        )
    }
}

/** Agent status colours: fixed meanings, not the accent (Working uses the accent). */
@Composable
fun statusColor(s: AgentStatus?): Color {
    val c = MaterialTheme.colorScheme
    val dark = c.background.luminance() < 0.5f
    return when (s) {
        AgentStatus.Working -> c.primary
        is AgentStatus.Blocked -> if (dark) Color(0xFFFF8A65) else Color(0xFFC4441F)
        is AgentStatus.Error -> c.error
        AgentStatus.Done -> if (dark) Color(0xFF7CC891) else Color(0xFF2E7D4A)
        AgentStatus.Idle, null -> c.onSurfaceVariant
    }
}

private fun mono(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
)

/** Console names/body are 14/18sp, metadata 12/16sp; Hangul uses the system fallback. */
private val ConsoleTypography = Typography(
    displayLarge = mono(28, 34), displayMedium = mono(24, 30), displaySmall = mono(22, 28),
    headlineLarge = mono(22, 28), headlineMedium = mono(20, 26), headlineSmall = mono(18, 24),
    titleLarge = mono(16, 22, FontWeight.Medium), titleMedium = mono(14, 18, FontWeight.Medium),
    titleSmall = mono(12, 16, FontWeight.Medium),
    bodyLarge = mono(14, 18), bodyMedium = mono(14, 18), bodySmall = mono(12, 16),
    labelLarge = mono(12, 16, FontWeight.Medium), labelMedium = mono(12, 16), labelSmall = mono(11, 16),
)
