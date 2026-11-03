package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.HerdrCoreState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AttachMode

data class AccentPreset(val label: Int, val hex: String)

val AccentPresets = listOf(
    AccentPreset(R.string.accent_saffron, "#F4B73A"),
    AccentPreset(R.string.accent_ember, "#E8714A"),
    AccentPreset(R.string.accent_jade, "#3FB68B"),
    AccentPreset(R.string.accent_lagoon, "#3A9FD8"),
    AccentPreset(R.string.accent_iris, "#8C7CF0"),
    AccentPreset(R.string.accent_rose, "#E0668F"),
)

@Composable
fun SettingsScreen(
    model: HostsModel,
    settings: ThemeSettings,
    onBack: () -> Unit,
    showHeader: Boolean = true,
    terminalDisplay: TerminalDisplaySettings = rememberTerminalDisplaySettings(),
    hostEditor: @Composable (Host) -> Unit = {},
) {
    if (model.settingsDiagnostics) {
        DiagnosticsScreen(model.diagnosticHistory, model.hosts)
        return
    }
    val host = model.settingsHost?.let { selected -> model.hosts.find { it.id == selected.id } }
    if (host != null) {
        HostSettingsScreen(model, host, hostEditor)
        return
    }
    if (model.settingsKeys) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeySection(model)
            KeyImportButton(model)
            if (model.key != null) {
                HorizontalDivider()
                ExtraKeysSection(model)
            }
        }
        return
    }
    val readAloud = io.github.shiroel.aries.speech.rememberReadAloud(model)
    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.settings_keys_open),
                    Modifier.fillMaxWidth().testTag("settings-keys").clickable(onClick = model::openKeySettings).padding(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider()
            }
            if (model.hosts.isNotEmpty()) item {
                Text(stringResource(R.string.settings_hosts_title), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
            items(model.hosts, key = { "settings-host:${it.id}" }) { host ->
                Text(
                    host.name,
                    Modifier.fillMaxWidth().testTag("settings-host:${host.id}").clickable { model.openHostSettings(host) }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider()
            }
            item {
                SettingsField(R.string.theme_title) {
                    ConsoleSegments(
                        ThemeMode.entries, settings.mode, settings::chooseMode,
                        label = { stringResource(when (it) {
                            ThemeMode.SYSTEM -> R.string.theme_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                        }) },
                    )
                }
            }
            item { SettingsField(R.string.accent_title) { AccentControls(settings) } }
            item { SettingsField(R.string.accent_custom) { HsvPicker(settings) } }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val attachments = remember(context) { AttachmentSettings.from(context) }
                SettingsField {
                    OptionToggle(R.string.attachments_resize_label, R.string.attachments_resize_hint,
                        attachments.resizeImages, attachments::chooseResizeImages)
                }
            }
            item { SettingsField { ChatCacheSettings(model.chatCache) } }
            item(key = "app-lock") {
                io.github.shiroel.aries.security.LocalAppLock.current?.let {
                    SettingsField { io.github.shiroel.aries.security.AppLockSettings(it) }
                }
            }
            item(key = "quiet-hours") { SettingsField { QuietHoursSection() } }
            item {
                SettingsField(R.string.settings_agent_view) {
                    AgentViewToggle(model.agentView, model::setDefaultAgentView)
                }
            }
            item(key = "attach-mode") {
                SettingsField {
                    OptionToggle(
                        R.string.attach_input_label, R.string.attach_input_hint,
                        model.attachMode == AttachMode.INPUT,
                    ) { model.setDefaultAttachMode(if (it) AttachMode.INPUT else AttachMode.OBSERVE) }
                }
            }
            item(key = "terminal-display") {
                SettingsField(R.string.terminal_display_title) { TerminalDisplaySection(terminalDisplay) }
            }
            item { SettingsField { io.github.shiroel.aries.dictation.DictationSettings() } }
            item(key = "read-aloud") { SettingsField { io.github.shiroel.aries.speech.ReadAloudSettings(readAloud) } }
            item(key = "app-guidance") {
                io.github.shiroel.aries.guidance.LocalAppGuidance.current?.let {
                    io.github.shiroel.aries.guidance.AppGuidanceSettings(it)
                }
            }
            item(key = "diagnostics") {
                Text(stringResource(R.string.diagnostics_title),
                    Modifier.fillMaxWidth().testTag("settings-diagnostics").clickable(onClick = model::openDiagnostics).padding(16.dp),
                    color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
            }
        }
    }
}

/** Host configuration stays reachable even when a herdr-core Browse connection fails. */
@Composable
private fun HostSettingsScreen(model: HostsModel, host: Host, hostEditor: @Composable (Host) -> Unit) {
    val installState = model.herdrInstall.state
    val installFirst = installState == HerdrCoreState.Missing ||
        (installState is HerdrCoreState.Installed && installState.needsUpdate) ||
        model.herdrInstall.step != null
    LazyColumn(Modifier.fillMaxSize().testTag("host-actions:${host.id}")) {
        item {
            SettingsField {
                Text(host.name, style = MaterialTheme.typography.titleMedium)
                // Ticket 60 supplies the name/address form here without changing this navigation.
                hostEditor(host)
                JumpSummary(model.jumps[host.id].orEmpty())
                if (host.endpoint is Endpoint.Ssh) {
                    TextButton(onClick = { model.editJumps(host) }) { Text(stringResource(R.string.host_jumps_button)) }
                    TextButton(onClick = { model.askPassword(host) }) { Text(stringResource(R.string.host_install_key_button)) }
                }
                ClearChatCacheButton(model.chatCache, host)
                model.chatCache.notice?.let {
                    Text(stringResource(it.res, *it.args.toTypedArray()), style = MaterialTheme.typography.bodySmall)
                }
                val removeLabel = stringResource(R.string.host_remove_button_label, host.name)
                TextButton(onClick = { model.askRemove(host) }, modifier = Modifier.semantics { contentDescription = removeLabel }) {
                    Text(stringResource(R.string.host_remove_button), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (installFirst) item(key = "herdr-install") { SettingsField { HerdrInstallSection(model.herdrInstall) { model.openHost(host) } } }
        item { SettingsField { BeaconSection(model.beacon) } }
        item { SettingsField { PushSection(model.push, beaconInstalled = model.beacon.installed) } }
        if (!installFirst) item(key = "herdr-install") { SettingsField { HerdrInstallSection(model.herdrInstall) { model.openHost(host) } } }
    }
}

@Composable
private fun SettingsField(title: Int? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        content()
    }
    HorizontalDivider()
}

@Composable
private fun AccentControls(settings: ThemeSettings) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().border(1.dp, c.outline).selectableGroup()) {
        AccentPresets.forEach { preset ->
            val label = stringResource(preset.label)
            val selected = settings.accentHex == preset.hex
            Box(
                Modifier.weight(1f).height(48.dp).background(Color(android.graphics.Color.parseColor(preset.hex)))
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { settings.setAccent(preset.hex) })
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(32.dp).border(3.dp, c.background))
            }
        }
    }
    // An invalid draft is kept in the field, while the last valid colour stays applied.
    var hex by rememberSaveable(settings.accentHex) { mutableStateOf(settings.accentHex) }
    var invalid by rememberSaveable(settings.accentHex) { mutableStateOf(false) }
    OutlinedTextField(
        value = hex,
        onValueChange = { value ->
            hex = value
            invalid = !settings.setAccent(value)
        },
        modifier = Modifier.fillMaxWidth().testTag("accent-hex"),
        label = { Text(stringResource(R.string.accent_hex)) },
        trailingIcon = { Box(Modifier.size(24.dp).background(Color(android.graphics.Color.parseColor(settings.accentHex)))) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.accent_hex_invalid)) }) else null,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/** Prototype A's HSV controls, with rectangular hue strip and labelled accessibility actions. */
@Composable
private fun HsvPicker(settings: ThemeSettings) {
    val hsv = remember(settings.accentHex) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(settings.accentHex), it) }
    }
    val hues = remember { (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.8f, 0.95f))) } }
    Box(Modifier.fillMaxWidth().height(10.dp).background(Brush.horizontalGradient(hues)))
    fun set(index: Int, value: Float) {
        val next = hsv.copyOf().also { it[index] = value }
        val rgb = android.graphics.Color.HSVToColor(next) and 0xFFFFFF
        settings.setAccent("#%06X".format(java.util.Locale.ROOT, rgb))
    }
    LabeledSlider(R.string.accent_hue, hsv[0], 0f..360f) { set(0, it) }
    LabeledSlider(R.string.accent_saturation, hsv[1], 0f..1f) { set(1, it) }
    LabeledSlider(R.string.accent_brightness, hsv[2], 0.2f..1f) { set(2, it) }
}

@Composable
private fun LabeledSlider(labelRes: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val label = stringResource(labelRes)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
    }
}
