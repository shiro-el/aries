package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Use the pane's available height, including IME and split-screen constraints. */
@Composable
internal fun AgentViewport(
    header: @Composable () -> Unit,
    input: @Composable (compact: Boolean) -> Unit,
    content: @Composable ColumnScope.(compact: Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
        val compact = maxHeight < 480.dp || WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.TopCenter)) {
            header()
            // Measure input outside the weighted body so notices and pickers
            // cannot consume its height, even when the transcript has no room.
            Column(Modifier.weight(1f).clipToBounds()) { content(compact) }
            input(compact)
        }
    }
}
