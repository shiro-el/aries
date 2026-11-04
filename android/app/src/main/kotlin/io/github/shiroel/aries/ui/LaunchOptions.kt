package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
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
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentPreset

/** Model IDs stay editable: the available models belong to the Host's Agent configuration. */
@Composable
fun LaunchOptions(form: LaunchForm, preset: AgentPreset, enabled: Boolean = true) {
    var modelsOpen by remember(preset) { mutableStateOf(false) }
    var effortsOpen by remember(preset) { mutableStateOf(false) }
    OutlinedTextField(
        value = form.model, enabled = enabled,
        onValueChange = { form.model = it },
        modifier = Modifier.fillMaxWidth().testTag("launch_model"),
        label = { Text(stringResource(R.string.launch_model_label)) },
        placeholder = { Text(stringResource(R.string.launch_agent_default)) },
        trailingIcon = {
            Box {
                TextButton(enabled = enabled, onClick = {
                    if (preset == AgentPreset.CLAUDE_CODE) modelsOpen = true else form.model = ""
                }) {
                    Text(stringResource(if (preset == AgentPreset.CLAUDE_CODE) R.string.launch_model_choose else R.string.launch_agent_default))
                }
                DropdownMenu(expanded = modelsOpen && enabled, onDismissRequest = { modelsOpen = false }) {
                    SingleChoiceMenuItem(
                        selected = form.model.isEmpty(),
                        text = { Text(stringResource(R.string.launch_agent_default)) },
                        onClick = { form.model = ""; modelsOpen = false },
                    )
                    for (alias in listOf("sonnet", "opus", "haiku", "fable")) {
                        SingleChoiceMenuItem(
                            selected = form.model == alias,
                            text = { Text(alias) },
                            onClick = { form.model = alias; modelsOpen = false },
                        )
                    }
                }
            }
        },
        singleLine = true,
    )
    if (preset == AgentPreset.OPEN_CODE) {
        return
    }
    val efforts = when (preset) {
        AgentPreset.CLAUDE_CODE -> listOf("low", "medium", "high", "xhigh", "max")
        AgentPreset.CODEX -> listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        AgentPreset.PI -> listOf("off", "minimal", "low", "medium", "high", "xhigh")
        AgentPreset.OPEN_CODE -> emptyList()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.launch_effort_label), Modifier.weight(1f))
        Box {
            TextButton(
                modifier = Modifier.testTag("launch_effort"),
                onClick = { effortsOpen = true }, enabled = enabled,
            ) { EffortText(form.effort) }
            DropdownMenu(expanded = effortsOpen && enabled, onDismissRequest = { effortsOpen = false }) {
                for (effort in listOf("") + efforts) {
                    SingleChoiceMenuItem(
                        selected = form.effort == effort,
                        text = { EffortText(effort) },
                        onClick = { form.effort = effort; effortsOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun EffortText(effort: String) {
    val label = when (effort) {
        "" -> R.string.launch_agent_default
        "off" -> R.string.launch_effort_off
        "minimal" -> R.string.launch_effort_minimal
        "low" -> R.string.launch_effort_low
        "medium" -> R.string.launch_effort_medium
        "high" -> R.string.launch_effort_high
        "xhigh" -> R.string.launch_effort_xhigh
        "max" -> R.string.launch_effort_max
        "ultra" -> R.string.launch_effort_ultra
        else -> null
    }
    Text(if (label == null) effort else stringResource(label))
}
