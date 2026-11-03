package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.GitDiffLine

/** Draft-only reference. Replacements use the new side; pure deletions use the old side. */
fun formatGitReference(path: String, lines: List<GitDiffLine>): String {
    val newNumbers = lines.mapNotNull { it.newLine }
    val numbers = newNumbers.ifEmpty { lines.mapNotNull { it.oldLine } }
    val location = when {
        numbers.isEmpty() -> path
        numbers.min() == numbers.max() -> "$path:${numbers.min()}"
        else -> "$path:${numbers.min()}-${numbers.max()}"
    }
    if (lines.isEmpty()) return location
    val snippet = lines.joinToString("\n") { it.text }
    var fence = "```"
    while (snippet.contains(fence)) fence += "`"
    return "$location\n${fence}diff\n$snippet\n$fence"
}
