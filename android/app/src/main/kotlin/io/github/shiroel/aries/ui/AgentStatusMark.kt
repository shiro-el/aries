package io.github.shiroel.aries.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus

/** Shared Console mark: a fixed slot, one spoken status word, no chip. */
@Composable
fun AgentStatusMark(status: AgentStatus, modifier: Modifier = Modifier) {
    val label = stringResource(if (status is AgentStatus.Blocked) R.string.agent_status_blocked else agentStatusLabel(status))
    val color = statusColor(status)
    // InfiniteTransition observes Compose's MotionDurationScale. At scale zero it
    // uses the target value, so both endpoints keep the cursor visible.
    val cursor = if (status == AgentStatus.Working) {
        val transition = rememberInfiniteTransition(label = label)
        val alpha by transition.animateFloat(
            initialValue = 1f, targetValue = 1f,
            animationSpec = infiniteRepeatable(keyframes {
                durationMillis = 1100
                1f at 0
                1f at 549
                0f at 550
                0f at 1099
                1f at 1100
            }), label = label,
        )
        alpha
    } else 1f
    Box(modifier.size(24.dp).clearAndSetSemantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        when (status) {
            AgentStatus.Working, AgentStatus.Done -> Canvas(Modifier.size(16.dp)) {
                val unit = size.width / 16f
                val path = Path().apply {
                    if (status == AgentStatus.Working) {
                        moveTo(3 * unit, 4 * unit); lineTo(7 * unit, 8 * unit); lineTo(3 * unit, 12 * unit)
                    } else {
                        moveTo(2.75f * unit, 8.5f * unit); lineTo(6.25f * unit, 12 * unit); lineTo(13.25f * unit, 4.5f * unit)
                    }
                }
                drawPath(path, color, style = Stroke(1.75f * unit, cap = StrokeCap.Square, join = StrokeJoin.Miter))
                if (status == AgentStatus.Working) drawLine(
                    color, Offset(9.5f * unit, 12.25f * unit), Offset(13.5f * unit, 12.25f * unit),
                    strokeWidth = 1.75f * unit, cap = StrokeCap.Square, alpha = cursor,
                )
            }
            else -> Text(
                when (status) { is AgentStatus.Blocked -> "?"; is AgentStatus.Error -> "!"; else -> "-" }, // i18n-ok: status glyphs with localized semantics
                color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            )
        }
    }
}
