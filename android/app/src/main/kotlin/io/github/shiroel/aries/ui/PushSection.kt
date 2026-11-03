package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import io.github.shiroel.aries.core.PushVerificationState
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Push for the open Host: turn on (with each step's progress), test, turn off. */
@Composable
fun PushSection(model: PushModel, beaconInstalled: Boolean) {
    val host = model.host ?: return
    var beaconRequired by remember(host.id, beaconInstalled) { mutableStateOf(false) }
    LaunchedEffect(host.id, model.ui) {
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
            TextButton(onClick = { beaconRequired = true }) { Text(stringResource(R.string.push_enable_button)) }
            if (beaconRequired) Text(stringResource(R.string.push_needs_beacon), color = MaterialTheme.colorScheme.error)
            return@Column
        }
        when (val ui = model.ui) {
            PushUi.Loading, PushUi.Working -> CircularProgressIndicator()
            PushUi.Off -> {
                RelayField(model)
                TextButton(onClick = model::enable, enabled = model.relayDraft.isNotBlank()) {
                    Text(stringResource(if (model.problem == null) R.string.push_enable_button else R.string.push_retry_button))
                }
            }
            is PushUi.Enabling -> {
                if (ui.steps.isEmpty()) CircularProgressIndicator()
                ui.steps.forEach { (step, state) ->
                    Row(
                        Modifier.semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (state) {
                            StepState.Running -> {
                                val description = stringResource(R.string.push_step_running_description)
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp).semantics { contentDescription = description },
                                    strokeWidth = 2.dp,
                                )
                            }
                            StepState.Done, StepState.Failed -> Icon(
                                painterResource(if (state == StepState.Done) R.drawable.ic_check else R.drawable.ic_x),
                                contentDescription = stringResource(
                                    if (state == StepState.Done) R.string.push_step_done_description else R.string.push_step_failed_description,
                                ),
                                modifier = Modifier.size(18.dp),
                                tint = if (state == StepState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(stringResource(stepLabel(step)))
                    }
                }
                if (ui.steps.any { it.second == StepState.Failed }) {
                    TextButton(onClick = model::enable) { Text(stringResource(R.string.push_retry_button)) }
                }
            }
            is PushUi.On -> {
                Text(stringResource(R.string.push_on_body, ui.relayUrl))
                if (model.changingRelay) {
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
    PolicyOption(R.string.push_policy_blocked, policy.blocked, enabled) { on ->
        model.changePolicy { it.copy(blocked = on) }
    }
    PolicyOption(R.string.push_policy_error, policy.error, enabled) { on ->
        model.changePolicy { it.copy(error = on) }
    }
    PolicyOption(R.string.push_policy_done, policy.done, enabled) { on ->
        model.changePolicy { it.copy(done = on) }
    }
    PolicyOption(R.string.push_policy_child_agents, policy.childAgents, enabled) { on ->
        model.changePolicy { it.copy(childAgents = on) }
    }
    model.policyProblem?.let { Text(resolve(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun PolicyOption(label: Int, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
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
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
    )
}

/** A [Message] whose arguments may themselves be messages (a step's name). */
@Composable
private fun resolve(m: Message): String {
    val args = m.args.map { if (it is Message) resolve(it) else it }
    return stringResource(m.res, *args.toTypedArray())
}
