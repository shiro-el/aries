package io.github.shiroel.aries.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PRIVATE_KEY_BYTES = 64 * 1024

@Composable
fun KeyImportButton(model: HostsModel) {
    var importing by remember { mutableStateOf(false) }
    TextButton(onClick = { importing = true }) { Text(stringResource(R.string.key_import_button)) }
    if (importing) KeyImportDialog(model) { importing = false }
}

/** Secret drafts live only in this composition, never saved instance state. */
@Composable
fun KeyImportDialog(model: HostsModel, onDismiss: () -> Unit) {
    val needsLabel = model.key != null
    var label by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Message?>(null) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            problem = null
            scope.launch {
                try {
                    privateKey = withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { input ->
                            val bytes = ByteArray(MAX_PRIVATE_KEY_BYTES + 1)
                            var size = 0
                            while (size < bytes.size) {
                                val read = input.read(bytes, size, bytes.size - size)
                                if (read < 0) break
                                size += read
                            }
                            require(size <= MAX_PRIVATE_KEY_BYTES)
                            String(bytes, 0, size, Charsets.UTF_8)
                        } ?: error("document unavailable")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    privateKey = ""
                    problem = Message(R.string.key_import_file_error)
                } finally {
                    busy = false
                }
            }
        }
    }
    fun close() {
        privateKey = ""
        passphrase = ""
        onDismiss()
    }
    val passwordKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.key_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.key_import_hint))
                if (needsLabel) OutlinedTextField(
                    label, { label = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.key_new_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("key-import-label"),
                )
                TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.key_import_file_button))
                }
                OutlinedTextField(
                    privateKey, { if (it.toByteArray(Charsets.UTF_8).size <= MAX_PRIVATE_KEY_BYTES) privateKey = it },
                    enabled = !busy, minLines = 3, maxLines = 5,
                    label = { Text(stringResource(R.string.key_import_private_label)) },
                    keyboardOptions = passwordKeyboard, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("key-import-private"),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.key_import_passphrase_label)) },
                    keyboardOptions = passwordKeyboard, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("key-import-passphrase"),
                )
                problem?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
                if (busy) Text(stringResource(R.string.key_import_busy))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (!needsLabel || label.isNotBlank()) && privateKey.isNotBlank(),
                modifier = Modifier.testTag("key-import-save"),
                onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        try {
                            model.importKey(label, privateKey, passphrase)
                            close()
                        } catch (e: CoreException) {
                            // Permit a retry, but don't retain a failed passphrase.
                            passphrase = ""
                            problem = problemFor(e).body
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.key_import_button)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = ::close) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
