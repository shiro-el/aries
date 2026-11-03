package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Push for the open Host: turn on (with each step's progress), test, turn off. */
@Composable
fun PushSection(model: PushModel, beaconInstalled: Boolean) {
    model.host ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.push_title), style = MaterialTheme.typography.titleMedium)
        if (!beaconInstalled) {
            Text(stringResource(R.string.push_needs_beacon))
            return@Column
        }
        when (val ui = model.ui) {
            PushUi.Loading, PushUi.Working -> CircularProgressIndicator()
            PushUi.NoRelay -> Text(stringResource(R.string.push_no_relay))
            PushUi.Off -> {
                Text(stringResource(R.string.push_off_body))
                Text(stringResource(R.string.push_force_stop_note), style = MaterialTheme.typography.bodySmall)
                Button(onClick = model::enable) {
                    Text(stringResource(if (model.problem == null) R.string.push_enable_button else R.string.push_retry_button))
                }
            }
            is PushUi.Enabling -> {
                if (ui.steps.isEmpty()) CircularProgressIndicator()
                ui.steps.forEach { (step, state) ->
                    val format = when (state) {
                        StepState.Running -> R.string.push_step_running
                        StepState.Done -> R.string.push_step_done
                        StepState.Failed -> R.string.push_step_failed
                    }
                    Text(stringResource(format, stringResource(stepLabel(step))))
                }
                if (ui.steps.any { it.second == StepState.Failed }) {
                    Button(onClick = model::enable) { Text(stringResource(R.string.push_retry_button)) }
                }
            }
            is PushUi.On -> {
                Text(stringResource(R.string.push_on_body, ui.relayUrl))
                Text(stringResource(R.string.push_force_stop_note), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::sendTest) { Text(stringResource(R.string.push_test_button)) }
                    OutlinedButton(onClick = model::disable) { Text(stringResource(R.string.push_disable_button)) }
                }
            }
        }
        model.problem?.let { Text(resolve(it), color = MaterialTheme.colorScheme.error) }
        model.notice?.let { Text(resolve(it), style = MaterialTheme.typography.bodySmall) }
    }
}

/** A [Message] whose arguments may themselves be messages (a step's name). */
@Composable
private fun resolve(m: Message): String {
    val context = LocalContext.current
    val args = m.args.map { if (it is Message) context.getString(it.res, *it.args.toTypedArray()) else it }
    return stringResource(m.res, *args.toTypedArray())
}
