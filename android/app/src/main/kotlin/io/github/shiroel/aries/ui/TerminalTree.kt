package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.TerminalInfo

/** A flat list retains the core's facts; this projection adds only display order. */
internal data class TerminalTreeRow<T>(val value: T, val guide: String, val depth: Int)

/** Whole addresses, bounded traversal and a visited set keep missing/cyclic parents visible. */
internal fun <T> terminalTree(
    entries: List<T>,
    rootGuides: Boolean = false,
    namespace: (T) -> String = { "" },
    terminal: (T) -> TerminalInfo,
): List<TerminalTreeRow<T>> {
    val byAddress = entries.associateBy { namespace(it) to terminal(it).address }
    val children = entries.groupBy { namespace(it) to terminal(it).parent }
    val seen = mutableSetOf<Pair<String, String>>()
    val result = mutableListOf<TerminalTreeRow<T>>()
    fun visit(entry: T, ancestorGuides: String, depth: Int, last: Boolean) {
        val info = terminal(entry)
        if (!seen.add(namespace(entry) to info.address)) return
        val guide = if (depth == 0) "" else ancestorGuides + if (last) "└─" else "├─"
        result += TerminalTreeRow(entry, guide, depth)
        if (depth >= 32) return
        val next = if (depth == 0) "" else ancestorGuides + if (last) "  " else "│ "
        val descendants = children[namespace(entry) to info.address].orEmpty().sortedBy { terminal(it).address }
        descendants.forEachIndexed { index, child -> visit(child, next, depth + 1, index == descendants.lastIndex) }
    }
    val roots = entries.filter { (namespace(it) to terminal(it).parent) !in byAddress }
    roots.forEachIndexed { index, entry -> visit(entry, "", if (rootGuides) 1 else 0, index == roots.lastIndex) }
    // No root in a cycle, or a chain beyond the bound: do not lose its rows.
    entries.forEach { visit(it, "", if (rootGuides) 1 else 0, true) }
    return result
}

/** Agent lists also retain a listed plain Terminal when it is an Agent's ancestor. */
internal fun agentFamily(host: Host, terminals: List<TerminalInfo>): List<AgentEntry> {
    val byAddress = terminals.associateBy { it.address }
    val included = mutableSetOf<String>()
    terminals.filter { it.agentStatus != null }.forEach { agent ->
        var current: TerminalInfo? = agent
        repeat(33) {
            val t = current
            if (t != null && included.add(t.address)) current = t.parent?.let(byAddress::get)
            else current = null
        }
    }
    return terminals.filter { it.address in included }.map { AgentEntry(host, it) }
}

@Composable
internal fun TerminalBranch(guide: String, depth: Int, content: @Composable () -> Unit) {
    Row {
        if (depth > 0) Text(
            guide, Modifier.padding(start = 12.dp).width((depth * 20).dp), // i18n-ok: decorative Console tree guides
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}
