package io.github.shiroel.aries.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.shiroel.aries.QuietHoursSettings
import io.github.shiroel.aries.R
import java.util.Date
import java.util.TimeZone

@Composable
fun QuietHoursSection() {
    val context = LocalContext.current
    val settings = remember(context) { QuietHoursSettings.from(context) }
    val hours = settings.hours
    Row(
        Modifier.fillMaxWidth().testTag("quiet-hours-enabled")
            .toggleable(hours.enabled, role = Role.Switch) { settings.update(hours.copy(enabled = it)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.quiet_hours_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        ConsoleToggle(checked = hours.enabled, onCheckedChange = null)
    }
    Text(stringResource(R.string.quiet_hours_body), style = MaterialTheme.typography.bodySmall)
    if (hours.enabled) Text(
        if (hours.startMinute == hours.endMinute) stringResource(R.string.quiet_hours_all_day)
        else stringResource(R.string.quiet_hours_range, quietTime(context, hours.startMinute), quietTime(context, hours.endMinute)),
        style = MaterialTheme.typography.bodySmall,
    )
    QuietTimeButton(R.string.quiet_hours_start, "quiet-hours-start", hours.startMinute, hours.enabled) {
        settings.update(settings.hours.copy(startMinute = it))
    }
    QuietTimeButton(R.string.quiet_hours_end, "quiet-hours-end", hours.endMinute, hours.enabled) {
        settings.update(settings.hours.copy(endMinute = it))
    }
}

@Composable
private fun QuietTimeButton(label: Int, tag: String, minute: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val time = quietTime(context, minute)
    TextButton(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        enabled = enabled,
        onClick = {
            TimePickerDialog(context, { _, hour, minutes -> onChange(hour * 60 + minutes) },
                minute / 60, minute % 60, DateFormat.is24HourFormat(context)).show()
        },
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Text(time)
    }
}

private fun quietTime(context: android.content.Context, minute: Int): String =
    DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(minute * 60_000L))
