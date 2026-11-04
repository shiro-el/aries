package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface,
    label: @Composable (T) -> String,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius)
    Row(modifier.clip(shape).border(1.dp, colors.outline, shape).selectableGroup()) {
        options.forEachIndexed { index, option ->
            if (index > 0) Box(Modifier.width(1.dp).height(height).background(colors.outline))
            val active = selected == option
            val width = if (segmentWidth == null) Modifier.weight(1f) else Modifier.width(segmentWidth)
            Row(
                width.heightIn(min = height)
                    .background(if (active) colors.surfaceContainerHighest else colors.surface)
                    .selectable(active, role = Role.RadioButton, onClick = { if (!active) onSelect(option) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label(option), Modifier.weight(1f), color = if (active) colors.primary else inactiveColor,
                    style = textStyle, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                RadioButton(active, onClick = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}
