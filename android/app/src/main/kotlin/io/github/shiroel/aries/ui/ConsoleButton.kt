package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape

/** Material's CornerFull default bypasses theme shapes; actions follow the layout tokens. */
@Composable
internal fun ConsoleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = LocalAccentColor.current
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent(accent)),
        content = content,
    )
}
