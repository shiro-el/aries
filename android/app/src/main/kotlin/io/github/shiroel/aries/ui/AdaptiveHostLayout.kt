package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import io.github.shiroel.aries.R

/** Window-local width also handles split screen and folding without changing navigation state. */
@Composable
internal fun AdaptiveHostLayout(
    listScreen: Screen?,
    hasDetail: Boolean,
    listContent: @Composable (Screen) -> Unit,
    listHeader: @Composable (Screen) -> Unit = {},
    detailHeader: @Composable () -> Unit = {},
    detailContent: @Composable () -> Unit,
) {
    val savedLists = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(maxWidth.value, maxHeight.value)
        val wide = listScreen != null &&
            sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
            sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
        val listWidth = (maxWidth * .36f).coerceIn(240.dp, 360.dp)
        Row(Modifier.fillMaxSize()) {
            if (listScreen != null && (wide || !hasDetail)) {
                val width = if (wide) Modifier.width(listWidth) else Modifier.weight(1f)
                Column(width.fillMaxHeight().testTag("adaptive-list-pane")) {
                    val key = when (listScreen) {
                        is Screen.Browse -> "host:${listScreen.browser.host.id}"
                        is Screen.Agents -> "agents"
                        else -> "hosts"
                    }
                    savedLists.SaveableStateProvider(key) {
                        listHeader(listScreen)
                        Box(Modifier.weight(1f)) { listContent(listScreen) }
                    }
                }
            }
            if (wide) VerticalDivider()
            // Keep detail at one composition location across the compact/medium breakpoint.
            if (listScreen == null || hasDetail || wide) {
                Box(Modifier.weight(1f).fillMaxHeight().testTag("adaptive-detail-pane")) {
                    if (listScreen == null || hasDetail) {
                        Column(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.TopCenter)) {
                            detailHeader()
                            Box(Modifier.weight(1f)) { detailContent() }
                        }
                    } else Text(
                        stringResource(R.string.adaptive_choose_detail),
                        Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
