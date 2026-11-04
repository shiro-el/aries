package io.github.shiroel.aries.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
internal fun rememberQuietHoursSettings(): QuietHoursSettings {
    val context = LocalContext.current
    return remember(context) { QuietHoursSettings.from(context) }
}

@Composable
fun QuietHoursSection(
    settings: QuietHoursSettings = rememberQuietHoursSettings(),
    onEditTime: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val hours = settings.hours
    var localPicker by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val edit: (Boolean) -> Unit = onEditTime ?: { localPicker = it }
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
    QuietTimeButton(R.string.quiet_hours_start, "quiet-hours-start", hours.startMinute, hours.enabled) { edit(true) }
    QuietTimeButton(R.string.quiet_hours_end, "quiet-hours-end", hours.endMinute, hours.enabled) { edit(false) }
    localPicker?.let { start -> QuietHoursTimePicker(settings, start) { localPicker = null } }
}

@Composable
private fun QuietTimeButton(label: Int, tag: String, minute: Int, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    TextButton(modifier = Modifier.fillMaxWidth().testTag(tag), enabled = enabled, onClick = onClick) {
        Text(stringResource(label), Modifier.weight(1f))
        Text(quietTime(context, minute))
    }
}

private class QuietPickerState(val saved: Bundle? = null) {
    var dialog: TimePickerDialog? = null
}

private val QuietPickerSaver = Saver<QuietPickerState, Bundle>(
    // Save the clock's unconfirmed selection when Android saves the composition.
    save = { it.dialog?.onSaveInstanceState() ?: it.saved ?: Bundle() },
    restore = { QuietPickerState(it) },
)

/** Owned by the Settings screen, so neither scrolling nor recreation owns the window. */
@Composable
internal fun QuietHoursTimePicker(settings: QuietHoursSettings, start: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val close by rememberUpdatedState(onDismiss)
    val state = rememberSaveable(start, saver = QuietPickerSaver) { QuietPickerState() }
    DisposableEffect(context, settings, start) {
        val minute = if (start) settings.hours.startMinute else settings.hours.endMinute
        val picker = TimePickerDialog(context, { _, hour, minutes ->
            val selected = hour * 60 + minutes
            settings.update(if (start) settings.hours.copy(startMinute = selected) else settings.hours.copy(endMinute = selected))
        }, minute / 60, minute % 60, DateFormat.is24HourFormat(context))
        state.saved?.takeUnless { it.isEmpty }?.let(picker::onRestoreInstanceState)
        state.dialog = picker
        picker.setOnDismissListener { close() }
        picker.show()
        onDispose {
            picker.setOnDismissListener(null)
            picker.dismiss()
            state.dialog = null
        }
    }
}

private fun quietTime(context: android.content.Context, minute: Int): String =
    DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(minute * 60_000L))
