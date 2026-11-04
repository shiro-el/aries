package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** Literal, case-insensitive line search shared by source files and patches. */
@Composable
internal fun FileFindBar(
    query: String, onQuery: (String) -> Unit, searching: Boolean, noMatch: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        ConsoleField(query, onQuery, Modifier.weight(1f).padding(vertical = 4.dp),
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotEmpty() && !searching) onNext() }), label = { Text(stringResource(R.string.files_find_hint)) })
        ActionIcon(R.drawable.ic_chevron_left, R.string.files_find_previous, onPrevious, enabled = query.isNotEmpty() && !searching)
        ActionIcon(R.drawable.ic_chevron_right, R.string.files_find_next, onNext, enabled = query.isNotEmpty() && !searching)
    }
    if (searching) Text(stringResource(R.string.files_find_searching))
    else if (noMatch) Text(stringResource(R.string.files_find_no_matches))
}

internal fun markFileMatches(text: AnnotatedString, query: String, colors: ColorScheme): AnnotatedString {
    if (query.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        var offset = 0
        while (offset < text.length) {
            val found = text.text.indexOf(query, offset, ignoreCase = true)
            if (found < 0) break
            addStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer), found, found + query.length)
            offset = found + query.length
        }
    }
}
