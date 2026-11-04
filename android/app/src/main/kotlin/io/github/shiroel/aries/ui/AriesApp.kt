package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import io.github.shiroel.aries.core.KeyProtection
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
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
fun AriesApp(
    model: HostsModel,
    settings: ThemeSettings = rememberThemeSettings(),
    guidance: io.github.shiroel.aries.guidance.AppGuidanceState? = null,
    allowNotifications: suspend () -> Boolean = { false },
    terminalDisplay: TerminalDisplaySettings = rememberTerminalDisplaySettings(),
) {
    AriesTheme(settings) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutScreens provides layoutScreensFor(settings.layout)) {
            NewTaskAccess(model) {
                if (guidance == null) AriesContent(model, settings, terminalDisplay)
                else io.github.shiroel.aries.guidance.AppGuidance(guidance) { AriesContent(model, settings, terminalDisplay, guidance, allowNotifications) }
            }
        }
    }
}

@Composable
private fun rememberThemeSettings(): ThemeSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) { ThemeSettings.from(context) }
}

@Composable
private fun AriesContent(
    model: HostsModel, settings: ThemeSettings, terminalDisplay: TerminalDisplaySettings,
    guidance: io.github.shiroel.aries.guidance.AppGuidanceState? = null,
    allowNotifications: suspend () -> Boolean = { false },
) {
    val activity = androidx.activity.compose.LocalActivity.current
    val appLock = io.github.shiroel.aries.security.LocalAppLock.current?.lock
    // Screen connections and the Call banner follow the visible lifecycle.
    LifecycleStartEffect(model) {
        model.resumeScreens()
        model.resumeCalls()
        onStopOrDispose {
            // A replacement Activity keeps its sessions; App Lock releases immediately.
            if (activity?.isChangingConfigurations != true) {
                model.backgrounded(retainConnections = appLock?.enabled != true)
                model.pauseCalls()
            }
        }
    }
    Scaffold { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            FirstConnectionGuide(model, guidance, allowNotifications) {
                CallAttentionHost(model) {
                    LocalLayoutScreens.current.Shell(model) {
                        RemoteFilesAccess(model) {
                            AdaptiveHostLayout(
                                listScreen = model.listScreen.takeIf { LocalLayoutScreens.current.usesListPane },
                                hasDetail = model.screen != model.listScreen,
                                listHeader = { LocalLayoutScreens.current.Header(model, it) },
                                detailHeader = { LocalLayoutScreens.current.Header(model, model.screen) },
                                listContent = { LocalLayoutScreens.current.Screen(model, settings, terminalDisplay, it) },
                                detailContent = { LocalLayoutScreens.current.Screen(model, settings, terminalDisplay, model.screen) },
                            )
                        }
                    }
                }
            }
        }
        model.prompt?.let { PromptDialog(it, model) }
        if (model.installingKeyOn != null) {
            AlertDialog(onDismissRequest = {},
                text = { Text(stringResource(R.string.key_install_busy)) }, confirmButton = {})
        }
    }
}

@Composable
internal fun HostListScreen(model: HostsModel, tasks: Boolean = false) {
    val adding = model.hostAddMethod != null
    LazyColumn(Modifier.fillMaxSize()) {
        if (model.hosts.isEmpty()) item { Text(stringResource(R.string.hosts_empty), Modifier.padding(12.dp), fontSize = 12.sp) }
        items(model.hosts, key = { it.id }) { host -> ConsoleHostRow(model, host, tasks) }
        item(key = "add") {
            val expansion = stringResource(if (adding) R.string.tree_expanded else R.string.tree_collapsed)
            Text(
                stringResource(if (adding) R.string.console_add_close else R.string.console_add_host),
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hosts-add-toggle")
                    .semantics { stateDescription = expansion }.clickable(role = Role.Button) {
                    if (adding) model.closeAddingHost() else model.startAddingHost()
                }.padding(12.dp),
                fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
            )
            if (adding) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HostConnectionForm(model)
            }
        }
    }
}

@Composable
private fun ConsoleHostRow(model: HostsModel, host: Host, tasks: Boolean = false) {
    val color = if (host.endpoint is Endpoint.Local) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val connectLabel = stringResource(R.string.hosts_connect_button)
    val name: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = connectLabel, role = Role.Button) { model.openHost(host) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(host.name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            val where = when (val endpoint = host.endpoint) {
                is Endpoint.Ssh -> if (endpoint.port.toInt() == 22) {
                    stringResource(R.string.host_ssh_default_address, endpoint.user, endpoint.address)
                } else stringResource(R.string.host_ssh_address, endpoint.user, endpoint.address, endpoint.port.toInt())
                is Endpoint.Local -> stringResource(R.string.host_local_address)
            }
            Text(where, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        }
    }
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        WaitingSummary(model.quickOpenSources.filter { it.host.id == host.id },
            model.drawerAgents.filter { it.host.id == host.id }, Modifier.testTag("host-waiting:${host.id}"),
            onClick = { model.openHostWaiting(host) })
        val taskLabel = stringResource(R.string.new_task_on_host, host.name)
        ActionIcon(R.drawable.ic_plus, R.string.new_task_title, { model.back(); model.newTask(host) },
            modifier = Modifier.testTag("host-new-task").semantics {
                contentDescription = taskLabel
            })
    }
    val more: @Composable () -> Unit = {
        val moreLabel = stringResource(R.string.console_host_menu, host.name)
        Box(
            Modifier.size(48.dp).testTag("host-menu:${host.id}").clickable(role = Role.Button) { model.openHostSettings(host) }
                .semantics { contentDescription = moreLabel },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.console_more), Modifier.clearAndSetSemantics {}, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (tasks) Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            val live = model.quickOpenSources.any { it.host.id == host.id && it.live }
            val dot = androidx.compose.foundation.shape.CircleShape
            Box(Modifier.padding(start = 16.dp).size(8.dp)
                .then(if (live) Modifier.background(statusColor(io.github.shiroel.aries.core.AgentStatus.Done), dot)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, dot)))
            name()
            more()
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        name()
        actions()
        more()
    }
    if (model.missingKeyHostId == host.id) {
        Column(Modifier.padding(12.dp)) {
            if (model.key == null) ConnectionKeySetup(model)
            else TextButton(onClick = { model.connect(host) }, modifier = Modifier.testTag("missing-key-connect")) {
                Text(stringResource(R.string.onboarding_continue_button))
            }
        }
    }
    HorizontalDivider()
}

@Composable
internal fun KeySection(model: HostsModel) {
    val key = model.key
    if (key == null) {
        Text(stringResource(R.string.key_missing))
        ConsoleButton(onClick = model::createKey) { Text(stringResource(R.string.key_create_button)) }
        return
    }
    PublicKeyDetails(model)
    TextButton(onClick = model::openKeyRecovery) { Text(stringResource(R.string.key_recover_button)) }
    BiometricToggle(model.keyProtection == KeyProtection.BIOMETRIC, model::requireBiometric)
}

@Composable
private fun PublicKeyDetails(model: HostsModel) {
    val key = model.key ?: return
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
}

@Composable
private fun BiometricToggle(required: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = required, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.key_biometric_label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        ConsoleToggle(checked = required, onCheckedChange = null)
    }
}

@Composable
internal fun OptionToggle(label: Int, on: Boolean, onChange: (Boolean) -> Unit) =
    OptionToggle(stringResource(label), on, onChange)

@Composable
internal fun OptionToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        ConsoleToggle(checked = on, onCheckedChange = null)
    }
}

@Composable
internal fun ConnectionKeySetup(model: HostsModel) {
    Text(stringResource(R.string.onboarding_key_body), style = MaterialTheme.typography.bodySmall)
    KeySection(model)
    KeyImportButton(model)
}

@Composable
private fun HostConnectionForm(model: HostsModel) {
    val method = model.hostAddMethod ?: return
    ConsoleSegments(HostAddMethod.entries, method, model::startAddingHost,
        label = { stringResource(if (it == HostAddMethod.QR) R.string.onboarding_qr_button else R.string.onboarding_manual_button) })
    when {
        model.key == null -> ConnectionKeySetup(model)
        model.addedHost != null -> {
            val host = model.addedHost!!
            HostKeyAuthorization(model, host)
            TextButton(onClick = { model.connect(host) }, modifier = Modifier.testTag("host-setup-connect")) {
                Text(stringResource(R.string.onboarding_continue_button))
            }
        }
        method == HostAddMethod.QR -> {
            Text(stringResource(R.string.onboarding_qr_body), style = MaterialTheme.typography.bodySmall)
            QrHostPairing(model, connectAfterPair = true)
        }
        else -> AddHostForm(model)
    }
}

@Composable
internal fun HostKeyAuthorization(model: HostsModel, host: Host) {
    Text(stringResource(R.string.onboarding_manual_key_body))
    KeySection(model)
    TextButton(onClick = { model.askPassword(host) }) { Text(stringResource(R.string.host_install_key_button)) }
}

@Composable
private fun AddHostForm(model: HostsModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("") }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val guided = io.github.shiroel.aries.guidance.LocalAppGuidance.current?.onboardingStep != null
    val errors = hostInputErrors(model.hosts, null, name, address, portNumber, user)
    Text(stringResource(R.string.onboarding_manual_key_body), style = MaterialTheme.typography.bodySmall)
    PublicKeyDetails(model)
    Text(stringResource(R.string.host_add_title), style = MaterialTheme.typography.titleMedium)
    ConsoleField(name, { name = it }, label = { Text(stringResource(R.string.host_add_name)) }, singleLine = true, isError = errors.name != null, supportingText = hostInputSupport(errors.name))
    ConsoleField(address, { address = it }, label = { Text(stringResource(R.string.host_add_address)) }, singleLine = true, isError = errors.address != null && address.isNotEmpty(), supportingText = hostInputSupport(errors.address))
    ConsoleField(
        port, { port = it },
        label = { Text(stringResource(R.string.host_add_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = errors.port != null, supportingText = hostInputSupport(errors.port),
    )
    ConsoleField(user, { user = it }, label = { Text(stringResource(R.string.host_add_user)) }, singleLine = true, isError = errors.user != null && user.isNotEmpty(), supportingText = hostInputSupport(errors.user))
    ConsoleButton(
        enabled = validHostInput(model.hosts, null, name, address, portNumber, user),
        onClick = {
            if (model.addHost(name, address, portNumber ?: 22, user, connectAfterAdd = guided)) {
                name = ""; address = ""; port = "22"; user = ""
            }
        },
    ) { Text(stringResource(R.string.host_add_button)) }
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
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            confirmButton = { TextButton(onClick = { model.remove(prompt) }) { Text(stringResource(R.string.host_remove_confirm_button)) } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
        )
        is Prompt.ConfirmSavedLaunch -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.shortcuts_confirm_title)) },
            text = {
                val request = prompt.saved.request
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.shortcuts_confirm_body, prompt.saved.name, prompt.browser.host.name))
                    Text(stringResource(R.string.launch_command), style = MaterialTheme.typography.labelLarge)
                    request.preset?.let { Text(stringResource(it.label)) }
                    if (request.command.isNotBlank()) Text(request.command)
                    else if (request.preset == null) Text(stringResource(R.string.shortcuts_default_command))
                    request.model?.let {
                        Text(stringResource(R.string.launch_model_label), style = MaterialTheme.typography.labelLarge)
                        Text(it)
                    }
                    request.effort?.let {
                        Text(stringResource(R.string.launch_effort_label), style = MaterialTheme.typography.labelLarge)
                        Text(it)
                    }
                    Text(stringResource(R.string.launch_cwd), style = MaterialTheme.typography.labelLarge)
                    Text(request.cwd?.takeIf { it.isNotBlank() } ?: stringResource(R.string.launch_cwd_default))
                    val space = prompt.browser.structure?.spaces?.find { it.id == request.spaceId }
                    request.spaceId?.let {
                        Text(stringResource(R.string.launch_space), style = MaterialTheme.typography.labelLarge)
                        Text(space?.name ?: it)
                    }
                    request.tabId?.let { id ->
                        Text(stringResource(R.string.launch_tab), style = MaterialTheme.typography.labelLarge)
                        Text(space?.tabs?.find { it.id == id }?.name ?: id)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { model.confirmSavedLaunch(prompt) }) { Text(stringResource(R.string.launch_button)) } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
        )
        is Prompt.Problem -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(text(prompt.title)) },
            text = { AnnouncedText(text(prompt.body)) },
            confirmButton = {
                TextButton(onClick = { if (prompt.recoverKey) model.openKeyRecovery() else model.dismiss() }) {
                    Text(stringResource(if (prompt.recoverKey) R.string.key_recover_button else R.string.ok_button))
                }
            },
            dismissButton = {
                if (prompt.recoverKey) TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) }
            },
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsoleField(
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
private fun text(m: Message): String = messageText(m)
