package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Shared return-to-latest action; callers anchor it above their input area. */
@Composable
internal fun LatestButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
) {
    BadgedBox(
        modifier = modifier,
        badge = {
            if (unreadCount > 0) {
                // The button's translated description already includes the count.
                Badge(Modifier.clearAndSetSemantics {}) { Text(unreadCount.toString()) }
            }
        },
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).shadow(2.dp, CircleShape),
            shape = CircleShape,
        ) {
            Icon(
                painterResource(R.drawable.ic_arrow_down_to_line),
                contentDescription = description,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
