package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.JumpHost
import io.github.shiroel.aries.core.KeyInfo

/** One jump host being edited. `keyId == null` is the default key. */
private data class HopDraft(val address: String, val port: String, val user: String, val keyId: String?) {
    val portNumber get() = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid get() = validHostToken(address.trim()) && validHostToken(user.trim()) && portNumber != null

    fun toJumpHost() = JumpHost(address.trim(), portNumber!!.toUShort(), user.trim(), keyId, null)
}

// Only editable endpoint text and key IDs are saved, never keys or trust decisions.
private val HopDraftsSaver = listSaver<SnapshotStateList<HopDraft>, ArrayList<String?>>(
    save = { drafts -> drafts.map { arrayListOf(it.address, it.port, it.user, it.keyId) } },
    restore = { saved -> mutableStateListOf(*saved.map { HopDraft(it[0]!!, it[1]!!, it[2]!!, it[3]) }.toTypedArray()) },
)

/** "via a → b" under a Host row, or nothing for a direct Host. */
@Composable
fun JumpSummary(jumps: List<JumpHost>) {
    if (jumps.isEmpty()) return
    Text(
        stringResource(R.string.host_jumps_summary, jumps.joinToString(" → ") { it.address }),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** The registered extra keys and a form to create another one. */
@Composable
fun ExtraKeysSection(model: HostsModel) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.key_clip_label)
    var label by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(model.createdExtraKeyLabel) {
        model.consumeCreatedExtraKey()?.let { created -> if (label.trim() == created) label = "" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.keys_title), style = MaterialTheme.typography.titleMedium)
        model.keys.filter { it.label.isNotEmpty() }.forEach { key ->
            Text(key.label, style = MaterialTheme.typography.titleSmall)
            Text(key.openssh, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(clipLabel, key.openssh))
            }) { Text(stringResource(R.string.key_copy_button)) }
        }
        ConsoleField(label, { label = it }, enabled = !model.extraKeyBusy,
            label = { Text(stringResource(R.string.key_new_label)) }, singleLine = true)
        ConsoleButton(enabled = label.isNotBlank() && !model.extraKeyBusy, onClick = {
            model.createExtraKey(label)
        }) { Text(stringResource(R.string.key_new_button)) }
    }
}

@Composable
fun JumpHostsScreen(model: HostsModel, host: Host, jumps: List<JumpHost>, hostKeyId: String?, showHeader: Boolean = true) {
    val drafts = rememberSaveable(host.id, saver = HopDraftsSaver) {
        mutableStateListOf(*jumps.map { HopDraft(it.address, it.port.toString(), it.user, it.keyId) }.toTypedArray())
    }
    var targetKey by rememberSaveable(host.id) { mutableStateOf(hostKeyId) }
    // A plain scrolling Column: a route has a handful of hops, and every field stays composed.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalLayoutTokens.current.contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHeader) TextButton(onClick = model::back) { Text(stringResource(R.string.back_button)) }
        if (showHeader) Text(stringResource(R.string.jumps_title, host.name), style = MaterialTheme.typography.headlineSmall)
        if (drafts.isEmpty()) Text(stringResource(R.string.jumps_empty))
        drafts.forEachIndexed { i, d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider()
                Text(stringResource(R.string.jumps_hop_title, i + 1), style = MaterialTheme.typography.titleMedium)
                ConsoleField(
                    d.address, { drafts[i] = d.copy(address = it) },
                    label = { Text(stringResource(R.string.host_add_address)) }, singleLine = true,
                    isError = d.address.isNotEmpty() && !validHostToken(d.address.trim()),
                    supportingText = hostInputSupport(R.string.host_input_address_error.takeUnless { validHostToken(d.address.trim()) }),
                )
                ConsoleField(
                    d.port, { drafts[i] = d.copy(port = it) },
                    label = { Text(stringResource(R.string.host_add_port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = d.portNumber == null,
                    supportingText = hostInputSupport(R.string.host_input_port_error.takeIf { d.portNumber == null }),
                )
                ConsoleField(
                    d.user, { drafts[i] = d.copy(user = it) },
                    label = { Text(stringResource(R.string.host_add_user)) }, singleLine = true,
                    isError = d.user.isNotEmpty() && !validHostToken(d.user.trim()),
                    supportingText = hostInputSupport(R.string.host_input_user_error.takeUnless { validHostToken(d.user.trim()) }),
                )
                Row {
                    KeyPicker(R.string.jumps_key_label, model.keys, d.keyId) { drafts[i] = d.copy(keyId = it) }
                    TextButton(onClick = { drafts.removeAt(i) }) { Text(stringResource(R.string.jumps_remove_button)) }
                }
            }
        }
        TextButton(onClick = { drafts += HopDraft("", "22", "", null) }) {
            Text(stringResource(R.string.jumps_add_button))
        }
        HorizontalDivider()
        KeyPicker(R.string.jumps_host_key_label, model.keys, targetKey) { targetKey = it }
        ConsoleButton(
            enabled = drafts.all { it.valid },
            onClick = { model.saveJumps(host, drafts.map { it.toJumpHost() }, targetKey) },
        ) { Text(stringResource(R.string.jumps_save_button)) }
    }
}

/** A button showing the chosen key; tapping it lists every key. `null` is the default key. */
@Composable
private fun KeyPicker(labelRes: Int, keys: List<KeyInfo>, selected: String?, onPick: (String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.key_default_label)
    fun nameOf(id: String?) = keys.find { it.id == id && it.label.isNotEmpty() }?.label ?: defaultLabel
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(labelRes, nameOf(selected))) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SingleChoiceMenuItem(selected = selected == null, text = { Text(defaultLabel) }, onClick = { onPick(null); open = false })
            keys.filter { it.label.isNotEmpty() }.forEach { k ->
                SingleChoiceMenuItem(selected = selected == k.id, text = { Text(k.label) }, onClick = { onPick(k.id); open = false })
            }
        }
    }
}

@Composable
fun TrustJumpHostKeyDialog(prompt: Prompt.TrustJumpHostKey, model: HostsModel) {
    AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text(stringResource(R.string.jump_key_trust_title)) },
        text = {
            Text(
                stringResource(
                    R.string.jump_key_trust_body,
                    prompt.hop.toInt() + 1, prompt.address, prompt.host.name, prompt.fingerprint,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { model.trust(prompt) }) { Text(stringResource(R.string.host_key_trust_button)) }
        },
        dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
    )
}
