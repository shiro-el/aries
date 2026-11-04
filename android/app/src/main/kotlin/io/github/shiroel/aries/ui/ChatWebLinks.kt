package io.github.shiroel.aries.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration

/** Keep offsets in the displayed text; existing Markdown/file links own their entire range. */
internal fun annotateChatWebLinks(text: AnnotatedString, color: Color): AnnotatedString =
    AnnotatedString.Builder(text).apply {
        val style = SpanStyle(color = color, textDecoration = TextDecoration.Underline)
        text.getLinkAnnotations(0, text.length).filter { it.item is LinkAnnotation.Url }.forEach {
            addStyle(style, it.start, it.end)
        }
        chatWebUrls(text.text).forEach { (range, url) ->
            if (text.getLinkAnnotations(range.first, range.last + 1).isEmpty()) {
                addLink(LinkAnnotation.Url(url, TextLinkStyles(style)), range.first, range.last + 1)
            }
        }
    }.toAnnotatedString()

internal fun chatWebUrls(text: String): List<Pair<IntRange, String>> = webUrlPattern.findAll(text).mapNotNull { match ->
    val url = trimWebUrl(match.value)
    if (url.substringAfter("://").isEmpty()) null else (match.range.first until match.range.first + url.length) to url
}.toList()

/** Sentence punctuation is excluded, while balanced parentheses in paths remain part of the URL. */
internal fun trimWebUrl(value: String): String {
    var end = value.length
    val excess = mapOf(')' to '(', ']' to '[', '}' to '{').mapValues { (close, open) ->
        value.count { it == close } - value.count { it == open }
    }.toMutableMap()
    while (end > 0) {
        val last = value[end - 1]
        if (last in ".,;:!?，。；：！？、」』】") end--
        else if ((excess[last] ?: 0) > 0) { excess[last] = excess.getValue(last) - 1; end-- }
        else break
    }
    return value.substring(0, end)
}

private val webUrlPattern = Regex("""https?://[^\s<>"'`「」『』【】]+""", RegexOption.IGNORE_CASE)
