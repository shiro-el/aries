package io.github.shiroel.aries.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle

/** Announce operation results without moving focus from the control that triggered them. */
@Composable
internal fun AnnouncedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    Text(text, modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = color, style = style)
}

/** Names and locations must remain available when system text is enlarged. */
@Composable
internal fun accessibleNameLines(): Int = if (LocalDensity.current.fontScale > 1.3f) Int.MAX_VALUE else 1
