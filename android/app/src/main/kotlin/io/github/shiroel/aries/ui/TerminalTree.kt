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

/** Nine-dp guide lanes with rounded elbows meeting the centre of each child row. */
@Composable
internal fun TerminalBranch(
    guide: String,
    depth: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    // Bound only the visual indent; full addresses and folds retain the complete tree.
    val visibleDepth = depth.coerceAtMost(3)
    val inset = if (depth > 0) 31.dp else 0.dp
    val indent = inset + (visibleDepth * 13).dp
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().drawBehind {
        val step = 13.dp.toPx()
        val lane = 9.dp.toPx()
        val y = size.height / 2
        val stroke = 1.5.dp.toPx()
        val radius = minOf(6.dp.toPx(), y, lane)
        guide.chunked(2).takeLast(visibleDepth).forEachIndexed { index, part ->
            val x = inset.toPx() + index * step
            if (part.startsWith("│") || part.startsWith("├")) drawLine(color,
                androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), stroke)
            if (part.startsWith("├") || part.startsWith("└")) {
                val elbow = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, 0f)
                    lineTo(x, y - radius)
                    quadraticTo(x, y, x + radius, y)
                    lineTo(x + lane, y)
                }
                drawPath(elbow, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            }
        }
    }) {
        androidx.compose.foundation.layout.Box(Modifier.padding(start = indent)) { content() }
    }
}
