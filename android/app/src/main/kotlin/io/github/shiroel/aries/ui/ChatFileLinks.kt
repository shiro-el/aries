package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration

/** A Host path and a one-based line. Shared by Chat and the file viewer. */
data class FileLocation(val path: String, val line: ULong = 1uL, val preview: FilePreview? = null)

/** Bytes already supplied by the transcript; retain its MIME when the Agent resized the file. */
data class FilePreview(val bytes: ByteArray, val mediaType: String)

internal val LocalOpenRemoteFile = staticCompositionLocalOf<((FileLocation) -> Unit)?> { null }
internal const val CHAT_INLINE_CODE = "chat-inline-code"
internal const val CHAT_FILE_DESTINATION = "chat-file-destination"

/** A structured tool path can contain spaces, unlike an unquoted prose token. */
internal fun chatToolPathText(text: String, path: String?): AnnotatedString {
    if (path.isNullOrBlank()) return AnnotatedString(text)
    val start = text.indexOf(path)
    return AnnotatedString.Builder(text).apply {
        if (start >= 0) addStringAnnotation(CHAT_FILE_DESTINATION, path, start, start + path.length)
    }.toAnnotatedString()
}

/** File links remain in the text, so selecting/copying a message preserves it. */
@Composable
fun ChatFileText(text: String, modifier: Modifier = Modifier) {
    val open = LocalOpenRemoteFile.current
    val color = MaterialTheme.colorScheme.primary
    val annotated = remember(text, open, color) {
        annotateChatFileLinks(AnnotatedString(text), open, color)
    }
    Text(annotated, modifier)
}

/** Adds native file actions without removing Markdown styles or replacing explicit web links. */
internal fun annotateChatFileLinks(source: AnnotatedString, open: ((FileLocation) -> Unit)?, color: Color): AnnotatedString {
    val text = annotateChatWebLinks(source, color)
    if (open == null) return text
    val builder = AnnotatedString.Builder(text)
    val reserved = mutableListOf<IntRange>()
    fun add(start: Int, end: Int, token: String) {
        val target = chatFileLocation(token) ?: return
        if (text.getLinkAnnotations(start, end).isNotEmpty() || reserved.any { start <= it.last && end > it.first }) return
        builder.addLink(LinkAnnotation.Clickable(
            tag = "file:$start",
            styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = { open(target) },
        ), start, end)
    }
    text.getStringAnnotations(CHAT_FILE_DESTINATION, 0, text.length).forEach {
        add(it.start, it.end, it.item)
        reserved += it.start until it.end
    }
    text.getStringAnnotations(CHAT_INLINE_CODE, 0, text.length).forEach {
        add(it.start, it.end, text.text.substring(it.start, it.end))
        reserved += it.start until it.end
    }
    for (match in fileTokens.findAll(text.text)) {
        val quoted = match.groups.drop(1).firstOrNull { it != null }
        val token = quoted?.value ?: match.value.trimEnd('.', ',', ';', '!', '?')
        val start = quoted?.range?.first ?: match.range.first
        add(start, start + token.length, token)
    }
    return builder.toAnnotatedString()
}

internal fun chatFileLocation(token: String): FileLocation? {
    if ("://" in token || '@' in token || token.startsWith("www.") || token.startsWith('~')) return null
    val suffix = fileLineSuffix.find(token)
    val path = if (suffix == null) token else token.substring(0, suffix.range.first)
    val line = if (suffix == null) 1uL else suffix.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.toULong() ?: return null
    if (':' in path || path.endsWith('/') || !looksLikeFile(path)) return null
    return FileLocation(path, line)
}

private val fileLineSuffix = Regex(":([0-9]+)$")
// Tokenize entire URLs too, so a path inside one cannot become a local link.
private val fileTokens = Regex("""`([^`\n]+)`|"([^"\n]+)"|'([^'\n]+)'|[^\s`'"<>\[\](){}]+""")
private fun looksLikeFile(path: String): Boolean = '/' in path ||
    path in setOf("Makefile", "Dockerfile", "Containerfile", "LICENSE", "NOTICE", "README") ||
    Regex("""(?:[\p{L}_-][\p{L}\p{N}_.-]*\.[\p{L}][\p{L}\p{N}_-]*|\.[\p{L}][\p{L}\p{N}_.-]*)""").matches(path)
