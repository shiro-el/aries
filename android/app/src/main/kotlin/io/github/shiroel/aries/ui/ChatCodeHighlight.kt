package io.github.shiroel.aries.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** A linear lexer: language keywords, literals and comments; unknown fences stay plain text.
 * Runs on the parsing dispatcher over the whole block so multiline tokens survive lazy chunking.
 * Token colours are applied by Compose from the current Material palette (including dark mode).
 */
internal fun highlightChatCode(source: String, language: String): AnnotatedString {
    val grammar = codeLanguages[language.lowercase()] ?: return AnnotatedString(source)
    val out = AnnotatedString.Builder(source)
    var i = 0
    while (i < source.length) {
        val start = i
        val lineComment = grammar.comments.firstOrNull { source.startsWith(it, i) }
        val kind: String?
        when {
            lineComment != null -> {
                i = source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length
                kind = "comment"
            }
            grammar.blockComments && source.startsWith("/*", i) -> {
                i = source.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: source.length
                kind = "comment"
            }
            source[i] in "\"'`" -> {
                val quote = source[i]
                val delimiter = if (grammar.tripleQuotes && source.startsWith("$quote$quote$quote", i)) "$quote$quote$quote" else "$quote"
                i += delimiter.length
                while (i < source.length) {
                    if (source[i] == '\\') i = (i + 2).coerceAtMost(source.length)
                    else if (source.startsWith(delimiter, i)) { i += delimiter.length; break }
                    else i++
                }
                kind = "string"
            }
            source[i].isDigit() -> {
                i++
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] in "._")) i++
                kind = "number"
            }
            source[i].isLetter() || source[i] in "_$" -> {
                i++
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] in "_$")) i++
                val word = source.substring(start, i).let { if (grammar.ignoreCase) it.lowercase() else it }
                kind = if (word in grammar.keywords) "keyword" else null
            }
            else -> { i++; kind = null }
        }
        if (kind != null) {
            out.addStringAnnotation("syntax", kind, start, i)
            when (kind) {
                "keyword" -> out.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, i)
                "comment" -> out.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, i)
            }
        }
    }
    return out.toAnnotatedString()
}

private data class CodeLanguage(
    val keywords: Set<String>,
    val comments: List<String> = listOf("//"),
    val blockComments: Boolean = true,
    val tripleQuotes: Boolean = false,
    val ignoreCase: Boolean = false,
)

private val codeLanguages: Map<String, CodeLanguage> = buildMap {
    fun language(aliases: String, keywords: String, comments: List<String> = listOf("//"), block: Boolean = true, triple: Boolean = false, ignoreCase: Boolean = false) {
        val grammar = CodeLanguage(keywords.split(' ').toSet(), comments, block, triple, ignoreCase)
        aliases.split(' ').forEach { put(it, grammar) }
    }
    language("kotlin kt kts", "val var fun class interface object package import return if else when for while do try catch finally throw in is as null true false suspend override private public internal protected data sealed enum companion inline reified this super", triple = true)
    language("java", "class interface enum record package import return if else switch case default for while do try catch finally throw throws new null true false public private protected static final void int long double float boolean char byte short extends implements this super synchronized volatile")
    language("javascript js jsx typescript ts tsx", "const let var function class interface type export import from default return if else switch case for of in while do try catch finally throw new null true false undefined async await typeof instanceof this extends implements public private readonly")
    language("python py", "def class return if elif else for in while try except finally raise import from as with lambda yield async await pass break continue and or not is None True False global nonlocal del assert", listOf("#"), block = false, triple = true)
    language("rust rs", "fn let mut pub struct enum impl trait use mod crate self Self super return if else match for in while loop move async await unsafe const static ref where type dyn true false as break continue")
    language("go golang", "func package import var const type struct interface map chan go defer return if else switch case select for range break continue fallthrough nil true false")
    language("c h cpp c++ cc hpp", "auto bool char class const constexpr double enum extern false float for if else inline int long namespace new nullptr private protected public return short signed sizeof static struct switch case template this throw true try catch typedef typename union unsigned using virtual void volatile while delete include define")
    language("csharp cs c#", "using namespace class interface struct enum public private protected internal static readonly const var void int string bool new null true false return if else switch case for foreach in while try catch finally throw async await this base override virtual sealed record get set")
    language("json", "true false null", emptyList(), block = false)
    language("jsonc", "true false null")
    language("bash sh shell zsh", "if then else elif fi for in do done while until case esac function local export return exit true false source", listOf("#"), block = false)
    language("sql", "select from where join inner outer left right on as group by order having limit offset insert into values update set delete create table index drop alter null true false and or not is distinct union all with returning", listOf("--"), ignoreCase = true)
    language("yaml yml", "true false null yes no on off", listOf("#"), block = false, ignoreCase = true)
}
