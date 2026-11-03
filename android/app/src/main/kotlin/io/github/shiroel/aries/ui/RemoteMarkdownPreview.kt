package io.github.shiroel.aries.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** Read complete fences across file.read pages; retain the source view for line navigation. */
@Composable
internal fun RemoteMarkdownPreview(connection: HostConnection?, address: String, path: String, modifier: Modifier = Modifier) {
    var document by remember { mutableStateOf<ChatMarkdownDocument?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tooLarge by remember { mutableStateOf(false) }
    var notText by remember { mutableStateOf(false) }
    LaunchedEffect(connection, address, path) {
        try {
            document = withContext(Dispatchers.Default) {
                val source = StringBuilder()
                var offset = 1uL
                var bytes = 0
                while (true) {
                    ensureActive()
                    val page = checkNotNull(connection).readFilePage(address, path, offset, 200u)
                    bytes += page.text.toByteArray(Charsets.UTF_8).size
                    if (bytes > 8 * 1024 * 1024) { tooLarge = true; return@withContext null }
                    if ('\u0000' in page.text) { notText = true; return@withContext null }
                    source.append(page.text)
                    if (page.last >= page.lines || page.lines == 0uL) break
                    check(page.last >= offset)
                    offset = page.last + 1uL
                }
                val language = if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) == "md") null else "mermaid"
                parseChatMarkdown(source.toString(), language)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is CoreException.Rpc && e.kind == "not_text") notText = true else failed = true
        }
    }
    when {
        tooLarge -> Text(stringResource(R.string.files_preview_too_large, 8L), color = MaterialTheme.colorScheme.error)
        notText -> Text(stringResource(R.string.files_not_text))
        failed -> Text(stringResource(R.string.files_preview_failed), color = MaterialTheme.colorScheme.error)
        document == null -> Text(stringResource(R.string.files_loading))
        else -> LazyColumn(modifier) { chatMarkdownItems("file:$path", document) }
    }
}
