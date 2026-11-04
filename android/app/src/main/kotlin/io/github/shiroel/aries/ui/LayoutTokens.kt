package io.github.shiroel.aries.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Surface roles, independent of the user's accent and light/dark preference. */
internal data class LayoutPalette(
    val background: Color,
    val foreground: Color,
    val surface: Color,
    val raisedSurface: Color,
    val muted: Color,
    val outline: Color,
    val divider: Color,
    val diff: DiffColors? = null,
)

internal data class DiffColors(val added: Color, val removed: Color)

internal data class LayoutTokens(
    val light: LayoutPalette,
    val dark: LayoutPalette,
    val bodyFont: FontFamily,
    val bodySize: TextUnit,
    val bodyLineHeight: TextUnit,
    val contentPadding: Dp,
    val itemSpacing: Dp,
    val cornerRadius: Dp,
)

/** Terminal cells always use these colours, regardless of layout, theme or accent. */
internal object TerminalColors {
    val background = Color(0xFF101214)
    val foreground = Color(0xFFD8DADC)
}

private val ConsoleTokens = LayoutTokens(
    light = LayoutPalette(Color(0xFFFAF9F7), Color(0xFF1F1C1A), Color(0xFFEFECE8), Color(0xFFE3DFDA),
        Color(0xFF5E5852), Color(0xFF8C857E), Color(0xFFD5D0CA)),
    dark = LayoutPalette(Color(0xFF121315), Color(0xFFECE8E3), Color(0xFF1D1E20), Color(0xFF2C2D2F),
        Color(0xFFA9A49E), Color(0xFF79736C), Color(0xFF34332F)),
    bodyFont = FontFamily.Monospace, bodySize = 14.sp, bodyLineHeight = 18.sp,
    contentPadding = 16.dp, itemSpacing = 8.dp, cornerRadius = 0.dp,
)

private val ThreadTokens = LayoutTokens(
    light = LayoutPalette(Color(0xFFF7F4EE), Color(0xFF2B2724), Color(0xFFFFFFFF), Color(0xFFEFE9DF),
        Color(0xFF6E655D), Color(0xFF8A8178), Color(0xFFE2DACB)),
    dark = LayoutPalette(Color(0xFF1E1B19), Color(0xFFECE6DC), Color(0xFF282421), Color(0xFF312C28),
        Color(0xFFB8AEA3), Color(0xFF958B80), Color(0xFF3A342F)),
    bodyFont = FontFamily.SansSerif, bodySize = 15.sp, bodyLineHeight = 22.sp,
    contentPadding = 16.dp, itemSpacing = 8.dp, cornerRadius = 10.dp,
)

private val TasksTokens = LayoutTokens(
    light = LayoutPalette(Color(0xFFF6F5F2), Color(0xFF1B1B1A), Color(0xFFFFFFFF), Color(0xFFEEECE7),
        Color(0xFF6B6862), Color(0xFF8E8B86), Color(0xFFE1DED7),
        diff = DiffColors(Color(0xFF2E7D4A), Color(0xFFB3261E))),
    dark = LayoutPalette(Color(0xFF151617), Color(0xFFE9E7E3), Color(0xFF1F2022), Color(0xFF26272A),
        Color(0xFFB0ADA7), Color(0xFF8E8B86), Color(0xFF3A3B3F),
        diff = DiffColors(Color(0xFF7CC891), Color(0xFFF2756A))),
    bodyFont = FontFamily.SansSerif, bodySize = 14.sp, bodyLineHeight = 20.sp,
    contentPadding = 16.dp, itemSpacing = 6.dp, cornerRadius = 6.dp,
)

internal fun tokensFor(layout: AppLayout): LayoutTokens = when (layout) {
    AppLayout.CONSOLE -> ConsoleTokens
    AppLayout.THREAD -> ThreadTokens
    AppLayout.TASKS -> TasksTokens
}

internal val LocalLayoutTokens = staticCompositionLocalOf { ConsoleTokens }
