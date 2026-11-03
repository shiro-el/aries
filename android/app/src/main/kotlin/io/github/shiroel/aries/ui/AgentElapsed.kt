package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.shiroel.aries.R
import kotlinx.coroutines.delay

enum class AgentTimeUnit { LessThanMinute, Minute, Hour, Day }
data class AgentDuration(val value: Long, val unit: AgentTimeUnit)

/** Wall clock skew cannot make a negative age; absent timestamps stay unknown. */
fun agentDuration(statusMs: ULong?, nowMillis: Long): AgentDuration? {
    if (statusMs == null) return null
    val now = nowMillis.coerceAtLeast(0).toULong()
    val minutes = (if (now > statusMs) now - statusMs else 0uL) / 60_000uL
    return when {
        minutes < 1uL -> AgentDuration(0, AgentTimeUnit.LessThanMinute)
        minutes < 60uL -> AgentDuration(minutes.toLong(), AgentTimeUnit.Minute)
        minutes < 1_440uL -> AgentDuration((minutes / 60uL).toLong(), AgentTimeUnit.Hour)
        else -> AgentDuration((minutes / 1_440uL).toLong(), AgentTimeUnit.Day)
    }
}

/** Refreshes visible elapsed times every 30 seconds, like herdr-app. */
@Composable
internal fun AgentElapsedTime(
    statusMs: ULong?,
    @StringRes unknownValue: Int = R.string.console_agent_age_unknown,
    @StringRes unknownLabel: Int = R.string.console_agent_age_unavailable,
) {
    val now by produceState(System.currentTimeMillis(), statusMs) {
        if (statusMs != null) while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val duration = agentDuration(statusMs, now)
    val text = when (duration?.unit) {
        null -> stringResource(unknownValue)
        AgentTimeUnit.LessThanMinute -> stringResource(R.string.agents_elapsed_less_than_minute)
        AgentTimeUnit.Minute -> stringResource(R.string.agents_elapsed_minutes, duration.value)
        AgentTimeUnit.Hour -> stringResource(R.string.agents_elapsed_hours, duration.value)
        AgentTimeUnit.Day -> stringResource(R.string.agents_elapsed_days, duration.value)
    }
    val label = if (duration == null) stringResource(unknownLabel) else stringResource(R.string.agents_elapsed_label, text)
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = label })
}
