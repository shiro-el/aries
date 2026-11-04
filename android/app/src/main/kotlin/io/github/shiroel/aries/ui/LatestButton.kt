package io.github.shiroel.aries.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Shared return-to-latest action; callers anchor it above their input area. */
@Composable
internal fun LatestButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier, unreadCount: Int = 0) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius),
        color = colors.surfaceContainerHigh, contentColor = colors.primary,
        border = BorderStroke(1.dp, colors.outline)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_down_to_line), description, Modifier.size(20.dp))
            if (unreadCount > 0) Text(unreadCount.toString(), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge)
        }
    }
}
