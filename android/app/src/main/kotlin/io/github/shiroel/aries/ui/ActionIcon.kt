package io.github.shiroel.aries.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Shared Lucide toolbar action with a full touch target and a translated label. */
@Composable
internal fun ActionIcon(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(20.dp))
    }
}
