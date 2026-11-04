package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint

/** Key IDs stay stable, including on jump routes. Re-registration is always explicit. */
@Composable
internal fun KeyRecoverySection(model: HostsModel) {
    var generatingId by model::generatingKeyId
    var importingId by rememberSaveable { mutableStateOf<String?>(null) }
    val generating = model.keys.find { it.id == generatingId }
    val importing = model.keys.find { it.id == importingId }
    val busy = model.recoveringKeyId != null
    val problem = model.keyRecoveryProblem
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.key_clip_label)
    Text(stringResource(R.string.key_recover_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.key_recover_body))
    problem?.let { AnnouncedText(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
    model.keys.forEach { key ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text(key.label.ifBlank { stringResource(R.string.key_title) }, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.key_fingerprint, key.fingerprint))
            TextButton(enabled = !busy, onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(clipLabel, key.openssh))
            }) { Text(stringResource(R.string.key_copy_button)) }
            TextButton(enabled = !busy, onClick = { generatingId = key.id }, modifier = Modifier.testTag("key-recover-generate-${key.id}")) {
                Text(stringResource(R.string.key_recover_generate_button))
            }
            TextButton(enabled = !busy, onClick = { model.keyImport.clear(); importingId = key.id }, modifier = Modifier.testTag("key-recover-import-${key.id}")) {
                Text(stringResource(R.string.key_import_button))
            }
        }
    }
    HorizontalDivider()
    Text(stringResource(R.string.key_recover_register_body))
    model.hosts.filter { it.endpoint is Endpoint.Ssh }.forEach { host ->
        TextButton(enabled = !busy, onClick = { model.askPassword(host) }, modifier = Modifier.testTag("key-recover-register-${host.id}")) {
            Text(stringResource(R.string.key_install_title, host.name))
        }
    }
    generating?.let { key ->
        AlertDialog(
            onDismissRequest = { if (!busy) generatingId = null },
            title = { Text(stringResource(R.string.key_recover_title)) },
            text = { Text(stringResource(R.string.key_recover_replace_body)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    model.confirmKeyRecovery()
                }) { Text(stringResource(R.string.key_recover_replace_button)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { generatingId = null }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
    importing?.let { key -> KeyImportDialog(model, replacementKeyId = key.id) { importingId = null } }
}
