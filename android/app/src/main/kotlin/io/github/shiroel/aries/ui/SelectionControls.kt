package io.github.shiroel.aries.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** Selection contract: settings use switches, multiple choices use checkboxes, single choices use radios.
 * The row owns the action and accessibility state; its trailing indicator is never a second click target.
 */
@Composable
fun SingleChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
        RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
    }
}

/** Compact choices in a horizontal filter group still put the same radio at the end. */
@Composable
internal fun SingleChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(LocalLayoutTokens.current.cornerRadius),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            label()
            RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun SingleChoiceMenuItem(
    selected: Boolean,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = text, onClick = onClick, enabled = enabled,
        trailingIcon = { RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp)) },
        modifier = modifier.semantics { this.selected = selected; role = Role.RadioButton },
    )
}

@Composable
internal fun SwitchMenuItem(
    checked: Boolean,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    DropdownMenuItem(
        text = text, onClick = onClick, enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = { MenuToggleState(checked, enabled) },
        modifier = modifier.widthIn(min = 240.dp, max = 320.dp)
            .semantics { toggleableState = ToggleableState(checked); role = Role.Switch },
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
) {
    Row(
        modifier.heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f))
        ConsoleToggle(checked, onCheckedChange = null, enabled = enabled)
    }
}
