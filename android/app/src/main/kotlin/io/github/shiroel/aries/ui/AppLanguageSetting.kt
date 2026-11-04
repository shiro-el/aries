package io.github.shiroel.aries.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.AppLanguage
import io.github.shiroel.aries.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppLanguageSetting() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val choices = listOf(
        "" to stringResource(R.string.app_language_system),
        "en" to stringResource(R.string.app_language_english),
        "ko" to stringResource(R.string.app_language_korean),
    )
    val selected = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it; search = "" }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == selected }?.second ?: choices.first().second,
            onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.app_language_title)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().testTag("app-language")
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            OutlinedTextField(
                search, onValueChange = { search = it }, singleLine = true,
                label = { Text(stringResource(R.string.app_language_search)) },
                modifier = Modifier.fillMaxWidth().testTag("app-language-search"),
            )
            choices.filter { (tag, label) ->
                search.isBlank() || label.contains(search.trim(), ignoreCase = true) ||
                    (tag == "en" && "영어 English".contains(search.trim(), ignoreCase = true)) ||
                    (tag == "ko" && "한국어 Korean".contains(search.trim(), ignoreCase = true))
            }.forEach { (tag, label) ->
                SingleChoiceMenuItem(
                    selected = tag == selected,
                    modifier = Modifier.testTag("app-language:$tag"),
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        AppLanguage.choose(context, tag)
                    },
                )
            }
        }
    }
}
