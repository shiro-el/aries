package io.github.shiroel.aries

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Keeps the UI translatable. A language is added by dropping in
 * `res/values-<lang>/strings.xml` (see "UI strings" in CONTRIBUTING.md); this
 * test then holds it to the same keys and format arguments as English.
 * It also fails on hard-coded UI text in Kotlin sources.
 */
class TranslationsTest {
    private val res = File("src/main/res")
    private val kotlin = File("src/main/kotlin")

    /** Languages Aries ships. Every one of them must be complete. */
    private val shipped = setOf("ko")

    @Test
    fun shippedLanguagesExist() {
        val present = translations().keys
        val missing = shipped - present
        assertTrue("missing translation directories: ${missing.map { "values-$it" }}", missing.isEmpty())
    }

    @Test
    fun everyTranslationHasEveryStringWithTheSameArguments() {
        val base = strings(res.resolve("values"))
        assertTrue("no strings found under $res/values", base.isNotEmpty())
        val translatable = base.filterValues { it.translatable }
        val problems = mutableListOf<String>()
        for ((lang, dir) in translations()) {
            val tr = strings(dir)
            for (key in translatable.keys - tr.keys) problems += "values-$lang: missing \"$key\""
            for (key in tr.keys - translatable.keys) {
                problems += if (key in base) "values-$lang: \"$key\" is translatable=\"false\""
                else "values-$lang: \"$key\" is not in values/"
            }
            for ((key, s) in tr) {
                val en = translatable[key] ?: continue
                if (s.args != en.args) problems += "values-$lang: \"$key\" has arguments ${s.args}, English has ${en.args}"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n", "Incomplete translations:\n"))
    }

    @Test
    fun formatArgumentsArePositional() {
        val problems = strings(res.resolve("values")).filter { (_, s) -> s.unpositional }.keys
        assertTrue("use %1\$s-style arguments in: $problems", problems.isEmpty())
    }

    // D118: phone and watch additions must keep the same 해요체 voice; lint only checks translation structure.
    @Test
    fun koreanCopyKeepsTheConversationalVoiceOnPhoneAndWatch() {
        val formalEnding = Regex("[가-힣]+(니다|십시오)")
        val problems = listOf(res.resolve("values-ko"), File("../wear/src/main/res/values-ko"))
            .flatMap { dir -> dir.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
            .flatMap { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    .documentElement.childNodes
                (0 until nodes.length).mapNotNull { index ->
                    val element = nodes.item(index) as? Element ?: return@mapNotNull null
                    formalEnding.find(element.textContent)?.let {
                        "${file.path}: ${element.getAttribute("name")}: ${it.value}"
                    }
                }
            }
        assertTrue("Use 해요체:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun uiTextComesFromStringResources() {
        val problems = kotlin.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (HARDCODED_UI_TEXT.containsMatchIn(line) && !line.contains(ALLOW)) "${file.path}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        if (problems.isNotEmpty()) {
            fail(problems.joinToString("\n", "Hard-coded UI text (use stringResource(R.string.…), or mark the line $ALLOW):\n"))
        }
    }

    private class Res(val translatable: Boolean, val args: List<String>, val unpositional: Boolean)

    /** `values-<lang>` directories that hold strings, keyed by qualifier (`ko`, `pt-rBR`, `b+sr+Latn`). */
    private fun translations(): Map<String, File> =
        res.listFiles().orEmpty()
            .filter { it.isDirectory && LANGUAGE_DIR.matches(it.name) && strings(it).isNotEmpty() }
            .associateBy { it.name.removePrefix("values-") }

    private fun strings(dir: File): Map<String, Res> {
        val out = mutableMapOf<String, Res>()
        for (file in dir.listFiles { f -> f.extension == "xml" }.orEmpty()) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName !in RESOURCE_TAGS) continue
                val text = e.textContent
                val args = ARG.findAll(text).map { it.value }.filter { it != "%%" }.toList()
                out[e.getAttribute("name")] = Res(
                    translatable = e.getAttribute("translatable") != "false",
                    args = args.sorted(),
                    unpositional = args.any { !it.contains('$') && it != "%n" },
                )
            }
        }
        return out
    }

    private companion object {
        const val ALLOW = "// i18n-ok"
        val RESOURCE_TAGS = setOf("string", "plurals", "string-array")
        val LANGUAGE_DIR = Regex("""values-([a-z]{2,3}(-r[A-Z]{2})?|b\+[A-Za-z0-9+]+)""")
        val ARG = Regex("""%%|%n|%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")

        /**
         * A string literal containing a letter passed straight to a text-bearing
         * Compose parameter or Android API, e.g. `Text("Hosts")`,
         * `label = "Port"`, `Toast.makeText(ctx, "Done", …)`.
         */
        val HARDCODED_UI_TEXT = Regex(
            """(\bText\s*\(|\b(text|label|title|contentDescription|placeholder|supportingText|message)\s*=|makeText\s*\([^,]*,|setTitle\s*\(|setMessage\s*\()\s*"[^"]*\p{L}""",
        )
    }
}
