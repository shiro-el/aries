package io.github.shiroel.aries.ui

import android.icu.text.Collator
import android.icu.util.ULocale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import java.util.Locale

/** Searchable language choices; callers persist their original codes and use null for automatic. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePicker(
    label: String,
    languageCodes: List<String>,
    selectedCode: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The configuration follows Android's per-app language, unlike Locale.getDefault().
    val appLocale = ULocale.forLocale(LocalConfiguration.current.locales[0])
    val choices = remember(languageCodes, selectedCode, appLocale) {
        val collator = Collator.getInstance(appLocale)
        (languageCodes + listOfNotNull(selectedCode)).distinct().map { code ->
            // Whisper retains the deprecated jw spelling; CLDR names Javanese under jv.
            val locale = ULocale(if (code == "jw") "jv" else code)
            LanguageChoice(code, locale.getDisplayLanguage(appLocale), locale.getDisplayLanguage(locale))
        }.sortedWith { a, b -> collator.compare(a.localizedName, b.localizedName) }
    }
    val automatic = stringResource(R.string.language_picker_auto)
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(choices.find { it.code == selectedCode }?.label ?: automatic)
        }
    }
    if (open) {
        var query by rememberSaveable { mutableStateOf("") }
        val normalized = query.languageSearchKey()
        val matching = choices.filter { choice ->
            listOf(choice.localizedName, choice.nativeName, choice.code).any { normalized in it.languageSearchKey() }
        }
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().padding(horizontal = 16.dp)
                    .testTag("language-picker-sheet"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.back_button)) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.language_picker_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                key(normalized) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
                        item(key = "automatic") {
                            LanguageRow(automatic, selectedCode == null) { onSelect(null); open = false }
                        }
                        items(matching, key = { it.code }) { choice ->
                            LanguageRow(choice.label, selectedCode == choice.code) { onSelect(choice.code); open = false }
                        }
                        if (matching.isEmpty()) item {
                            Text(stringResource(R.string.language_picker_no_results), Modifier.padding(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private data class LanguageChoice(val code: String, val localizedName: String, val nativeName: String) {
    val label get() = "$localizedName · $nativeName"
}

private fun String.languageSearchKey() = filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    SingleChoiceRow(selected, onClick) { Text(label) }
}
