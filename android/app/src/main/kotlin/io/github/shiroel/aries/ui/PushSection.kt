package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.PushStep
import io.github.shiroel.aries.core.PushVerificationState
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Push pairing is local state; only a new pairing depends on Beacon availability. */
@Composable
fun PushSection(model: PushModel, beaconState: BeaconUi, showDialogs: Boolean = true) {
    val host = model.host ?: return
    var advanced by rememberSaveable(host.id) { mutableStateOf(false) }
    LaunchedEffect(host.id, model.ui) {
        do {
            model.refreshVerification()
            if (model.verificationState != PushVerificationState.PENDING) break
            delay(1_000)
        } while (true)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.push_title), style = MaterialTheme.typography.titleMedium)
        when (val ui = model.ui) {
            PushUi.Loading, PushUi.Working -> ConsoleBusyIndicator()
            PushUi.Off -> {
                val missing = (beaconState as? BeaconUi.Known)?.state == BeaconState.NotInstalled
                val checking = beaconState == BeaconUi.Loading || beaconState == BeaconUi.Working
                when {
                    missing -> Text(stringResource(R.string.push_needs_beacon))
                    checking -> Text(stringResource(R.string.push_checking_beacon))
                    else -> Text(stringResource(R.string.push_off_summary))
                }
                RelayField(model)
                TextButton(onClick = model::enable, enabled = !missing && !checking && !model.busy && model.relayDraft.isNotBlank()) {
                    Text(stringResource(if (model.problem == null) R.string.push_enable_button else R.string.push_retry_button))
                }
            }
            is PushUi.Enabling -> PushSteps(ui.steps)
            is PushUi.On -> {
                Text(stringResource(R.string.push_on_summary))
                if (advanced || model.changingRelay) {
                    Text(stringResource(R.string.push_on_body, ui.relayUrl), style = MaterialTheme.typography.bodySmall)
                    if (model.changingRelay) {
                        RelayField(model)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = model::enable, enabled = !model.busy && model.relayDraft.isNotBlank()) {
                                Text(stringResource(R.string.push_use_relay_button))
                            }
                            TextButton(onClick = model::cancelRelayChange, enabled = !model.busy) { Text(stringResource(R.string.cancel_button)) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = model::sendTest, enabled = !model.busy) { Text(stringResource(R.string.push_test_button)) }
                            TextButton(onClick = model::askDisable, enabled = !model.busy) { Text(stringResource(R.string.push_disable_button)) }
                        }
                        TextButton(onClick = model::changeRelay, enabled = !model.busy) { Text(stringResource(R.string.push_change_relay_button)) }
                    }
                    PolicyOptions(model)
                }
            }
        }
        if (model.ui is PushUi.Off || model.ui is PushUi.On) {
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("push-advanced")) {
                Text(stringResource(if (advanced) R.string.settings_less_button else R.string.settings_advanced_button))
            }
        }
        if (model.testing) {
            ConsoleBusyIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            AnnouncedText(stringResource(R.string.push_testing))
        }
        if (model.failedSteps.isNotEmpty() && model.ui !is PushUi.Enabling) PushSteps(model.failedSteps)
        when (model.verificationState) {
            PushVerificationState.PENDING -> AnnouncedText(stringResource(R.string.push_verification_pending))
            PushVerificationState.FAILED -> {
                AnnouncedText(stringResource(R.string.push_verification_failed), color = MaterialTheme.colorScheme.error)
                if (model.ui is PushUi.On) {
                    TextButton(onClick = model::enable, enabled = !model.busy) { Text(stringResource(R.string.push_retry_button)) }
                }
            }
            else -> Unit
        }
        model.problem?.let { AnnouncedText(resolve(it), color = MaterialTheme.colorScheme.error) }
        model.notice?.let { AnnouncedText(resolve(it), style = MaterialTheme.typography.bodySmall) }
    }
    if (showDialogs) PushDialogs(model)
}

@Composable
internal fun PushDialogs(model: PushModel) {
    val host = model.host ?: return
    if (model.confirmDisable) {
        AlertDialog(
            onDismissRequest = model::cancelDisable,
            title = { Text(stringResource(R.string.push_disable_title, host.name)) },
            text = { Text(stringResource(R.string.push_disable_body)) },
            confirmButton = { TextButton(onClick = model::disable) { Text(stringResource(R.string.push_disable_button)) } },
            dismissButton = { TextButton(onClick = model::cancelDisable) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}

@Composable
private fun PushSteps(steps: List<Pair<PushStep, StepState>>) {
    if (steps.isEmpty()) ConsoleBusyIndicator()
    steps.forEach { (step, state) ->
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                StepState.Running -> {
                    val description = stringResource(R.string.push_step_running_description)
                    ConsoleBusyIndicator(
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
}

/** Which Agent statuses the Host notifies about; saved on the Host through its Beacon. */
@Composable
private fun PolicyOptions(model: PushModel) {
    Text(stringResource(R.string.push_policy_title), style = MaterialTheme.typography.titleSmall)
    val policy = model.policy
    if (policy == null) {
        if (model.policyBusy) ConsoleBusyIndicator()
        model.policyProblem?.let {
            AnnouncedText(resolve(it), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = model::loadPolicy) { Text(stringResource(R.string.push_policy_retry)) }
        }
        return
    }
    val enabled = !model.policyBusy && !model.busy
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
    model.policyProblem?.let { AnnouncedText(resolve(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun PolicyOption(label: Int, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        ConsoleToggle(checked = on, onCheckedChange = null, enabled = enabled)
    }
}

/** The Relay this Host's push goes through: this build's default or a self-hosted one. */
@Composable
private fun RelayField(model: PushModel) {
    ConsoleField(
        model.relayDraft, { model.relayDraft = it },
        modifier = Modifier.fillMaxWidth().testTag("push_relay_url"),
        label = { Text(stringResource(R.string.push_relay_label)) },
        placeholder = { Text(stringResource(R.string.push_relay_placeholder)) },
        singleLine = true,
        isError = model.relayProblem != null,
        supportingText = when {
            model.relayProblem != null -> ({ AnnouncedText(resolve(model.relayProblem!!)) })
            model.relayDraft.isBlank() -> ({ Text(stringResource(R.string.push_relay_required)) })
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
    )
}

/** A [Message] whose arguments may themselves be messages (a step's name). */
@Composable
private fun resolve(m: Message): String {
    val args = m.args.map { if (it is Message) resolve(it) else it }
    return stringResource(m.res, *args.toTypedArray())
}
