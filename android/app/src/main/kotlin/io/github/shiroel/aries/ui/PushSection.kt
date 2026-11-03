package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.shiroel.aries.core.PushVerificationState
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Push for the open Host: turn on (with each step's progress), test, turn off. */
@Composable
fun PushSection(model: PushModel, beaconInstalled: Boolean) {
    model.host ?: return
    LaunchedEffect(model.host?.id, model.ui) {
        do {
            model.refreshVerification()
            if (model.verificationState != PushVerificationState.PENDING) break
            delay(1_000)
        } while (true)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.push_title), style = MaterialTheme.typography.titleMedium)
        if (!beaconInstalled) {
            Text(stringResource(R.string.push_needs_beacon))
            return@Column
        }
        when (val ui = model.ui) {
            PushUi.Loading, PushUi.Working -> CircularProgressIndicator()
            PushUi.Off -> {
                Text(stringResource(R.string.push_off_body))
                Text(stringResource(R.string.push_force_stop_note), style = MaterialTheme.typography.bodySmall)
                if (!model.hasDefaultRelay) Text(stringResource(R.string.push_no_relay))
                RelayField(model)
                TextButton(onClick = model::enable, enabled = model.relayDraft.isNotBlank()) {
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
                    TextButton(onClick = model::enable) { Text(stringResource(R.string.push_retry_button)) }
                }
            }
            is PushUi.On -> {
                Text(stringResource(R.string.push_on_body, ui.relayUrl))
                Text(stringResource(R.string.push_force_stop_note), style = MaterialTheme.typography.bodySmall)
                if (model.changingRelay) {
                    Text(stringResource(R.string.push_change_relay_body), style = MaterialTheme.typography.bodySmall)
                    RelayField(model)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = model::enable, enabled = model.relayDraft.isNotBlank()) {
                            Text(stringResource(R.string.push_use_relay_button))
                        }
                        TextButton(onClick = model::cancelRelayChange) { Text(stringResource(R.string.cancel_button)) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = model::sendTest) { Text(stringResource(R.string.push_test_button)) }
                        TextButton(onClick = model::disable) { Text(stringResource(R.string.push_disable_button)) }
                    }
                    TextButton(onClick = model::changeRelay) { Text(stringResource(R.string.push_change_relay_button)) }
                }
                PolicyOptions(model)
            }
        }
        when (model.verificationState) {
            PushVerificationState.PENDING -> Text(stringResource(R.string.push_verification_pending))
            PushVerificationState.FAILED -> {
                Text(stringResource(R.string.push_verification_failed), color = MaterialTheme.colorScheme.error)
                if (model.ui is PushUi.On) {
                    TextButton(onClick = model::enable) { Text(stringResource(R.string.push_retry_button)) }
                }
            }
            else -> Unit
        }
        model.problem?.let { Text(resolve(it), color = MaterialTheme.colorScheme.error) }
        model.notice?.let { Text(resolve(it), style = MaterialTheme.typography.bodySmall) }
    }
}

/** Which Agent statuses the Host notifies about; saved on the Host through its Beacon. */
@Composable
private fun PolicyOptions(model: PushModel) {
    Text(stringResource(R.string.push_policy_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.push_policy_body), style = MaterialTheme.typography.bodySmall)
    val policy = model.policy
    if (policy == null) {
        if (model.policyBusy) CircularProgressIndicator()
        model.policyProblem?.let {
            Text(resolve(it), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = model::loadPolicy) { Text(stringResource(R.string.push_policy_retry)) }
        }
        return
    }
    val enabled = !model.policyBusy
    PolicyOption(R.string.push_policy_blocked, R.string.push_policy_blocked_hint, policy.blocked, enabled) { on ->
        model.changePolicy { it.copy(blocked = on) }
    }
    PolicyOption(R.string.push_policy_error, R.string.push_policy_error_hint, policy.error, enabled) { on ->
        model.changePolicy { it.copy(error = on) }
    }
    PolicyOption(R.string.push_policy_done, R.string.push_policy_done_hint, policy.done, enabled) { on ->
        model.changePolicy { it.copy(done = on) }
    }
    PolicyOption(R.string.push_policy_child_agents, R.string.push_policy_child_agents_hint, policy.childAgents, enabled) { on ->
        model.changePolicy { it.copy(childAgents = on) }
    }
    model.policyProblem?.let { Text(resolve(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun PolicyOption(label: Int, hint: Int, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label))
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}

/** The Relay this Host's push goes through: this build's default or a self-hosted one. */
@Composable
private fun RelayField(model: PushModel) {
    OutlinedTextField(
        model.relayDraft, { model.relayDraft = it },
        modifier = Modifier.fillMaxWidth().testTag("push_relay_url"),
        label = { Text(stringResource(R.string.push_relay_label)) },
        placeholder = { Text(stringResource(R.string.push_relay_placeholder)) },
        supportingText = { Text(stringResource(R.string.push_relay_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
    )
}

/** A [Message] whose arguments may themselves be messages (a step's name). */
@Composable
private fun resolve(m: Message): String {
    val context = LocalContext.current
    val args = m.args.map { if (it is Message) context.getString(it.res, *it.args.toTypedArray()) else it }
    return stringResource(m.res, *args.toTypedArray())
}
