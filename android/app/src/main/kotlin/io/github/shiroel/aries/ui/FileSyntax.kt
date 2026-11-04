package io.github.shiroel.aries.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal data class FileLexerState(val quote: String? = null, val blockDepth: Int = 0)
internal data class HighlightedFileCode(val code: AnnotatedString, val next: FileLexerState)

/** Unknown formats remain plain. The caller carries lexical context across contiguous pages. */
internal fun highlightFileCode(text: String, path: String, colors: ColorScheme): AnnotatedString =
    highlightFilePage(text, path, colors, FileLexerState()).code

internal fun highlightFilePage(text: String, path: String, colors: ColorScheme, initial: FileLexerState): HighlightedFileCode {
    val extension = path.substringAfterLast('.', "").lowercase()
    val hashComments = extension in setOf("py", "sh", "bash", "rb", "yaml", "yml", "toml")
    val supported = hashComments || extension in setOf("kt", "kts", "java", "rs", "go", "c", "h", "cpp", "hpp", "cs", "swift", "js", "jsx", "ts", "tsx", "json", "css", "sql")
    if (!supported) return HighlightedFileCode(AnnotatedString(text), FileLexerState())
    var quote = initial.quote
    var depth = initial.blockDepth
    val nested = extension in setOf("kt", "kts", "rs", "swift")
    val code = buildAnnotatedString {
        append(text)
        var cursor = 0
        while (cursor < text.length) {
            val start = cursor
            val color = when {
                depth > 0 || quote == null && !hashComments && extension != "json" && text.startsWith("/*", cursor) -> {
                    if (depth == 0) { depth = 1; cursor += 2 }
                    while (cursor < text.length && depth > 0) {
                        when {
                            text.startsWith("*/", cursor) -> { depth--; cursor += 2 }
                            nested && text.startsWith("/*", cursor) -> { depth++; cursor += 2 }
                            else -> cursor++
                        }
                    }
                    colors.onSurfaceVariant
                }
                quote != null || text[cursor] == '"' || text[cursor] == '\'' || text[cursor] == '`' -> {
                    if (quote == null) {
                        quote = when {
                            text.startsWith("\"\"\"", cursor) -> "\"\"\""
                            text.startsWith("'''", cursor) -> "'''"
                            else -> text[cursor].toString()
                        }
                        cursor += quote.length
                    }
                    val delimiter = quote
                    while (cursor < text.length) {
                        when {
                            text.startsWith(delimiter, cursor) -> { cursor += delimiter.length; quote = null; break }
                            text[cursor] == '\\' -> cursor = minOf(text.length, cursor + 2)
                            text[cursor] == '\n' && delimiter == "'" -> { quote = null; break }
                            else -> cursor++
                        }
                    }
                    colors.tertiary
                }
                hashComments && text[cursor] == '#' || extension == "sql" && text.startsWith("--", cursor) ||
                    !hashComments && extension !in setOf("json", "sql") && text.startsWith("//", cursor) -> {
                    cursor = text.indexOf('\n', cursor).takeIf { it >= 0 } ?: text.length
                    colors.onSurfaceVariant
                }
                text[cursor].isDigit() -> {
                    while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '.')) cursor++
                    colors.secondary
                }
                text[cursor].isLetter() || text[cursor] == '_' -> {
                    while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '_')) cursor++
                    if (text.substring(start, cursor).lowercase() in keywords) colors.primary else null
                }
                else -> { cursor++; null }
            }
            if (color != null && cursor > start) addStyle(SpanStyle(color = color), start, cursor)
        }
    }
    return HighlightedFileCode(code, FileLexerState(quote, depth))
}

private val keywords = ("val var fun fn func def class interface struct enum impl trait public private protected " +
    "internal static final const let mut return if else when match switch case for while do in is as " +
    "import package use from export default async await suspend try catch finally throw throws new " +
    "true false null none nil self this super void int string bool boolean type typeof extends " +
    "break continue yield lambda with pass raise and or not select from where join on insert update").split(' ').toSet()
