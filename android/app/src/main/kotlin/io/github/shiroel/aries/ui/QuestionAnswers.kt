package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatQuestionAnswer
import io.github.shiroel.aries.core.ChatToolStatus

/** Older Beacons and cached Chats only have output. Prefer the structured
 * answer; the compatibility path never assigns answers by JSON map order. */
internal fun questionAnswers(q: ChatEntryBody.Question): List<ChatQuestionAnswer?> {
    val raw = q.output.orEmpty()
    val lines = raw.lines()
    return q.questions.mapIndexed { part, question ->
        question.answer ?: if (q.status != ChatToolStatus.DONE) null else {
            val marker = "\"${question.text}\"=\""
            val start = raw.indexOf(marker)
            val value = if (start >= 0) {
                val rest = raw.substring(start + marker.length)
                val next = q.questions.map { rest.indexOf("\", \"${it.text}\"=\"") }.filter { it >= 0 }.minOrNull()
                val end = next ?: rest.lastIndexOf("\".").takeIf { it >= 0 }
                    ?: rest.lastIndex.takeIf { rest.endsWith('"') }
                end?.let { rest.substring(0, it) }
            } else if (lines.size == q.questions.size && !raw.trimStart().startsWith('{') && !raw.contains("\"=\"")) {
                lines[part].takeIf { it.isNotBlank() }
            } else null
            value?.let {
                var rest = it.trim()
                val choices = mutableListOf<UInt>()
                while (rest.isNotEmpty()) {
                    val option = question.options.withIndex().filter { (_, option) ->
                        rest == option.label || (question.multi && rest.startsWith("${option.label}, "))
                    }.maxByOrNull { (_, option) -> option.label.length } ?: break
                    choices += option.index.toUInt()
                    rest = rest.substring(option.value.label.length).removePrefix(", ").trim()
                }
                ChatQuestionAnswer(choices.distinct(), rest.takeIf { it.isNotBlank() }, null)
            }
        }
    }
}
