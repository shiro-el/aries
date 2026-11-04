package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape

/** Material's CornerFull button default bypasses theme shapes; Console actions stay square. */
@Composable
internal fun ConsoleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = LocalAccentColor.current
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent(accent)),
        content = content,
    )
}
