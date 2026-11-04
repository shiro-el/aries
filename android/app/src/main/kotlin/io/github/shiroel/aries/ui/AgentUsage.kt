package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentUsage
import io.github.shiroel.aries.core.ContextUsage
import io.github.shiroel.aries.core.UsageLimit
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Readings are scoped to this Agent; absent values never become zero. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentUsageSheet(usage: AgentUsage?, onDismiss: () -> Unit) {
    ConsoleSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("agent-usage-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.usage_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.usage_source), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.usage_context_title), style = MaterialTheme.typography.titleMedium)
            val context = usage?.context
            val unavailable = stringResource(R.string.usage_unavailable)
            Text(stringResource(R.string.usage_context_tokens,
                context?.usedTokens?.let { number(it.toDouble()) } ?: unavailable,
                context?.windowTokens?.let { number(it.toDouble()) } ?: unavailable))
            Text(stringResource(R.string.usage_updated, recordedTime(context?.updatedAt) ?: unavailable),
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(stringResource(R.string.usage_account_title), style = MaterialTheme.typography.titleMedium)
            if (usage?.limits.isNullOrEmpty()) Text(stringResource(R.string.usage_limits_unavailable))
            usage?.limits?.forEachIndexed { index, limit -> UsageLimitView(limit, index + 1) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.usage_close))
            }
        }
    }
}

@Composable
internal fun contextRemaining(context: ContextUsage?): String {
    val used = context?.usedTokens
    val window = context?.windowTokens
    if (used == null || window == null || window == 0uL) return stringResource(R.string.usage_context_unavailable)
    val remaining = ((1.0 - used.toDouble() / window.toDouble()) * 100).coerceIn(0.0, 100.0)
    val label = stringResource(R.string.usage_context_remaining, number(remaining, 1))
    val now = usageNow()
    val age = context.updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?.let { relativeAge(LocalContext.current, it.coerceAtLeast(0).toULong(), now) }
        ?: stringResource(R.string.surfaces_age_unknown)
    return stringResource(R.string.usage_context_age, label, age)
}

@Composable
private fun UsageLimitView(limit: UsageLimit, index: Int) {
    val now = usageNow()
    val unavailable = stringResource(R.string.usage_unavailable)
    val label = when (limit.name) {
        "seven_day_opus" -> stringResource(R.string.usage_week_opus)
        "seven_day_sonnet" -> stringResource(R.string.usage_week_sonnet)
        else -> when (limit.windowMinutes) {
            300uL -> stringResource(R.string.usage_five_hours)
            10080uL -> stringResource(R.string.usage_seven_days)
            null -> limit.name.takeIf { it.isNotBlank() }?.let { stringResource(R.string.usage_window_named, it) }
                ?: stringResource(R.string.usage_window_unknown, index)
            else -> stringResource(R.string.usage_window, number(limit.windowMinutes!!.toDouble()))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        val resetAt = limit.resetsAt?.takeIf { it <= Long.MAX_VALUE.toULong() }?.let {
            runCatching { Instant.ofEpochSecond(it.toLong()) }.getOrNull()
        }
        val used = limit.usedBasisPoints?.let { number(it.toDouble() / 100, 2) }
        if (resetAt != null && !resetAt.isAfter(Instant.ofEpochMilli(now))) {
            Text(if (used != null) stringResource(R.string.usage_reset_reading, used)
                else stringResource(R.string.usage_reset_unknown))
        } else {
            Text(used?.let { stringResource(R.string.usage_used, it) } ?: stringResource(R.string.usage_used_unknown))
            Text(stringResource(R.string.usage_resets, resetAt?.let { runCatching { formatTime(it) }.getOrNull() } ?: unavailable))
        }
        Text(stringResource(R.string.usage_updated, recordedTime(limit.updatedAt) ?: unavailable),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun number(value: Double, decimals: Int = 0): String = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = decimals
}.format(value)

private fun recordedTime(value: String?): String? = value?.let {
    runCatching { formatTime(Instant.parse(it)) }.getOrNull()
}

private fun formatTime(value: Instant): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(value)

@Composable
private fun usageNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    return now
}
