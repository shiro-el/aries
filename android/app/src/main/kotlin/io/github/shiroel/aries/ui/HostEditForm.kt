package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import kotlinx.coroutines.launch

/** Reusable in Settings' hostEditor slot: { host -> HostEditSection(model, host) }. */
@Composable
fun HostEditSection(model: HostsModel, host: Host) {
    // Settings may retain the Host that opened it; resolve the saved values by id.
    val latest = model.hosts.firstOrNull { it.id == host.id } ?: return
    if (latest.endpoint !is Endpoint.Ssh) return
    var editing by rememberSaveable(host.id) { mutableStateOf(false) }
    if (editing) {
        HostEditForm(model, latest, onClose = { editing = false })
    } else {
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag("host-edit:${host.id}")) {
            Text(stringResource(R.string.host_edit_button))
        }
    }
}

/** An inline Console form, kept independent of either Host rows or Settings navigation. */
@Composable
fun HostEditForm(model: HostsModel, host: Host, onClose: () -> Unit) {
    val endpoint = host.endpoint as? Endpoint.Ssh ?: return
    var name by rememberSaveable(host.id) { mutableStateOf(host.name) }
    var address by rememberSaveable(host.id) { mutableStateOf(endpoint.address) }
    var port by rememberSaveable(host.id) { mutableStateOf(endpoint.port.toString()) }
    var user by rememberSaveable(host.id) { mutableStateOf(endpoint.user) }
    var saving by remember(host.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.host_edit_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, Modifier.testTag("host-edit-name"), enabled = !saving, label = { Text(stringResource(R.string.host_add_name)) }, singleLine = true)
        OutlinedTextField(address, { address = it }, Modifier.testTag("host-edit-address"), enabled = !saving, label = { Text(stringResource(R.string.host_add_address)) }, singleLine = true)
        OutlinedTextField(
            port, { port = it }, Modifier.testTag("host-edit-port"), enabled = !saving,
            label = { Text(stringResource(R.string.host_add_port)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(user, { user = it }, Modifier.testTag("host-edit-user"), enabled = !saving, label = { Text(stringResource(R.string.host_add_user)) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConsoleButton(
                modifier = Modifier.testTag("host-edit-save"),
                enabled = !saving && validHostInput(model.hosts, host.id, name, address, portNumber, user),
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            if (model.saveHost(host, name, address, portNumber ?: 22, user)) onClose()
                        } finally { saving = false }
                    }
                },
            ) { Text(stringResource(R.string.host_edit_save_button)) }
            TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.testTag("host-edit-cancel")) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    }
}

internal fun validHostInput(hosts: List<Host>, except: String?, name: String, address: String, port: Int?, user: String): Boolean {
    val label = name.trim().ifBlank { address.trim() }
    fun token(value: String) = value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() }
    return label.isNotEmpty() && label.none { it.isISOControl() } &&
        hosts.none { it.id != except && it.name == label } &&
        token(address.trim()) && token(user.trim()) && port != null
}
