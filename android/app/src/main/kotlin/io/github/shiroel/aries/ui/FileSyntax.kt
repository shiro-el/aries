package io.github.shiroel.aries.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Lightweight, page-local highlighting. Unknown formats remain plain text. */
internal fun highlightFileCode(text: String, path: String, colors: ColorScheme): AnnotatedString {
    val extension = path.substringAfterLast('.', "").lowercase()
    val hashComments = extension in setOf("py", "sh", "bash", "rb", "yaml", "yml", "toml")
    val supported = hashComments || extension in setOf("kt", "kts", "java", "rs", "go", "c", "h", "cpp", "hpp", "cs", "swift", "js", "jsx", "ts", "tsx", "json", "css", "sql")
    if (!supported) return AnnotatedString(text)
    val comment = when {
        hashComments -> "#[^\\n]*"
        extension == "sql" -> "--[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)"
        extension == "json" -> "(?!)"
        else -> "//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)"
    }
    val tokens = Regex("(?<comment>$comment)|(?<string>\"(?:\\\\.|[^\"\\\\]++)*\"|'(?:\\\\.|[^'\\\\\\n]++)*')|(?<number>\\b[0-9]+(?:\\.[0-9]+)?\\b)|(?<word>[A-Za-z_][A-Za-z_0-9]*)")
    return buildAnnotatedString {
        append(text)
        // Bound styling work even when a single Host line approaches the response budget.
        for (token in tokens.findAll(text.take(200_000))) {
            val color = when {
                token.groups["comment"] != null -> colors.onSurfaceVariant
                token.groups["string"] != null -> colors.tertiary
                token.groups["number"] != null -> colors.secondary
                token.value.lowercase() in keywords -> colors.primary
                else -> continue
            }
            addStyle(SpanStyle(color = color), token.range.first, token.range.last + 1)
        }
    }
}

private val keywords = ("val var fun fn func def class interface struct enum impl trait public private protected " +
    "internal static final const let mut return if else when match switch case for while do in is as " +
    "import package use from export default async await suspend try catch finally throw throws new " +
    "true false null none nil self this super void int string bool boolean type typeof extends " +
    "break continue yield lambda with pass raise and or not select from where join on insert update").split(' ').toSet()
