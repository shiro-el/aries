package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import io.github.shiroel.aries.core.KeyProtection
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.QrMatrix

@Composable
fun AriesApp(model: HostsModel) {
    // Calls are followed (for the banner) only while the app is in front.
    LifecycleStartEffect(model) {
        model.resumeCalls()
        onStopOrDispose { model.pauseCalls() }
    }
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            model.calls.banner?.let { entry ->
                CallBanner(entry, onView = model::openCalls, onDismiss = model.calls::dismissBanner)
            }
            when (val s = model.screen) {
                Screen.Hosts -> HostListScreen(model)
                is Screen.Connecting -> Connecting(s.host)
                is Screen.Browse -> {
                    BackHandler {
                        if (s.browser.openSpaceId != null) s.browser.closeSpace() else model.back()
                    }
                    BrowseScreen(
                        s.browser,
                        onBack = model::back,
                        onOpen = model::openTerminal,
                        onOpenChat = { model.openChat(s.browser.host, it) },
                        onNewTerminal = { model.newTerminal(s.browser) },
                        onLaunch = { model.launch(s.browser, it) },
                        onSaveLaunch = { model.saveLaunch(s.browser, it) },
                        onDeleteLaunch = { form, saved -> model.deleteSavedLaunch(s.browser, form, saved) },
                        onRunSaved = { model.runSaved(s.browser, it) },
                        hostSections = {
                            BeaconSection(model.beacon)
                            PushSection(model.push, beaconInstalled = model.beacon.installed)
                        },
                    )
                }
                is Screen.Terminal -> {
                    BackHandler { model.closeTerminal() }
                    TerminalScreen(s.viewer, onBack = model::closeTerminal)
                }
                is Screen.JumpHosts -> {
                    BackHandler { model.back() }
                    JumpHostsScreen(model, s.host, s.jumps, s.hostKeyId)
                }
                is Screen.Agents -> {
                    BackHandler {
                        if (s.board.openKey != null) s.board.closeDetail() else model.back()
                    }
                    AgentsScreen(
                        s.board,
                        onBack = model::back,
                        onOpen = { model.openAgent(s.board, it) },
                        onPin = { entry, pinned -> model.pin(s.board, entry, pinned) },
                        onTerminal = { model.openTerminal(it.terminal) },
                        onChat = { model.openChat(it.host, it.terminal) },
                    )
                }
                Screen.Calls -> {
                    BackHandler { model.back() }
                    CallsScreen(model.calls, onBack = model::back, onTerminal = model::openCallTerminal)
                }
                is Screen.Chat -> {
                    BackHandler { model.closeChat() }
                    ChatScreen(s.chat, onBack = model::closeChat)
                }
            }
        }
        model.prompt?.let { PromptDialog(it, model) }
    }
}

@Composable
private fun HostListScreen(model: HostsModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // The Agents entry shares the title row so the Host list stays reachable.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.hosts_title), style = MaterialTheme.typography.headlineMedium)
                if (model.hosts.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = model::openCalls) { Text(stringResource(R.string.calls_button)) }
                        Button(onClick = model::openAgents) { Text(stringResource(R.string.agents_button)) }
                    }
                }
            }
        }
        item { KeySection(model) }
        item { HorizontalDivider() }
        if (model.hosts.isEmpty()) {
            item { Text(stringResource(R.string.hosts_empty)) }
        }
        items(model.hosts, key = { it.id }) { host ->
            HostRow(host, onRemove = { model.askRemove(host) }) { model.connect(host) }
            JumpSummary(model.jumps[host.id].orEmpty())
            if (host.endpoint is Endpoint.Ssh) {
                TextButton(onClick = { model.editJumps(host) }) { Text(stringResource(R.string.host_jumps_button)) }
                TextButton(onClick = { model.askPassword(host) }) {
                    Text(stringResource(R.string.host_install_key_button))
                }
            }
        }
        if (model.key != null) item { ExtraKeysSection(model) }
        item { HorizontalDivider() }
        item { AddHostForm(model) }
    }
}

@Composable
private fun KeySection(model: HostsModel) {
    val key = model.key
    if (key == null) {
        Text(stringResource(R.string.key_missing))
        Button(onClick = model::createKey) { Text(stringResource(R.string.key_create_button)) }
        return
    }
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.key_clip_label)
    Text(stringResource(R.string.key_title), style = MaterialTheme.typography.titleMedium)
    Text(key.openssh, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.key_fingerprint, key.fingerprint), style = MaterialTheme.typography.bodySmall)
    Button(onClick = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(clipLabel, key.openssh))
    }) { Text(stringResource(R.string.key_copy_button)) }
    Button(onClick = model::showKeyQr) { Text(stringResource(R.string.key_qr_button)) }
    Text(stringResource(R.string.key_copy_hint), style = MaterialTheme.typography.bodySmall)
    BiometricToggle(model.keyProtection == KeyProtection.BIOMETRIC, model::requireBiometric)
}

@Composable
private fun BiometricToggle(required: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = required, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.key_biometric_label), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.key_biometric_hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = required, onCheckedChange = null)
    }
}

@Composable
private fun HostRow(host: Host, onRemove: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 8.dp)) {
            Text(host.name, style = MaterialTheme.typography.titleMedium)
            val where = when (val e = host.endpoint) {
                is Endpoint.Ssh -> stringResource(R.string.host_ssh_address, e.user, e.address, e.port.toInt())
                is Endpoint.Local -> stringResource(R.string.host_local_address)
            }
            Text(where, style = MaterialTheme.typography.bodySmall)
        }
        val removeLabel = stringResource(R.string.host_remove_button_label, host.name)
        TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = removeLabel }) {
            Text(stringResource(R.string.host_remove_button))
        }
    }
}

@Composable
private fun AddHostForm(model: HostsModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("") }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    Text(stringResource(R.string.host_add_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.host_add_name)) }, singleLine = true)
    OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.host_add_address)) }, singleLine = true)
    OutlinedTextField(
        port, { port = it },
        label = { Text(stringResource(R.string.host_add_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.host_add_user)) }, singleLine = true)
    Button(
        enabled = address.isNotBlank() && user.isNotBlank() && portNumber != null,
        onClick = {
            model.addHost(name, address, portNumber ?: 22, user)
            name = ""; address = ""; port = "22"; user = ""
        },
    ) { Text(stringResource(R.string.host_add_button)) }
}

@Composable
private fun Connecting(host: Host) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.connecting, host.name))
    }
}

@Composable
private fun PromptDialog(prompt: Prompt, model: HostsModel) {
    when (prompt) {
        is Prompt.TrustHostKey -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.host_key_trust_title)) },
            text = { Text(stringResource(R.string.host_key_trust_body, prompt.host.name, prompt.fingerprint)) },
            confirmButton = { TextButton(onClick = { model.trust(prompt) }) { Text(stringResource(R.string.host_key_trust_button)) } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
        )
        is Prompt.TrustJumpHostKey -> TrustJumpHostKeyDialog(prompt, model)
        is Prompt.Password -> PasswordDialog(prompt, model)
        is Prompt.KeyQr -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.key_qr_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val description = stringResource(R.string.key_qr_description)
                    QrCode(prompt.qr, Modifier.testTag("key_qr").semantics { contentDescription = description })
                    Text(stringResource(R.string.key_fingerprint, prompt.key.fingerprint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.ok_button)) } },
        )
        is Prompt.ConfirmRemove -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.host_remove_title, prompt.host.name)) },
            text = { Text(stringResource(R.string.host_remove_body)) },
            confirmButton = { TextButton(onClick = { model.remove(prompt) }) { Text(stringResource(R.string.host_remove_confirm_button)) } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
        )
        is Prompt.Problem -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(text(prompt.title)) },
            text = { Text(text(prompt.body)) },
            confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.ok_button)) } },
        )
    }
}

@Composable
private fun PasswordDialog(prompt: Prompt.Password, model: HostsModel) {
    // Plain `remember`, not `rememberSaveable`: the password must not land in saved state.
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text(stringResource(R.string.key_install_title, prompt.host.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.key_install_body, prompt.host.name))
                OutlinedTextField(
                    password, { password = it },
                    label = { Text(stringResource(R.string.key_install_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { model.installKey(prompt.host, password) }) {
                Text(stringResource(R.string.key_install_button))
            }
        },
        dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
    )
}

/** Draws [qr] dark on white with the 4-module quiet zone scanners need. */
@Composable
private fun QrCode(qr: QrMatrix, modifier: Modifier = Modifier) {
    val size = qr.size.toInt()
    Canvas(modifier.size(240.dp).background(Color.White)) {
        val cell = this.size.minDimension / (size + 8)
        for (y in 0 until size) for (x in 0 until size) {
            if (qr.modules[y * size + x]) {
                drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell, cell))
            }
        }
    }
}

@Composable
private fun text(m: Message): String = stringResource(m.res, *m.args.toTypedArray())
