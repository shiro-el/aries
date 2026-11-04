package io.github.shiroel.aries.dictation

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.shiroel.aries.R
import io.github.shiroel.aries.ui.LanguagePicker
import io.github.shiroel.aries.ui.OptionToggle
import androidx.compose.ui.unit.dp

/** The secret field is always blank on entry, masked, and never saveable. */
@Composable
fun DictationSettings(store: DictationStore? = null) {
    val context = LocalContext.current
    val credentials = store ?: remember(context) { DictationStore(context) }
    var options by remember { mutableStateOf(credentials.options()) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(credentials.hasKey) }
    var keyError by remember { mutableStateOf(false) }
    fun save(next: DictationOptions) { credentials.saveOptions(next); options = next }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dictation_title), style = MaterialTheme.typography.titleMedium)
        if (hasKey) Text(stringResource(R.string.dictation_key_saved))
        OutlinedTextField(
            value = key, onValueChange = { key = it; keyError = false },
            label = { Text(stringResource(R.string.dictation_key_label)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = key.isNotBlank(), onClick = {
            try { credentials.saveKey(key); hasKey = true; key = ""; keyError = false }
            catch (_: Exception) { keyError = true }
        }) { Text(stringResource(R.string.dictation_key_save_button)) }
        if (hasKey) TextButton(onClick = { credentials.clearKey(); key = ""; hasKey = false }) {
            Text(stringResource(R.string.dictation_key_remove_button))
        }
        if (keyError) Text(stringResource(R.string.dictation_key_failed), color = MaterialTheme.colorScheme.error)
        LanguagePicker(
            label = stringResource(R.string.dictation_language_label),
            languageCodes = dictationLanguageCodes,
            selectedCode = options.language,
            onSelect = { save(options.copy(language = it)) },
        )
        Text(stringResource(R.string.dictation_model_label))
        Column(Modifier.selectableGroup()) {
            DictationModel.entries.forEach { model ->
                ModelRow(model, options.model == model) { save(options.copy(model = model)) }
            }
        }
        OutlinedTextField(
            value = options.vocabulary, onValueChange = { save(options.copy(vocabulary = it)) },
            label = { Text(stringResource(R.string.dictation_vocabulary_label)) }, modifier = Modifier.fillMaxWidth(),
        )
        OptionToggle(R.string.dictation_context_label, options.autoContext) {
            save(options.copy(autoContext = it))
        }
    }
}

@Composable
private fun ModelRow(model: DictationModel, selected: Boolean, onClick: () -> Unit) {
    io.github.shiroel.aries.ui.SingleChoiceRow(
        selected, onClick, Modifier.fillMaxWidth().testTag("dictation-model:${model.name}"),
    ) { Text(model.apiName) } // i18n-ok: Groq model identifiers
}
