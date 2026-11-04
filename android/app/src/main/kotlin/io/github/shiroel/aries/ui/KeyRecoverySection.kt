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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.KeyInfo
import kotlinx.coroutines.launch

/** Key IDs stay stable, including on jump routes. Re-registration is always explicit. */
@Composable
internal fun KeyRecoverySection(model: HostsModel) {
    var generating by remember { mutableStateOf<KeyInfo?>(null) }
    var importing by remember { mutableStateOf<KeyInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Message?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.key_clip_label)
    Text(stringResource(R.string.key_recover_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.key_recover_body))
    problem?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
    model.keys.forEach { key ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text(key.label.ifBlank { stringResource(R.string.key_title) }, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.key_fingerprint, key.fingerprint))
            TextButton(enabled = !busy, onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(clipLabel, key.openssh))
            }) { Text(stringResource(R.string.key_copy_button)) }
            TextButton(enabled = !busy, onClick = { generating = key }, modifier = Modifier.testTag("key-recover-generate-${key.id}")) {
                Text(stringResource(R.string.key_recover_generate_button))
            }
            TextButton(enabled = !busy, onClick = { importing = key }, modifier = Modifier.testTag("key-recover-import-${key.id}")) {
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
            onDismissRequest = { if (!busy) generating = null },
            title = { Text(stringResource(R.string.key_recover_title)) },
            text = { Text(stringResource(R.string.key_recover_replace_body)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        try {
                            model.recoverKey(key.id)
                        } catch (e: CoreException) {
                            problem = problemFor(e).body
                        } finally {
                            generating = null
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.key_recover_replace_button)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { generating = null }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
    importing?.let { key -> KeyImportDialog(model, replacementKeyId = key.id) { importing = null } }
}
