package io.github.shiroel.aries.dictation

import androidx.compose.foundation.selection.selectable
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
import io.github.shiroel.aries.ui.OptionToggle
import androidx.compose.ui.unit.dp

/** The secret field is always blank on entry, masked, and never saveable. */
@Composable
fun DictationSettings(store: DictationStore? = null) {
    val context = LocalContext.current
    val credentials = store ?: remember(context) { DictationStore(context) }
    var options by remember { mutableStateOf(credentials.options()) }
    var language by remember { mutableStateOf(options.language.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(credentials.hasKey) }
    var keyError by remember { mutableStateOf(false) }
    fun save(next: DictationOptions) { credentials.saveOptions(next); options = next }
    val invalidLanguage = language.isNotEmpty() && !language.matches(Regex("[a-z]{2}"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dictation_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dictation_disclosure), style = MaterialTheme.typography.bodySmall)
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
        OutlinedTextField(
            value = language, onValueChange = {
                language = it.trim().lowercase(java.util.Locale.ROOT)
                if (language.isEmpty() || language.matches(Regex("[a-z]{2}"))) save(options.copy(language = language.ifEmpty { null }))
            },
            label = { Text(stringResource(R.string.dictation_language_label)) }, singleLine = true,
            isError = invalidLanguage, modifier = Modifier.fillMaxWidth(),
            supportingText = if (invalidLanguage) ({ Text(stringResource(R.string.dictation_language_invalid)) }) else null,
        )
        Text(stringResource(R.string.dictation_model_label))
        DictationModel.entries.forEach { model ->
            ModelRow(model, options.model == model) { save(options.copy(model = model)) }
        }
        OutlinedTextField(
            value = options.vocabulary, onValueChange = { save(options.copy(vocabulary = it)) },
            label = { Text(stringResource(R.string.dictation_vocabulary_label)) }, modifier = Modifier.fillMaxWidth(),
        )
        OptionToggle(R.string.dictation_context_label, R.string.dictation_context_hint, options.autoContext) {
            save(options.copy(autoContext = it))
        }
    }
}

@Composable
private fun ModelRow(model: DictationModel, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().testTag("dictation-model:${model.name}")
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Text(model.apiName) // i18n-ok: Groq model identifiers
    }
}
