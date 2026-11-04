package io.github.shiroel.aries

import android.graphics.Typeface
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Display projections only: never use the result as a question/Choice identity. */
data class NotificationText(val expanded: Spanned) {
    val plain: String get() = expanded.toString().replace(Regex("\\s+"), " ").trim()
}

/** Shared by phone notifications and the phone's Wear summary, before either is truncated. */
object NotificationMarkdown {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

    fun render(markdown: String): NotificationText {
        val out = SpannableStringBuilder()
        fun line() { if (out.isNotEmpty() && out.last() != '\n') out.append('\n') }
        fun span(value: Any, body: () -> Unit) {
            val start = out.length
            body()
            if (out.length > start) out.setSpan(value, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun code(literal: String) {
            line()
            span(TypefaceSpan("monospace")) { out.append(literal.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()) }
            line()
        }
        parser.parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(node: Text) {
                // CommonMark deliberately leaves unmatched delimiters as Text. Old Beacons
                // may have clipped inside emphasis/code, so remove those display remnants.
                out.append(node.literal.replace(Regex("\\*{2,}|`+|(?<!\\S)\\*(?=\\S)|(?<=\\S)\\*(?!\\S)"), "")
                    .replace(Regex("(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])"), ""))
            }
            override fun visit(node: StrongEmphasis) = span(StyleSpan(Typeface.BOLD)) { visitChildren(node) }
            override fun visit(node: Emphasis) = span(StyleSpan(Typeface.ITALIC)) { visitChildren(node) }
            override fun visit(node: Code) = span(TypefaceSpan("monospace")) { out.append(node.literal) }
            override fun visit(node: FencedCodeBlock) = code(node.literal)
            override fun visit(node: IndentedCodeBlock) = code(node.literal)
            override fun visit(node: HtmlInline) = Unit
            override fun visit(node: HtmlBlock) {
                line()
                out.append(Html.fromHtml(node.literal, Html.FROM_HTML_MODE_COMPACT).toString().trim())
                line()
            }
            override fun visit(node: SoftLineBreak) { out.append(' ') }
            override fun visit(node: HardLineBreak) = line()
            override fun visit(node: Paragraph) { visitChildren(node); line() }
            override fun visit(node: Heading) { line(); visitChildren(node); line() }
            override fun visit(node: BlockQuote) { line(); visitChildren(node); line() }
            override fun visit(node: ListItem) { line(); out.append("• "); visitChildren(node); line() }
            override fun visit(node: ThematicBreak) = line()
            override fun visit(node: CustomNode) {
                if (node is TableCell && node.previous is TableCell) out.append(" · ")
                visitChildren(node)
                if (node is TableRow) line()
            }
        })
        var start = 0
        var end = out.length
        while (start < end && out[start].isWhitespace()) start++
        while (end > start && out[end - 1].isWhitespace()) end--
        return NotificationText(SpannedString(out.subSequence(start, end)))
    }
}
