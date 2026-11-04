package io.github.shiroel.aries.wear

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Shapes
import androidx.wear.compose.material.Typography

internal val WatchConsoleShape = RoundedCornerShape(WatchConsole.CORNER_RADIUS_DP.dp)

/** Detail action slots inherit the same palette, monospace type and angular shapes. */
@Composable
internal fun WatchConsoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Color(WatchConsole.SAFFRON), primaryVariant = Color(WatchConsole.SAFFRON),
            secondary = Color(WatchConsole.CARD), secondaryVariant = Color(WatchConsole.CARD),
            background = Color(WatchConsole.NIGHT), surface = Color(WatchConsole.CARD),
            error = Color(WatchConsole.ERROR), onError = Color(WatchConsole.NIGHT),
            onPrimary = Color(WatchConsole.NIGHT), onSecondary = Color(WatchConsole.TEXT),
            onBackground = Color(WatchConsole.TEXT), onSurface = Color(WatchConsole.TEXT),
        ),
        typography = Typography(defaultFontFamily = FontFamily.Monospace),
        shapes = Shapes(small = WatchConsoleShape, medium = WatchConsoleShape, large = WatchConsoleShape),
        content = content,
    )
}
