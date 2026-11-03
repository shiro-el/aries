package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.MaterialTheme
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
    preserveSiblingOrder: Boolean = false,
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
        val descendants = children[namespace(entry) to info.address].orEmpty().let {
            if (preserveSiblingOrder) it else it.sortedBy { child -> terminal(child).address }
        }
        descendants.forEachIndexed { index, child -> visit(child, next, depth + 1, index == descendants.lastIndex) }
    }
    val roots = entries.filter { (namespace(it) to terminal(it).parent) !in byAddress }
    roots.forEachIndexed { index, entry -> visit(entry, "", if (rootGuides) 1 else 0, index == roots.lastIndex) }
    // No root in a cycle, or a chain beyond the bound: do not lose its rows.
    entries.forEach { visit(it, "", if (rootGuides) 1 else 0, true) }
    return result
}

/** Agent families retain plain ancestors and all live descendants, like herdr-app. */
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
    val children = terminals.filter { it.state == "live" }.groupBy { it.parent }
    val queue = ArrayDeque(included)
    while (queue.isNotEmpty()) {
        children[queue.removeFirst()].orEmpty().forEach { child ->
            if (included.add(child.address)) queue.addLast(child.address)
        }
    }
    return terminals.withIndex().filter { it.value.address in included }.map { AgentEntry(host, it.value, sequence = it.index) }
}

/** Geometric guides span the entire row, including its subtitle and divider. */
@Composable
internal fun TerminalBranch(
    guide: String,
    depth: Int,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val color = MaterialTheme.colorScheme.outline
    val density = androidx.compose.ui.platform.LocalDensity.current
    val titleCenter = with(density) { 8.dp + MaterialTheme.typography.bodyMedium.lineHeight.toDp() / 2 }
    // Keep very deep families usable on a phone; identity/folding retain full depth.
    val visibleDepth = depth.coerceAtMost(4)
    val indent = (visibleDepth * 16).dp
    val leadingWidth = if (leading != null) 32.dp else 0.dp
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().drawBehind {
        val step = 16.dp.toPx()
        val y = titleCenter.toPx()
        guide.chunked(2).takeLast(visibleDepth).forEachIndexed { index, part ->
            val x = index * step + step / 2
            if (part.startsWith("│")) drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f),
                androidx.compose.ui.geometry.Offset(x, size.height), 1.dp.toPx())
            if (part.startsWith("├") || part.startsWith("└")) {
                drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Offset(x, if (part.startsWith("└")) y else size.height), 1.dp.toPx())
                drawLine(color, androidx.compose.ui.geometry.Offset(x, y),
                    androidx.compose.ui.geometry.Offset((index + 1) * step, y), 1.dp.toPx())
            }
        }
    }) {
        androidx.compose.foundation.layout.Box(Modifier.padding(start = indent + leadingWidth)) { content() }
        if (leading != null) androidx.compose.foundation.layout.Box(Modifier.padding(start = indent)) { leading() }
    }
}
