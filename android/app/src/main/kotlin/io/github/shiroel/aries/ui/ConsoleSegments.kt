package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Square radio segments shared by Settings and the compact Terminal/Chat toggle. */
@Composable
fun <T> ConsoleSegments(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    height: Dp = 48.dp,
    segmentWidth: Dp? = null,
    underlineHeight: Dp = 3.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface,
    label: @Composable (T) -> String,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.border(1.dp, colors.outline).selectableGroup()) {
        options.forEachIndexed { index, option ->
            if (index > 0) Box(Modifier.width(1.dp).height(height).background(colors.outline))
            val active = selected == option
            val width = if (segmentWidth == null) Modifier.weight(1f) else Modifier.width(segmentWidth)
            Box(
                width.height(height)
                    .background(if (active) colors.surfaceContainerHighest else colors.surface)
                    .selectable(active, role = Role.RadioButton, onClick = { if (!active) onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), color = if (active) colors.primary else inactiveColor,
                    style = textStyle, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                if (active) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(underlineHeight).background(colors.primary))
            }
        }
    }
}
