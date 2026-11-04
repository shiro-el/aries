package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
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
        LocalLayoutScreens.current.HostDetail(model, host, hostEditor)
        return
    }
    if (model.settingsKeys) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalLayoutTokens.current.contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.recoveringKeys) {
                KeyRecoverySection(model)
                return@Column
            }
            KeySection(model)
            KeyImportButton(model)
            if (model.key != null) {
                HorizontalDivider()
                ExtraKeysSection(model)
            }
        }
        return
    }
    var customAccent by rememberSaveable { mutableStateOf(false) }
    val readAloud = io.github.shiroel.aries.speech.rememberReadAloud(model)
    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            ConsoleTopBar(stringResource(R.string.settings_title), onMenu = {}, onBack = onBack,
                showMark = false, showNavigationActions = false)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("settings-list")) {
            item { SettingsHeading(R.string.settings_appearance_section) }
            item(key = "app-language") { SettingsField { AppLanguageSetting() } }
            item(key = "app-layout") {
                SettingsField(R.string.layout_title) {
                    LayoutSetting(model, settings)
                }
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
            item { SettingsField(R.string.accent_title) { AccentControls(settings, onCustom = { customAccent = true }) } }
            item { SettingsHeading(R.string.settings_interaction_section) }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val attachments = remember(context) { AttachmentSettings.from(context) }
                SettingsField {
                    OptionToggle(stringResource(R.string.attachments_resize_label, ATTACHMENT_IMAGE_SIDE),
                        attachments.resizeImages, attachments::chooseResizeImages)
                }
            }
            item {
                SettingsField(R.string.settings_agent_view) {
                    AgentViewToggle(model.agentView, model::setDefaultAgentView)
                }
            }
            item(key = "attach-mode") {
                SettingsField {
                    OptionToggle(
                        R.string.attach_input_label,
                        model.attachMode == AttachMode.INPUT,
                    ) { model.setDefaultAttachMode(if (it) AttachMode.INPUT else AttachMode.OBSERVE) }
                }
            }
            item(key = "terminal-display") {
                SettingsField(R.string.terminal_display_title) { TerminalDisplaySection(terminalDisplay) }
            }
            item(key = "read-aloud") { SettingsField { io.github.shiroel.aries.speech.ReadAloudSettings(readAloud) } }
            item { SettingsHeading(R.string.settings_notifications_section) }
            item(key = "quiet-hours") { SettingsField { QuietHoursSection() } }
            item { SettingsHeading(R.string.settings_connections_section) }
            item {
                Text(
                    stringResource(R.string.settings_keys_open),
                    Modifier.fillMaxWidth().testTag("settings-keys").clickable(onClick = model::openKeySettings).padding(LocalLayoutTokens.current.contentPadding),
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider()
            }
            if (model.hosts.isNotEmpty()) item {
                Text(stringResource(R.string.settings_hosts_title), Modifier.padding(LocalLayoutTokens.current.contentPadding), style = MaterialTheme.typography.bodySmall)
            }
            items(model.hosts, key = { "settings-host:${it.id}" }) { host ->
                Text(
                    host.name,
                    Modifier.fillMaxWidth().testTag("settings-host:${host.id}").clickable { model.openHostSettings(host) }.padding(LocalLayoutTokens.current.contentPadding),
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider()
            }
            item(key = "app-lock") {
                io.github.shiroel.aries.security.LocalAppLock.current?.let {
                    SettingsField { io.github.shiroel.aries.security.AppLockSettings(it) }
                }
            }
            item { SettingsHeading(R.string.settings_storage_section) }
            item { SettingsField { ChatCacheSettings(model.chatCache) } }
            item(key = "app-guidance") {
                io.github.shiroel.aries.guidance.LocalAppGuidance.current?.let {
                    io.github.shiroel.aries.guidance.AppGuidanceSettings(it)
                }
            }
            item(key = "diagnostics") {
                Text(stringResource(R.string.diagnostics_title),
                    Modifier.fillMaxWidth().testTag("settings-diagnostics").clickable(onClick = model::openDiagnostics).padding(LocalLayoutTokens.current.contentPadding),
                    color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
            }
        }
    }
    if (customAccent) CustomAccentSheet(settings, onDismiss = { customAccent = false })
}

/** Keep the three names readable with the same enlarged-text threshold as Agent rows. */
@Composable
private fun LayoutSetting(model: HostsModel, settings: ThemeSettings) {
    val choose: (AppLayout) -> Unit = { next ->
        model.homeLayoutChanged(settings.layout, next)
        settings.chooseLayout(next)
    }
    val label: @Composable (AppLayout) -> String = { stringResource(when (it) {
        AppLayout.CONSOLE -> R.string.layout_console
        AppLayout.THREAD -> R.string.layout_thread
        AppLayout.TASKS -> R.string.layout_tasks
    }) }
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            AppLayout.entries.forEach { layout ->
                ConsoleChoice(settings.layout == layout, { choose(layout) }, Modifier.fillMaxWidth()) {
                    Text(label(layout), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    } else ConsoleSegments(AppLayout.entries, settings.layout, choose, label = label)
}

/** Host configuration stays reachable even when a herdr-core Browse connection fails. */
@Composable
internal fun HostSettingsScreen(model: HostsModel, host: Host, hostEditor: @Composable (Host) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("host-actions:${host.id}")) {
        item {
            SettingsField {
                Text(host.name, style = MaterialTheme.typography.titleMedium)
                model.settingsReason?.let { AnnouncedText(stringResource(it.res, *it.args.toTypedArray())) }
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
        item(key = "herdr-install") { SettingsField { HerdrInstallSection(model.herdrInstall) { model.openHost(host) } } }
        item(key = "beacon") { SettingsField { BeaconSection(model.beacon) } }
        item(key = "push") { SettingsField { PushSection(model.push, beaconState = model.beacon.ui) } }
    }
}

@Composable
private fun SettingsField(title: Int? = null, content: @Composable () -> Unit) {
    val tokens = LocalLayoutTokens.current
    Column(Modifier.fillMaxWidth().padding(tokens.contentPadding), verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
        title?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        content()
    }
    HorizontalDivider()
}

@Composable
private fun SettingsHeading(title: Int) {
    Text(stringResource(title), Modifier.fillMaxWidth().padding(start = LocalLayoutTokens.current.contentPadding,
        end = LocalLayoutTokens.current.contentPadding, top = 20.dp, bottom = 8.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AccentControls(settings: ThemeSettings, onCustom: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup()) {
            if (AccentPresets.none { it.hex.equals(settings.accentHex, ignoreCase = true) }) {
                val label = stringResource(R.string.accent_custom)
                Box(Modifier.size(48.dp).selectable(true, role = Role.RadioButton, onClick = onCustom)
                    .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).border(2.dp, MaterialTheme.colorScheme.primary).padding(4.dp)
                        .background(Color(settings.accentArgb)))
                }
            }
            AccentPresets.forEach { preset ->
                val label = stringResource(preset.label)
                Box(Modifier.size(48.dp).selectable(settings.accentHex == preset.hex, role = Role.RadioButton,
                    onClick = { settings.setAccent(preset.hex) }).semantics { contentDescription = label },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).border(if (settings.accentHex == preset.hex) 2.dp else 1.dp,
                        if (settings.accentHex == preset.hex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        .padding(4.dp).background(Color(preset.hex.toColorInt())))
                }
            }
        }
        IconButton(onClick = onCustom, modifier = Modifier.size(48.dp).testTag("accent-custom")) {
            Icon(painterResource(R.drawable.ic_square_pen), stringResource(R.string.accent_custom), Modifier.size(20.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CustomAccentSheet(settings: ThemeSettings, onDismiss: () -> Unit) {
    // Keep the draft in the screen's saved-state registry, outside the sheet Dialog.
    var hex by rememberSaveable(settings.accentHex) { mutableStateOf(settings.accentHex) }
    var invalid by rememberSaveable(settings.accentHex) { mutableStateOf(false) }
    ConsoleSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalLayoutTokens.current.contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.accent_custom), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) }
            }
            // An invalid draft stays visible while the last valid colour remains applied.
            ConsoleField(value = hex, onValueChange = { hex = it; invalid = !settings.setAccent(it) },
                modifier = Modifier.fillMaxWidth().testTag("accent-hex"),
                label = { Text(stringResource(R.string.accent_hex)) }, singleLine = true, isError = invalid,
                trailingIcon = { Box(Modifier.size(24.dp).background(Color(settings.accentArgb))) },
                supportingText = if (invalid) ({ Text(stringResource(R.string.accent_hex_invalid)) }) else null)
            HsvPicker(settings)
        }
    }
}

/** Keep HSV independently of RGB round-trips, especially hue when saturation/value reaches zero. */
@Composable
private fun HsvPicker(settings: ThemeSettings) {
    val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(settings.accentArgb, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initial[2]) }
    var committed by rememberSaveable { mutableStateOf(settings.accentHex) }
    LaunchedEffect(settings.accentHex) {
        if (settings.accentHex != committed) {
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(settings.accentArgb, it) }
            if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]
            saturation = hsv[1]; brightness = hsv[2]; committed = settings.accentHex
        }
    }
    fun commit() {
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF
        committed = "#%06X".format(java.util.Locale.ROOT, rgb)
        settings.setAccent(committed)
    }
    val hues = remember { (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, .8f, .95f))) } }
    val draft = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(40.dp).background(draft).border(1.dp, MaterialTheme.colorScheme.outline)
            .testTag("accent-draft"))
        Box(Modifier.weight(1f).height(10.dp).background(Brush.horizontalGradient(hues)))
    }
    LabeledSlider(R.string.accent_hue, hue, 0f..360f, { hue = it }, ::commit)
    LabeledSlider(R.string.accent_saturation, saturation, 0f..1f, { saturation = it }, ::commit)
    LabeledSlider(R.string.accent_brightness, brightness, 0f..1f, { brightness = it }, ::commit)
}

@Composable
private fun LabeledSlider(labelRes: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val label = stringResource(labelRes)
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        ConsoleSlider(value = value, onValueChange = onChange, onValueChangeFinished = onFinished, valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}
