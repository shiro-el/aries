package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** One navigation bar for folders, diffs and full files. */
@Composable
internal fun FilesHeader(
    path: String, isFile: Boolean, onBack: () -> Unit, onClose: () -> Unit,
    onFolder: (String) -> Unit, onRefresh: () -> Unit, refreshing: Boolean,
    diffAvailable: Boolean, fullFile: Boolean, onFullFile: () -> Unit,
    wrap: Boolean, onWrap: (() -> Unit)?, onSend: (() -> Unit)?,
    onSave: (() -> Unit)?, onShare: (() -> Unit)?, exportEnabled: Boolean,
) {
    var menu by remember(path) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(R.drawable.ic_arrow_left, if (isFile) R.string.files_back_folder_button else R.string.files_close_button, onBack)
        val scroll = rememberScrollState()
        LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            if (path.isEmpty()) Text(stringResource(R.string.files_title), maxLines = 1)
            else {
                val parts = path.split('/').filter(String::isNotEmpty)
                Breadcrumb("/", "/", onFolder)
                parts.forEachIndexed { index, part ->
                    if (index > 0) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(12.dp))
                    val target = "/" + parts.take(index + 1).joinToString("/")
                    if (isFile && index == parts.lastIndex) Text(part, fontFamily = FontFamily.Monospace, maxLines = 1)
                    else Breadcrumb(part, target, onFolder)
                }
            }
        }
        if (diffAvailable) ActionIcon(
            if (fullFile) R.drawable.ic_file_diff else R.drawable.ic_file,
            if (fullFile) R.string.files_diff_button else R.string.files_full_button, onFullFile,
        )
        ActionIcon(R.drawable.ic_refresh_cw, R.string.files_refresh_button, onRefresh, enabled = !refreshing)
        if (isFile) Box {
            ActionIcon(R.drawable.ic_ellipsis_vertical, R.string.files_actions_label, { menu = !menu })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onSend?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.files_send_file_button)) },
                    onClick = { menu = false; action() }) }
                onSave?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.files_save_button)) },
                    enabled = exportEnabled, onClick = { menu = false; action() }) }
                onShare?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.files_share_button)) },
                    enabled = exportEnabled, onClick = { menu = false; action() }) }
                onWrap?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.files_wrap_lines)) },
                    leadingIcon = { if (wrap) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp)) },
                    onClick = { menu = false; action() }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.files_close_button)) }, onClick = { menu = false; onClose() })
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun Breadcrumb(name: String, path: String, onFolder: (String) -> Unit) {
    val label = stringResource(R.string.files_open_folder_label, path)
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .clickable(role = Role.Button) { onFolder(path) }.semantics { contentDescription = label }
        .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(name, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}
