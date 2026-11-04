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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

@Composable
fun KeyImportButton(model: HostsModel) {
    var importing by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { model.keyImport.clear(); importing = true }) { Text(stringResource(R.string.key_import_button)) }
    if (importing) KeyImportDialog(model) { importing = false }
}

/** Secret drafts and submitted work stay in the retained model, never saved instance state. */
@Composable
fun KeyImportDialog(model: HostsModel, replacementKeyId: String? = null, onDismiss: () -> Unit) {
    val needsLabel = model.key != null && replacementKeyId == null
    var label by rememberSaveable { mutableStateOf("") }
    val importer = model.keyImport
    val busy = importer.busy
    val problem = importer.problem
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.readDocument { resolver.openInputStream(uri) }
    }
    fun close() {
        importer.clear()
        onDismiss()
    }
    LaunchedEffect(importer.completed) { if (importer.completed) close() }
    val passwordKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.key_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (replacementKeyId != null) Text(stringResource(R.string.key_recover_replace_body))
                if (needsLabel) ConsoleField(
                    label, { label = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.key_new_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("key-import-label"),
                )
                TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.key_import_file_button))
                }
                ConsoleField(
                    importer.privateKey, importer::editPrivateKey,
                    enabled = !busy, minLines = 3, maxLines = 5,
                    label = { Text(stringResource(R.string.key_import_private_label)) },
                    keyboardOptions = passwordKeyboard, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("key-import-private"),
                )
                ConsoleField(
                    importer.passphrase, { importer.passphrase = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.key_import_passphrase_label)) },
                    keyboardOptions = passwordKeyboard, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("key-import-passphrase"),
                )
                problem?.let { AnnouncedText(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
                if (busy) Text(stringResource(R.string.key_import_busy))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (!needsLabel || label.isNotBlank()) && importer.privateKey.isNotBlank(),
                modifier = Modifier.testTag("key-import-save"),
                onClick = { importer.submit(label, replacementKeyId) },
            ) { Text(stringResource(if (replacementKeyId == null) R.string.key_import_button else R.string.key_recover_replace_button)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = ::close) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
