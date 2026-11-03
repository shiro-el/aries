package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** A Host path and a one-based line. Shared by Chat and the file viewer. */
data class FileLocation(val path: String, val line: ULong = 1uL)

internal val LocalOpenRemoteFile = staticCompositionLocalOf<((FileLocation) -> Unit)?> { null }

/** File links remain in the text, so selecting/copying a message preserves it. */
@Composable
fun ChatFileText(text: String, modifier: Modifier = Modifier) {
    val open = LocalOpenRemoteFile.current
    val color = MaterialTheme.colorScheme.primary
    val annotated = remember(text, open, color) {
        buildAnnotatedString {
            append(text)
            if (open != null) for (match in fileTokens.findAll(text)) {
                val quoted = match.groups.drop(1).firstOrNull { it != null }
                val token = quoted?.value ?: match.value.trimEnd('.', ',', ';', '!', '?')
                if ("://" in token || '@' in token || token.startsWith("www.") || token.startsWith('~')) continue
                val suffix = Regex(":([0-9]+)$").find(token)
                val path = if (suffix == null) token else token.substring(0, suffix.range.first)
                val line = if (suffix == null) 1uL else suffix.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.toULong() ?: continue
                if (':' in path || path.endsWith('/') || !looksLikeFile(path)) continue
                val start = quoted?.range?.first ?: match.range.first
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "file:${match.range.first}",
                        styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { open(FileLocation(path, line)) },
                    ),
                    start, start + token.length,
                )
            }
        }
    }
    Text(annotated, modifier)
}

// Tokenize entire URLs too, so a path inside one cannot become a local link.
private val fileTokens = Regex("""`([^`\n]+)`|"([^"\n]+)"|'([^'\n]+)'|[^\s`'"<>\[\](){}]+""")
private fun looksLikeFile(path: String): Boolean = '/' in path ||
    path in setOf("Makefile", "Dockerfile", "Containerfile", "LICENSE", "NOTICE", "README") ||
    Regex("""(?:[\p{L}_-][\p{L}\p{N}_.-]*\.[\p{L}][\p{L}\p{N}_-]*|\.[\p{L}][\p{L}\p{N}_.-]*)""").matches(path)
