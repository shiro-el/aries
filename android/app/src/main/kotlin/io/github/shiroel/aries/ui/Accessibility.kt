package io.github.shiroel.aries.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle

/** Announce operation results without moving focus from the control that triggered them. */
@Composable
internal fun AnnouncedText(
    text: String?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    // Keep the same semantics node while a result is absent, pending and complete.
    Text(text.orEmpty(), modifier.then(if (text.isNullOrEmpty()) Modifier.height(1.dp) else Modifier)
        .semantics { liveRegion = LiveRegionMode.Polite }, color = color, style = style)
}

/** Names and locations must remain available when system text is enlarged. */
@Composable
internal fun accessibleNameLines(): Int = if (LocalDensity.current.fontScale >= 1.3f) Int.MAX_VALUE else 1

/** Screen-owned result events leave no invisible text behind in accessibility traversal. */
@Composable
internal fun <T> AnnounceChanges(value: T, message: String?) {
    val view = LocalView.current
    var previous by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (previous != value) {
            previous = value
            // Send before an item's completion effect can advance/dismiss its sheet.
            // A deferred semantics update can be lost when that item leaves composition.
            @Suppress("DEPRECATION")
            if (!message.isNullOrEmpty()) view.announceForAccessibility(message)
        }
    }
}

/** Picker values need their field name, including a full folder path when the label is shortened. */
@Composable
internal fun Modifier.pickerDescription(field: String, value: String): Modifier {
    val label = androidx.compose.ui.res.stringResource(io.github.shiroel.aries.R.string.new_task_picker_label, field, value)
    return semantics { contentDescription = label }
}

/** One polite heading reads queue changes without interrupting the preceding result. */
@Composable
internal fun QueueHeading(text: String, identity: String, modifier: Modifier = Modifier) {
    val color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val style = androidx.compose.material3.MaterialTheme.typography.titleMedium
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { context -> android.widget.TextView(context).apply {
            isAccessibilityHeading = true
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } },
        update = { view ->
            if (view.text.toString() != text) view.text = text
            view.setTextColor(color.toArgb())
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
            view.tag = identity
        },
    )
}
