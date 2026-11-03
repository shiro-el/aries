package io.github.shiroel.aries.speech

import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Text passed to the system speech engine, with code blocks omitted. */
object ReadAloudText {
    fun clean(markdown: String): String {
        val text = StringBuilder()
        Parser.builder().build().parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(node: FencedCodeBlock) = Unit
            override fun visit(node: IndentedCodeBlock) = Unit
            override fun visit(node: HtmlBlock) = Unit
            override fun visit(node: HtmlInline) = Unit
            override fun visit(node: Image) = Unit
            override fun visit(node: Text) { text.append(node.literal) }
            override fun visit(node: Code) { text.append(node.literal) }
            override fun visit(node: SoftLineBreak) { text.append(' ') }
            override fun visit(node: HardLineBreak) { text.append('\n') }
            override fun visit(node: Paragraph) { visitChildren(node); text.append('\n') }
            override fun visit(node: Heading) { visitChildren(node); text.append('\n') }
        })
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
