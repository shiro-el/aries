package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.BeaconUpdate

/** The Host's Beacon: version, service health, install and remove. */
@Composable
fun BeaconSection(model: BeaconModel) {
    val host = model.host ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.beacon_title), style = MaterialTheme.typography.titleMedium)
        when (val ui = model.ui) {
            BeaconUi.Loading, BeaconUi.Working -> CircularProgressIndicator()
            is BeaconUi.Unavailable -> Text(text(ui.reason))
            is BeaconUi.Known -> when (val s = ui.state) {
                BeaconState.NotInstalled -> {
                    Text(stringResource(R.string.beacon_not_installed))
                    TextButton(onClick = model::install) { Text(stringResource(R.string.beacon_install_button)) }
                }
                is BeaconState.Installed -> {
                    Text(stringResource(R.string.beacon_version, s.version))
                    Text(
                        when {
                            !s.running -> stringResource(R.string.beacon_not_running)
                            s.coreVersion == null -> stringResource(R.string.beacon_not_attached)
                            else -> stringResource(R.string.beacon_attached, s.coreVersion!!)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!s.supported) {
                        Text(
                            stringResource(R.string.beacon_out_of_range, s.version, s.allowed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    when (val u = model.update) {
                        is BeaconUpdate.Updated -> Text(
                            stringResource(R.string.beacon_updated, u.from, u.install.version),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        BeaconUpdate.Needed -> {
                            Text(
                                stringResource(R.string.beacon_update_needed, s.version, model.pinnedVersion),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = model::install) { Text(stringResource(R.string.beacon_update_button)) }
                        }
                        BeaconUpdate.NotNeeded -> {}
                    }
                    if (s.os == "macos") {
                        Text(stringResource(R.string.beacon_macos_login_limit), style = MaterialTheme.typography.bodySmall)
                    } else if (!s.linger) {
                        Text(
                            stringResource(R.string.beacon_no_linger, userOf(host)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = model::install) { Text(stringResource(R.string.beacon_reinstall_button)) }
                        TextButton(onClick = model::askRemove) { Text(stringResource(R.string.beacon_remove_button)) }
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = model.autoUpdate,
                            role = Role.Switch,
                            onValueChange = model::changeAutoUpdate,
                        ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.beacon_auto_update))
                        Switch(checked = model.autoUpdate, onCheckedChange = null)
                    }
                }
            }
        }
    }
    if (model.confirmRemove) {
        AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.beacon_remove_title)) },
            text = { Text(stringResource(R.string.beacon_remove_body, host.name)) },
            confirmButton = { TextButton(onClick = model::remove) { Text(stringResource(R.string.beacon_remove_button)) } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
    model.prompt?.let { p ->
        if (p is Prompt.Problem) {
            AlertDialog(
                onDismissRequest = model::dismiss,
                title = { Text(text(p.title)) },
                text = { Text(text(p.body)) },
                confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.ok_button)) } },
            )
        }
    }
}

@Composable
private fun text(m: Message): String = stringResource(m.res, *m.args.toTypedArray())
