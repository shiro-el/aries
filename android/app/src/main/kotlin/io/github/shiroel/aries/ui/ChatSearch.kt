package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A searchable piece of the cached Chat, with its position in the transcript's lazy list. */
internal data class ChatSearchField(
    val entryId: String, val key: String, val text: String, val itemIndex: Int,
    /** Non-null only when lazy layout split one logical text into adjacent rows. */
    val joinPrevious: String? = null,
)
internal data class ChatSearchRange(val field: ChatSearchField, val start: Int, val end: Int)
internal data class ChatSearchMatch(
    val field: ChatSearchField, val start: Int, val end: Int,
    val continuation: List<ChatSearchRange> = emptyList(),
) {
    val ranges: List<ChatSearchRange> get() = listOf(ChatSearchRange(this.field, start, end)) + continuation
    fun sameOccurrence(other: ChatSearchMatch) = field.entryId == other.field.entryId &&
        field.key == other.field.key && start == other.start && end == other.end
}

internal fun searchChat(fields: List<ChatSearchField>, query: String): List<ChatSearchMatch> {
    if (query.isEmpty()) return emptyList()
    return buildList {
        var group = mutableListOf<Pair<Int, ChatSearchField>>()
        val text = StringBuilder()
        fun flush() {
            var from = 0
            var segment = 0
            while (from <= text.length - query.length) {
                val start = text.indexOf(query, from, ignoreCase = true)
                if (start < 0) break
                val end = start + query.length
                while (segment < group.size && group[segment].first + group[segment].second.text.length <= start) segment++
                val ranges = buildList {
                    var part = segment
                    while (part < group.size && group[part].first < end) {
                        val (offset, field) = group[part++]
                        val localStart = (start - offset).coerceAtLeast(0)
                        val localEnd = (end - offset).coerceAtMost(field.text.length)
                        if (localStart < localEnd) add(ChatSearchRange(field, localStart, localEnd))
                    }
                }
                ranges.firstOrNull()?.let { add(ChatSearchMatch(it.field, it.start, it.end, ranges.drop(1))) }
                from = end
            }
            group = mutableListOf()
            text.clear()
        }
        fields.forEach { field ->
            if (field.joinPrevious == null || group.lastOrNull()?.second?.entryId != field.entryId) flush()
            else text.append(field.joinPrevious)
            group += text.length to field
            text.append(field.text)
        }
        flush()
    }
}

internal fun selectedChatSearchIndex(matches: List<ChatSearchMatch>, selected: ChatSearchMatch?): Int =
    if (matches.isEmpty()) -1 else matches.indexOfFirst { selected?.sameOccurrence(it) == true }.coerceAtLeast(0)

internal fun navigateChatSearch(matches: List<ChatSearchMatch>, selected: ChatSearchMatch?, direction: Int): ChatSearchMatch? {
    if (matches.isEmpty()) return null
    if (selected == null) return if (direction > 0) matches.first() else matches.last()
    return matches[Math.floorMod(selectedChatSearchIndex(matches, selected) + direction, matches.size)]
}

/** Index the same rendered text used by the UI, including tool bodies hidden behind disclosure. */
internal fun chatSearchFields(entries: List<ChatEntry>, markdown: Map<String, ChatMarkdownDocument>): List<ChatSearchField> = buildList {
    var item = 0
    entries.forEach { entry ->
        fun field(key: String, text: String?, itemIndex: Int = item, joinPrevious: String? = null) {
            if (!text.isNullOrEmpty()) add(ChatSearchField(entry.id, key, text, itemIndex, joinPrevious))
        }
        when (val body = entry.body) {
            is ChatEntryBody.User, is ChatEntryBody.Agent, is ChatEntryBody.Thinking -> {
                if (body.markdownSource() == null) {
                    item++
                    return@forEach
                }
                val blocks = markdown[entry.id]?.blocks.orEmpty()
                blocks.forEachIndexed { index, block ->
                    val key = "markdown:$index"
                    when (block) {
                        is ChatMarkdownBlock.Prose -> field(key, block.text.text, item + 1 + index, if (block.continuation) "" else null)
                        is ChatMarkdownBlock.CodeLines -> {
                            val previous = blocks.getOrNull(index - 1) as? ChatMarkdownBlock.CodeLines
                            field(key, block.text.text, item + 1 + index, if (previous?.code === block.code) "\n" else null)
                        }
                        is ChatMarkdownBlock.Cells -> block.cells.forEachIndexed { cell, value ->
                            field("$key:cell:$cell", value.text.text, item + 1 + index)
                        }
                        else -> {}
                    }
                }
                item += blocks.size + 2
            }
            is ChatEntryBody.Tool -> {
                field("tool-name", body.name)
                field("tool-title", body.title)
                body.diff?.let { field("tool-diff", diffText(it, Int.MAX_VALUE, Color.Unspecified, Color.Unspecified, Color.Unspecified).text) }
                field("tool-input", body.input)
                field("tool-output", body.output)
                item++
            }
            is ChatEntryBody.AgentError -> { field("error", body.detail); item++ }
            is ChatEntryBody.Question -> {
                val answers = questionAnswers(body)
                body.questions.forEachIndexed { part, question ->
                    field("question:$part:header", question.header)
                    field("question:$part", question.text)
                    if (body.status == io.github.shiroel.aries.core.ChatToolStatus.DONE) {
                        question.options.forEachIndexed { index, option -> field("question:$part:option:$index", option.label) }
                        field("question:$part:answer", answers[part]?.text)
                        field("question:$part:notes", answers[part]?.notes)
                    }
                }
                item++
            }
            ChatEntryBody.Unsupported -> item++
        }
    }
}

/** Search the whole cached transcript off the UI thread; a changed query cancels publication of stale results. */
@Composable
internal fun rememberChatSearch(
    entries: List<ChatEntry>, markdown: Map<String, ChatMarkdownDocument>, query: String,
): State<List<ChatSearchMatch>> = produceState(emptyList(), entries, markdown, query) {
    value = emptyList()
    if (query.isNotEmpty()) value = withContext(Dispatchers.Default) { searchChat(chatSearchFields(entries, markdown), query) }
}

internal data class ChatSearchDecoration(
    val ranges: Map<Pair<String, String>, List<ChatSearchRange>>, val active: ChatSearchMatch?,
)
internal val LocalChatSearch = staticCompositionLocalOf { ChatSearchDecoration(emptyMap(), null) }
internal val LocalChatSearchEntry = staticCompositionLocalOf { "" }

@Composable
internal fun ChatSearchBar(
    open: Boolean,
    query: String,
    current: Int,
    count: Int,
    onQuery: (String) -> Unit,
    onMove: (Int) -> Unit,
    onClose: () -> Unit,
) {
    if (!open) return
    val focus = remember { FocusRequester() }
    val label = stringResource(R.string.chat_search_hint)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = label },
                label = { Text(label) }, singleLine = true, shape = RectangleShape,
            )
            ActionIcon(R.drawable.ic_x, R.string.chat_search_close, onClose)
        }
        Row {
            Text(stringResource(R.string.chat_search_count, current, count), Modifier.weight(1f).padding(12.dp))
            ActionIcon(R.drawable.ic_chevron_up, R.string.chat_search_previous, { onMove(-1) }, enabled = count > 0)
            ActionIcon(R.drawable.ic_chevron_down, R.string.chat_search_next, { onMove(1) }, enabled = count > 0)
        }
    }
    LaunchedEffect(focus) { focus.requestFocus() }
}

/** Native Text with search spans layered over Markdown, code and diff styles. */
@Composable
internal fun SearchableChatText(
    text: AnnotatedString,
    key: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    val decoration = LocalChatSearch.current
    val entry = LocalChatSearchEntry.current
    val matches = decoration.ranges[entry to key].orEmpty()
    val active = decoration.active?.ranges?.firstOrNull { it.field.entryId == entry && it.field.key == key }
    val target = decoration.active?.takeIf { it.field.entryId == entry && it.field.key == key }
    val palette = MaterialTheme.colorScheme
    val highlighted = remember(text, matches, active, palette) {
        AnnotatedString.Builder(text).apply {
            matches.forEach { match ->
                if (match.end <= text.length) {
                    val selected = active == match
                    addStyle(SpanStyle(
                        background = if (selected) palette.primary else palette.secondaryContainer,
                        color = if (selected) palette.onPrimary else palette.onSecondaryContainer,
                    ), match.start, match.end)
                    addStringAnnotation("chat-search", if (selected) "active" else "match", match.start, match.end)
                }
            }
        }.toAnnotatedString()
    }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        highlighted, modifier.bringIntoViewRequester(requester), color = color, fontFamily = fontFamily,
        fontStyle = fontStyle, fontWeight = fontWeight, fontSize = fontSize, lineHeight = lineHeight,
        textAlign = textAlign, maxLines = if (matches.isEmpty()) maxLines else Int.MAX_VALUE,
        softWrap = softWrap, style = style, onTextLayout = { layout = it },
    )
    LaunchedEffect(target, layout) {
        val result = layout
        if (target != null && result != null && target.start < result.layoutInput.text.length) {
            requester.bringIntoView(result.getBoundingBox(target.start))
        }
    }
}

@Composable
internal fun SearchableChatText(
    text: String,
    key: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    fontStyle: FontStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) = SearchableChatText(AnnotatedString(text), key, modifier, color = color, fontFamily = fontFamily,
    fontStyle = fontStyle, maxLines = maxLines, style = style)
