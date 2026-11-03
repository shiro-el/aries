package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
fun AriesApp(model: HostsModel, settings: ThemeSettings = rememberThemeSettings()) {
    AriesTheme(settings) { AriesContent(model, settings) }
}

@Composable
private fun rememberThemeSettings(): ThemeSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) { ThemeSettings.from(context) }
}

@Composable
private fun AriesContent(model: HostsModel, settings: ThemeSettings) {
    // Calls are followed (for the banner) only while the app is in front.
    LifecycleStartEffect(model) {
        model.resumeCalls()
        onStopOrDispose { model.pauseCalls() }
    }
    Scaffold { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            ConsoleShell(model) {
                Column(Modifier.fillMaxSize()) {
                    when (val s = model.screen) {
                        Screen.Hosts -> HostListScreen(model)
                        Screen.Settings -> {
                            SettingsScreen(model, settings, onBack = model::back, showHeader = false)
                        }
                        is Screen.Connecting -> Connecting(s.host)
                        is Screen.Browse -> {
                            BrowseScreen(
                                s.browser,
                                showHeader = false,
                                onBack = model::back,
                                onOpen = model::openTerminal,
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
                            TerminalScreen(
                                s.viewer,
                                onBack = model::closeTerminal,
                                onView = model::switchView.takeIf { s.viewer.terminal.agentStatus != null },
                            )
                        }
                        is Screen.JumpHosts -> {
                            JumpHostsScreen(model, s.host, s.jumps, s.hostKeyId, showHeader = false)
                        }
                        is Screen.Agents -> {
                            AgentsScreen(
                                s.board,
                                showHeader = false,
                                onBack = model::back,
                                onOpen = { model.openAgent(s.board, it) },
                                onPin = { entry, pinned -> model.pin(s.board, entry, pinned) },
                            )
                        }
                        is Screen.Chat -> {
                            ChatScreen(
                                s.chat,
                                onBack = model::closeChat,
                                onOpenTerminal = { model.openTerminal(s.chat.terminal) },
                                onView = model::switchView,
                                calls = model.calls,
                            )
                        }
                    }
                }
            }
        }
        model.prompt?.let { PromptDialog(it, model) }
    }
}

@Composable
private fun HostListScreen(model: HostsModel) {
    var keyDetails by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "key") {
            val key = model.key
            if (key == null) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.key_missing), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.createKey(); keyDetails = true }) {
                        Text(stringResource(R.string.key_create_button))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.console_key_band, key.fingerprint),
                    Modifier.fillMaxWidth().testTag("hosts-key-band").clickable { keyDetails = !keyDetails }
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (keyDetails) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { KeySection(model) }
            }
            HorizontalDivider()
        }
        if (model.hosts.isEmpty()) item { Text(stringResource(R.string.hosts_empty), Modifier.padding(12.dp), fontSize = 12.sp) }
        items(model.hosts, key = { it.id }) { host -> ConsoleHostRow(model, host) }
        // Per-Host clearing stays here, so its result remains visible on Hosts.
        model.chatCache.notice?.let { notice ->
            item { Text(stringResource(notice.res, *notice.args.toTypedArray()), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        item(key = "add") {
            Text(
                stringResource(if (adding) R.string.console_add_close else R.string.console_add_host),
                Modifier.fillMaxWidth().testTag("hosts-add-toggle").clickable { adding = !adding }.padding(12.dp),
                fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
            )
            if (adding) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { AddHostForm(model) }
        }
        if (model.key != null) item { Column(Modifier.padding(12.dp)) { ExtraKeysSection(model) } }
    }
}

@Composable
private fun ConsoleHostRow(model: HostsModel, host: Host) {
    var menu by rememberSaveable(host.id) { mutableStateOf(false) }
    val color = if (host.endpoint is Endpoint.Local) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(48.dp).background(color))
        Column(Modifier.weight(1f).clickable { model.connect(host) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(host.name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = when (val endpoint = host.endpoint) {
                is Endpoint.Ssh -> stringResource(R.string.host_ssh_address, endpoint.user, endpoint.address, endpoint.port.toInt())
                is Endpoint.Local -> stringResource(R.string.host_local_address)
            }
            Text(where, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            stringResource(if (host.endpoint is Endpoint.Ssh) R.string.console_ssh else R.string.console_local),
            Modifier.border(1.dp, color.copy(alpha = .7f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            fontSize = 11.sp, lineHeight = 15.sp, color = color,
        )
        val moreLabel = stringResource(R.string.console_host_menu, host.name)
        Box(
            Modifier.size(48.dp).testTag("host-menu:${host.id}").clickable { menu = !menu }
                .semantics { contentDescription = moreLabel },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.console_more), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (menu) Column(Modifier.testTag("host-actions:${host.id}").padding(start = 15.dp, end = 12.dp)) {
        JumpSummary(model.jumps[host.id].orEmpty())
        if (host.endpoint is Endpoint.Ssh) {
            HostEditSection(model, host)
            TextButton(onClick = { model.editJumps(host) }) { Text(stringResource(R.string.host_jumps_button)) }
            TextButton(onClick = { model.askPassword(host) }) { Text(stringResource(R.string.host_install_key_button)) }
        }
        ClearChatCacheButton(model.chatCache, host)
        val removeLabel = stringResource(R.string.host_remove_button_label, host.name)
        TextButton(onClick = { model.askRemove(host) }, modifier = Modifier.semantics { contentDescription = removeLabel }) {
            Text(stringResource(R.string.host_remove_button), color = MaterialTheme.colorScheme.error)
        }
    }
    HorizontalDivider()
}

@Composable
private fun KeySection(model: HostsModel) {
    val key = model.key
    if (key == null) {
        Text(stringResource(R.string.key_missing))
        ConsoleButton(onClick = model::createKey) { Text(stringResource(R.string.key_create_button)) }
        return
    }
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.key_clip_label)
    Text(stringResource(R.string.key_title), style = MaterialTheme.typography.titleMedium)
    Text(key.openssh, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.key_fingerprint, key.fingerprint), style = MaterialTheme.typography.bodySmall)
    ConsoleButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(clipLabel, key.openssh))
    }) { Text(stringResource(R.string.key_copy_button)) }
    ConsoleButton(onClick = model::showKeyQr) { Text(stringResource(R.string.key_qr_button)) }
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
internal fun OptionToggle(label: Int, hint: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = null)
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
    ConsoleButton(
        enabled = validHostInput(model.hosts, null, name, address, portNumber, user),
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
