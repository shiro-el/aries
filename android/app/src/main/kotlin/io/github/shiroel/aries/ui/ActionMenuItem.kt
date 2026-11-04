package io.github.shiroel.aries.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Shared action-menu rhythm. Status and submenu affordances occupy the trailing column. */
@Composable
internal fun ActionMenuItem(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    detail: String? = null,
    submenu: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { MenuIcon(icon) },
        trailingIcon = if (detail != null || submenu) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail?.let { MenuDetail(it) }
                if (submenu) MenuIcon(R.drawable.ic_chevron_right)
            }
        }) else null,
        colors = MenuDefaults.itemColors(
            textColor = if (destructive) colors.error else colors.onSurface,
            leadingIconColor = if (destructive) colors.error else colors.onSurfaceVariant,
            trailingIconColor = colors.onSurfaceVariant,
        ),
        onClick = onClick, enabled = enabled,
        modifier = modifier.widthIn(min = 240.dp, max = 320.dp),
    )
}

@Composable
internal fun MenuIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
}

@Composable
internal fun MenuDetail(text: String) {
    Text(text, Modifier.widthIn(max = 104.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun MenuDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Informational footer, deliberately without a click action or a disabled-action appearance. */
@Composable
internal fun MenuInfo(label: String, value: String, @DrawableRes icon: Int) {
    Row(Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MenuDetail(value)
    }
}

@Composable
internal fun MenuToggleState(checked: Boolean, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuDetail(stringResource(if (checked) R.string.menu_state_on else R.string.menu_state_off))
        ConsoleToggle(checked, onCheckedChange = null, enabled = enabled)
    }
}
