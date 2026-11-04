package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentUsage
import io.github.shiroel.aries.core.ContextUsage
import io.github.shiroel.aries.core.UsageLimit
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Readings are scoped to this Agent; absent values never become zero. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentUsageHeader(usage: AgentUsage?) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(contextRemaining(usage?.context), Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.usage_title)) }
    }
    HorizontalDivider()
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
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
            usage?.limits?.forEach { limit -> UsageLimitView(limit) }
            TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.usage_close))
            }
        }
    }
}

@Composable
private fun contextRemaining(context: ContextUsage?): String {
    val used = context?.usedTokens
    val window = context?.windowTokens
    if (used == null || window == null || window == 0uL) return stringResource(R.string.usage_context_unavailable)
    val remaining = ((1.0 - used.toDouble() / window.toDouble()) * 100).coerceIn(0.0, 100.0)
    return stringResource(R.string.usage_context_remaining, number(remaining, 1))
}

@Composable
private fun UsageLimitView(limit: UsageLimit) {
    val unavailable = stringResource(R.string.usage_unavailable)
    val label = when (limit.name) {
        "seven_day_opus" -> stringResource(R.string.usage_week_opus)
        "seven_day_sonnet" -> stringResource(R.string.usage_week_sonnet)
        else -> when (limit.windowMinutes) {
            300uL -> stringResource(R.string.usage_five_hours)
            10080uL -> stringResource(R.string.usage_seven_days)
            null -> stringResource(R.string.usage_window_unknown)
            else -> stringResource(R.string.usage_window, number(limit.windowMinutes!!.toDouble()))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(limit.usedBasisPoints?.let { stringResource(R.string.usage_used, number(it.toDouble() / 100, 2)) }
            ?: stringResource(R.string.usage_used_unknown))
        val reset = limit.resetsAt?.takeIf { it <= Long.MAX_VALUE.toULong() }?.let {
            runCatching { formatTime(Instant.ofEpochSecond(it.toLong())) }.getOrNull()
        }
        Text(stringResource(R.string.usage_resets, reset ?: unavailable))
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
