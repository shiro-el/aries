package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.RemoteFilePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** A file is a virtual list of all its lines, with only viewport ranges retained on the phone. */
@Composable
internal fun ColumnScope.RemoteTextFile(
    connection: HostConnection?, address: String, path: String, initialLine: ULong,
    wrap: Boolean, onSend: ((String) -> Unit)?,
) {
    var first by remember { mutableStateOf<RemoteFilePage?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var unavailable by remember { mutableStateOf<ULong?>(null) }
    var large by remember { mutableStateOf(false) }
    var approved by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf(false) }
    LaunchedEffect(connection, address, path, approved) {
        // Size probing is optional: file.read remains authoritative if machine.exec is unavailable.
        if (!inspected) {
            try {
                val probe = checkNotNull(connection).readFileBytes(address, path, 0u, 1u)
                large = probe.totalSize >= 2uL * 1024u * 1024u
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Some Hosts allow file.read without machine.exec. */ }
            inspected = true
        }
        if (large && !approved || first != null) return@LaunchedEffect
        try {
            val conn = checkNotNull(connection)
            first = try { conn.readFilePage(address, path, initialLine, 200u) }
            catch (e: CoreException.Rpc) {
                if (e.kind != "invalid_params" || initialLine <= 1uL) throw e
                val start = conn.readFilePage(address, path, 1u, 200u)
                if (start.lines >= initialLine) throw e
                unavailable = initialLine
                start
            }
            if ('\u0000' in first!!.text) { first = null; error = R.string.files_not_text }
            else if (first!!.text.toByteArray(Charsets.UTF_8).size >= 2 * 1024 * 1024) large = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            error = when {
                e is CoreException.Rpc && e.kind == "not_text" -> R.string.files_not_text
                e is CoreException.Rpc && e.kind == "not_found" -> R.string.files_missing
                else -> R.string.files_failed
            }
        }
    }
    when {
        error != null -> Text(if (error == R.string.files_missing) stringResource(error!!, path) else stringResource(error!!), color = MaterialTheme.colorScheme.error)
        large && !approved -> LargeFileNotice { approved = true }
        first == null -> Text(stringResource(R.string.files_loading))
        else -> {
            unavailable?.let { Text(stringResource(R.string.files_line_unavailable, it.toLong())) }
            PagedFileLines(first!!, path, wrap, onSend) { offset -> checkNotNull(connection).readFilePage(address, path, offset, 200u) }
        }
    }
}

@Composable
internal fun LargeFileNotice(onOpen: () -> Unit) {
    Text(stringResource(R.string.files_large_warning))
    TextButton(onClick = onOpen) { Text(stringResource(R.string.files_large_open)) }
}

private data class TextPage(val first: Int, val lines: List<String>, val starts: List<Int>, val code: AnnotatedString) {
    val last get() = first + lines.size - 1
    val chars get() = lines.sumOf { it.length }
    operator fun contains(line: Int) = line in first..last
}

private suspend fun textPage(page: RemoteFilePage, path: String, colors: ColorScheme): TextPage = withContext(Dispatchers.Default) {
    check(page.first <= Int.MAX_VALUE.toULong() && page.lines <= Int.MAX_VALUE.toULong())
    if ('\u0000' in page.text) throw CoreException.Rpc("not_text", "Binary file")
    val lines = if (page.text.isEmpty()) emptyList() else page.text.split('\n').let {
        if (page.text.endsWith('\n')) it.dropLast(1) else it
    }
    check(page.last + 1uL == page.first + lines.size.toULong())
    var offset = 0
    val starts = lines.map { line -> offset.also { offset += line.length + 1 } }
    TextPage(page.first.toInt(), lines, starts, highlightFileCode(page.text, path, colors))
}

@Composable
private fun ColumnScope.PagedFileLines(
    first: RemoteFilePage, path: String, wrap: Boolean, onSend: ((String) -> Unit)?,
    read: suspend (ULong) -> RemoteFilePage,
) {
    val colors = MaterialTheme.colorScheme
    var pages by remember(colors) { mutableStateOf<List<TextPage>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var notText by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val count = first.lines.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    val state = rememberLazyListState((first.first.toInt() - 1).coerceAtLeast(0))
    val currentRead by rememberUpdatedState(read)
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(first, colors) {
        try { pages = listOf(textPage(first, path, colors)); ready = true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is CoreException.Rpc && e.kind == "not_text") notText = true else failed = true
        }
    }
    fun retain(page: TextPage, visible: IntRange) {
        val kept = pages.filter { it.first != page.first }.toMutableList()
        kept.add(page)
        // Keep the viewport even for pathological giant lines; evict all other ranges first.
        while (kept.size > 6 || kept.sumOf { it.chars.toLong() } > 2 * 1024 * 1024) {
            val index = kept.indexOfFirst { it !== page && (it.last < visible.first || it.first > visible.last) }
            if (index < 0) break
            kept.removeAt(index)
        }
        pages = kept
    }
    suspend fun load(line: Int): TextPage {
        pages.find { line in it }?.let { return it }
        val result = currentRead(line.toULong())
        check(result.lines == first.lines && result.first == line.toULong()) // Refresh instead of joining inconsistent versions.
        val page = textPage(result, path, colors)
        check(page.lines.isNotEmpty())
        return page
    }
    LaunchedEffect(ready, retry, colors) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index + 1 } }.collectLatest { visible ->
            if (visible.isEmpty()) return@collectLatest
            try {
                for (line in visible) {
                    ensureActive()
                    if (pages.none { line in it }) retain(load(line), visible.first()..visible.last())
                }
                failed = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is CoreException.Rpc && e.kind == "not_text") notText = true else failed = true
            }
        }
    }
    var query by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(0) }
    var backwards by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<Int?>(null) }
    var searching by remember { mutableStateOf(false) }
    var noMatch by remember { mutableStateOf(false) }
    LaunchedEffect(query, step, ready, retry) {
        if (!ready || query.isEmpty()) { match = null; noMatch = false; searching = false; return@LaunchedEffect }
        searching = true
        noMatch = false
        if (step == 0) delay(250)
        val anchor = if (step == 0) 0 else match ?: 0
        try {
            // Scan bounded pages without accumulating the file or a list of all matches.
            val result = withContext(Dispatchers.Default) {
                var offset = 1
                var fallback: Pair<Int, TextPage>? = null
                var previous: Pair<Int, TextPage>? = null
                while (offset <= count) {
                    ensureActive()
                    val page = load(offset)
                    for ((index, line) in page.lines.withIndex()) {
                        val number = page.first + index
                        if (!line.contains(query, ignoreCase = true)) continue
                        val hit = number to page
                        if (!backwards && number > anchor) return@withContext hit
                        if (backwards && number < anchor) previous = hit
                        if (backwards || fallback == null) fallback = hit
                    }
                    offset = page.last + 1
                }
                previous ?: fallback
            }
            match = result?.first
            noMatch = result == null
            result?.let { (line, page) -> retain(page, line..line); state.scrollToItem(line - 1) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is CoreException.Rpc && e.kind == "not_text") notText = true else failed = true
        }
        finally { searching = false }
    }
    if (notText) { Text(stringResource(R.string.files_not_text)); return }
    FileFindBar(query, { query = it; step = 0; backwards = false }, searching, noMatch,
        { backwards = true; step++ }, { backwards = false; step++ })
    if (count == 0) Text(stringResource(R.string.files_empty_file))
    else {
        val visible by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.map { it.index + 1 } } }
        Text(stringResource(R.string.files_range, (visible.firstOrNull() ?: first.first.toInt()).toLong(),
            (visible.lastOrNull() ?: first.last.toInt()).toLong(), count.toLong()))
    }
    if (failed) Row {
        Text(stringResource(R.string.files_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.files_refresh_button)) }
    }
    val horizontal = rememberScrollState()
    LazyColumn(Modifier.weight(1f).testTag("files-lines")
        .then(if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(horizontal)), state = state) {
        items(count, key = { it }) { index ->
            val number = index + 1
            val page = pages.find { number in it }
            val line = page?.lines?.get(number - page.first)
            val label = stringResource(R.string.files_send_line_label, number.toLong())
            val highlighted = remember(page, number, colors, query) {
                page?.let {
                    val start = it.starts[number - it.first]
                    markFileMatches(it.code.subSequence(start, start + line!!.length), query, colors)
                }
            }
            Row(Modifier.then(if (wrap) Modifier.fillMaxWidth() else Modifier).testTag("files-line:$number")
                .then(if (onSend == null || line == null) Modifier else Modifier.clickable {
                    onSend(quotedFileReference(path) + ":$number\n" + line)
                }.semantics { contentDescription = label }).padding(vertical = 6.dp)) {
                Text(number.toString(), Modifier.widthIn(min = 48.dp), fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                Text(highlighted ?: AnnotatedString(stringResource(R.string.files_loading)),
                    modifier = if (wrap) Modifier.weight(1f) else Modifier, fontFamily = FontFamily.Monospace, softWrap = wrap)
            }
        }
    }
}
