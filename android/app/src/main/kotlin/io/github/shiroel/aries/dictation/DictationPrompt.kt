package io.github.shiroel.aries.dictation

/** UTF-8 bytes upper-bound Whisper's byte BPE tokens, without shipping its vocabulary. */
object DictationPrompt {
    const val MAX_BYTES = 224

    fun build(vocabulary: String, names: List<String>, lastResponse: String, autoContext: Boolean): String {
        val parts = mutableListOf<String>()
        var exhausted = false
        fun add(value: String, tail: Boolean = false) {
            if (exhausted) return
            val available = MAX_BYTES - parts.joinToString("\n").toByteArray(Charsets.UTF_8).size - if (parts.isEmpty()) 0 else 1
            if (available <= 0) return
            val points = value.trim().codePoints().toArray()
            val selected = mutableListOf<Int>()
            var used = 0
            for (point in if (tail) points.reversedArray() else points) {
                val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
                if (used + size > available) { exhausted = true; break }
                selected.add(point)
                used += size
            }
            if (tail) selected.reverse()
            val text = buildString { selected.forEach { appendCodePoint(it) } }.trim()
            if (text.isNotEmpty()) parts.add(text)
        }
        add(vocabulary)
        if (autoContext) {
            add(names.filter { it.isNotBlank() }.joinToString(", "))
            add(lastResponse, tail = true)
        }
        return parts.joinToString("\n")
    }
}
