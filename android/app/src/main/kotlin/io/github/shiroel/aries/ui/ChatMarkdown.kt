package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** A message is flattened into the transcript's own lazy items, never a nested vertical scroller. */
internal data class ChatMarkdownDocument(val source: String, val blocks: List<ChatMarkdownBlock>)

internal sealed interface ChatMarkdownBlock {
    data class Prose(
        val text: AnnotatedString,
        val headingLevel: Int = 0,
        val depth: Int = 0,
        val quoted: Boolean = false,
    ) : ChatMarkdownBlock
    data class Cells(val cells: List<MarkdownCell>, val scroll: ScrollState) : ChatMarkdownBlock
    data class CodeHeader(val code: MarkdownCode) : ChatMarkdownBlock
    data class CodeLines(val code: MarkdownCode, val text: AnnotatedString) : ChatMarkdownBlock
    data object Rule : ChatMarkdownBlock
}

internal data class MarkdownCell(val text: AnnotatedString, val header: Boolean, val alignment: TableCell.Alignment?)

internal class MarkdownCode(val source: String, val language: String) {
    val scroll = ScrollState(0)
    // Equal widths keep every lazy chunk on the same horizontal offset, including short chunks.
    val columns = source.lineSequence().maxOfOrNull { line ->
        line.fold(0) { column, char -> column + when { char == '\t' -> 8 - column % 8; char.code >= 0x1100 -> 2; else -> 1 } }
    } ?: 0
}

/** Cache only the current transcript, reuse unchanged messages, and cancel work when Chat leaves. */
@Composable
internal fun rememberChatMarkdown(entries: List<ChatEntry>): State<Map<String, ChatMarkdownDocument>> {
    val sources = remember(entries) {
        entries.mapNotNull { entry ->
            val text = when (val body = entry.body) {
                is ChatEntryBody.User -> body.text
                is ChatEntryBody.Agent -> body.text
                else -> null
            }
            text?.let { entry.id to it }
        }
    }
    return produceState(emptyMap(), sources) {
        val previous = value
        value = withContext(Dispatchers.Default) {
            sources.associate { (id, source) ->
                ensureActive()
                id to (previous[id]?.takeIf { it.source == source } ?: parseChatMarkdown(source))
            }
        }
    }
}

internal fun LazyListScope.chatMarkdownItems(id: String, document: ChatMarkdownDocument?) {
    itemsIndexed(
        document?.blocks.orEmpty(),
        key = { index, _ -> "transcript:$id:markdown:$index" },
        contentType = { _, block -> block::class },
    ) { _, block ->
        ChatMarkdownBlockView(block)
    }
}

@Composable
private fun ChatMarkdownBlockView(block: ChatMarkdownBlock) {
    when (block) {
        is ChatMarkdownBlock.Prose -> {
            val open = LocalOpenRemoteFile.current
            val color = MaterialTheme.colorScheme.primary
            val linked = remember(block.text, open, color) { annotateChatFileLinks(block.text, open, color) }
            val style = when (block.headingLevel) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge
                4 -> MaterialTheme.typography.titleMedium
                5, 6 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            }
            val quoteColor = MaterialTheme.colorScheme.outline
            Text(
                linked,
                Modifier.fillMaxWidth().padding(start = 16.dp + 12.dp * block.depth.coerceAtMost(8), end = 16.dp, top = 4.dp, bottom = 4.dp)
                    .then(if (block.quoted) Modifier.drawBehind {
                        drawLine(quoteColor, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx())
                    }.padding(start = 12.dp) else Modifier)
                    .then(if (block.headingLevel > 0) Modifier.semantics { heading() } else Modifier),
                style = style,
            )
        }
        is ChatMarkdownBlock.Cells -> Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(block.scroll)) {
            block.cells.forEach { cell ->
                val open = LocalOpenRemoteFile.current
                val color = MaterialTheme.colorScheme.primary
                val linked = remember(cell.text, open, color) { annotateChatFileLinks(cell.text, open, color) }
                Text(
                    linked,
                    Modifier.width(160.dp)
                        .background(if (cell.header) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(8.dp),
                    fontWeight = if (cell.header) FontWeight.Bold else null,
                    textAlign = when (cell.alignment) {
                        TableCell.Alignment.RIGHT -> TextAlign.Right
                        TableCell.Alignment.CENTER -> TextAlign.Center
                        else -> TextAlign.Left
                    },
                )
            }
        }
        ChatMarkdownBlock.Rule -> HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        is ChatMarkdownBlock.CodeHeader -> {
            val context = LocalContext.current
            val label = stringResource(R.string.chat_code_label)
            var copied by remember(block.code) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(block.code.language.ifEmpty { label }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, block.code.source))
                    copied = true
                }) { Text(stringResource(if (copied) R.string.chat_code_copied else R.string.chat_code_copy_button)) }
            }
        }
        is ChatMarkdownBlock.CodeLines -> {
            val palette = MaterialTheme.colorScheme
            val highlighted = remember(block.text, palette) {
                AnnotatedString.Builder(block.text).apply {
                    block.text.getStringAnnotations("syntax", 0, block.text.length).forEach {
                        addStyle(SpanStyle(color = when (it.item) {
                            "keyword" -> palette.primary
                            "string" -> palette.tertiary
                            "number" -> palette.secondary
                            else -> palette.onSurfaceVariant
                        }), it.start, it.end)
                    }
                }.toAnnotatedString()
            }
            val width = with(LocalDensity.current) { (9.sp * block.code.columns.coerceAtLeast(1)).toDp() } + 24.dp
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(palette.surfaceVariant).horizontalScroll(block.code.scroll)) {
                Text(
                    highlighted,
                    Modifier.width(width).padding(horizontal = 12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    softWrap = false,
                )
            }
        }
    }
}

private fun parseChatMarkdown(source: String): ChatMarkdownDocument {
    val blocks = mutableListOf<ChatMarkdownBlock>()
    fun code(source: String, info: String) {
        val language = info.trim().takeWhile { !it.isWhitespace() }
        val group = MarkdownCode(source, language)
        val highlighted = highlightChatCode(source, language)
        blocks += ChatMarkdownBlock.CodeHeader(group)
        var start = 0
        var lines = 0
        source.forEachIndexed { index, char ->
            if (char == '\n' && ++lines % 24 == 0) {
                blocks += ChatMarkdownBlock.CodeLines(group, highlighted.subSequence(start, index))
                start = index + 1
            }
        }
        if (start < source.length) {
            val end = if (source.endsWith('\n')) source.length - 1 else source.length
            blocks += ChatMarkdownBlock.CodeLines(group, highlighted.subSequence(start, end))
        }
    }
    fun visit(node: Node, depth: Int = 0, quoted: Boolean = false, prefix: String = "") {
        when (node) {
            is Paragraph, is Heading -> {
                val text = buildAnnotatedString { append(prefix); appendInlines(node) }
                // Bound text layout even for a single enormous paragraph. subSequence preserves spans/links.
                var start = 0
                while (start < text.length) {
                    var end = (start + 2048).coerceAtMost(text.length)
                    if (end < text.length) {
                        // Keep the search bounded for long identifiers / unbroken logs too.
                        for (space in end - 1 downTo start + 1024) {
                            if (text[space] == ' ') { end = space + 1; break }
                        }
                        if (text[end - 1].isHighSurrogate()) end--
                    }
                    blocks += ChatMarkdownBlock.Prose(text.subSequence(start, end), (node as? Heading)?.level ?: 0, depth, quoted)
                    start = end
                }
            }
            is BulletList, is OrderedList -> {
                val start = (node as? OrderedList)?.markerStartNumber ?: 1
                node.children().forEachIndexed { index, item ->
                    val marker = if (node is OrderedList) "${start + index}. " else "• "
                    item.children().forEachIndexed { childIndex, child ->
                        visit(child, depth + 1, quoted, if (childIndex == 0) marker else "")
                    }
                }
            }
            is BlockQuote -> node.children().forEach { visit(it, depth, true) }
            is TableBlock -> {
                val scroll = ScrollState(0)
                node.children().flatMap { it.children() }.filterIsInstance<TableRow>().forEach { row ->
                    blocks += ChatMarkdownBlock.Cells(row.children().filterIsInstance<TableCell>().map { cell ->
                        MarkdownCell(buildAnnotatedString { appendInlines(cell) }, cell.isHeader, cell.alignment)
                    }.toList(), scroll)
                }
            }
            is ThematicBreak -> blocks += ChatMarkdownBlock.Rule
            is FencedCodeBlock -> code(node.literal, node.info)
            is IndentedCodeBlock -> code(node.literal, "")
            is HtmlBlock -> blocks += ChatMarkdownBlock.Prose(AnnotatedString(node.literal), depth = depth, quoted = quoted)
            else -> node.children().forEach { visit(it, depth, quoted, prefix) }
        }
    }
    val document = try {
        Parser.builder().extensions(listOf(TablesExtension.create())).build().parse(source)
    } catch (_: IllegalArgumentException) {
        // A table exceeding the parser's cell budget remains readable as plain Markdown.
        Parser.builder().build().parse(source)
    }
    visit(document)
    return ChatMarkdownDocument(source, blocks)
}

private fun AnnotatedString.Builder.appendInlines(parent: Node) {
    parent.children().forEach { node ->
        when (node) {
            is org.commonmark.node.Text -> append(node.literal)
            is Code -> {
                pushStringAnnotation(CHAT_INLINE_CODE, "")
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
                pop()
            }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node) }
            is Link -> {
                if (node.destination.substringBefore(':').lowercase() in setOf("https", "http", "mailto")) {
                    withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                        appendInlines(node)
                    }
                } else if (chatFileLocation(node.destination) != null) {
                    pushStringAnnotation(CHAT_FILE_DESTINATION, node.destination)
                    appendInlines(node)
                    pop()
                } else appendInlines(node)
            }
            is Image -> {
                appendInlines(node)
                append(" (${node.destination})")
            }
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is HtmlInline -> append(node.literal)
            else -> appendInlines(node)
        }
    }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }
