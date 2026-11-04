package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.delay
import java.util.Locale

/** A live watch replaces the entire stored source for its Host, even when empty. */
data class QuickOpenSource(
    val host: Host,
    val structure: HostStructure?,
    val terminals: List<TerminalInfo>,
    val fetchedAtMs: ULong? = null,
    val structureFetchedAtMs: ULong? = fetchedAtMs,
    val live: Boolean = true,
)

enum class QuickOpenKind(@StringRes val label: Int) {
    HOST(R.string.quick_open_host), SPACE(R.string.quick_open_space),
    TAB(R.string.quick_open_tab), AGENT(R.string.quick_open_agent),
}

/** Whole Host, Space, Tab and Terminal identity; names are display-only. */
data class QuickOpenResult(
    val host: Host,
    val kind: QuickOpenKind,
    val name: String,
    val spaceId: String? = null,
    val tabId: String? = null,
    val address: String? = null,
    val context: String = host.name,
    val fetchedAtMs: ULong? = null,
    val live: Boolean = true,
) {
    val key: String get() = listOf(host.id, kind.name, spaceId.orEmpty(), tabId.orEmpty(), address.orEmpty()).joinToString("\n")
}

/** Exact > prefix > substring > ordered-subsequence, then compactness and name.
 * Name matches always precede matches against the full Host/Space/Tab path. */
internal fun quickOpenScore(query: String, value: String): Long? {
    val q = query.trim().lowercase(Locale.ROOT)
    val name = value.lowercase(Locale.ROOT)
    if (q.isEmpty()) return 0L
    if (name == q) return 0L
    if (name.startsWith(q)) return 1_000_000L + name.length
    val substring = name.indexOf(q)
    if (substring >= 0) return 2_000_000L + substring * 100L + name.length
    var previous = -1
    var first = -1
    var gaps = 0
    for (char in q) {
        val next = name.indexOf(char, previous + 1)
        if (next < 0) return null
        if (first < 0) first = next else gaps += next - previous - 1
        previous = next
    }
    return 3_000_000L + gaps * 100L + first * 10L + name.length
}

fun quickOpenResults(query: String, sources: List<QuickOpenSource>): List<QuickOpenResult> {
    val candidates = sources.flatMap { source ->
        val host = source.host
        buildList {
            add(QuickOpenResult(host, QuickOpenKind.HOST, host.name, fetchedAtMs = source.fetchedAtMs))
            source.structure?.spaces?.forEach { space ->
                add(QuickOpenResult(host, QuickOpenKind.SPACE, space.name, space.id,
                    context = host.name, fetchedAtMs = source.structureFetchedAtMs))
                space.tabs.forEach { tab ->
                    add(QuickOpenResult(host, QuickOpenKind.TAB, tab.name, space.id, tab.id,
                        context = "${host.name} / ${space.name}", fetchedAtMs = source.structureFetchedAtMs))
                }
            }
            source.terminals.filter { it.agentStatus != null }.distinctBy { it.address }.forEach { terminal ->
                val space = source.structure?.spaces?.find { s -> s.tabs.any { tab -> tab.panes.any { pane ->
                    pane.terminalAddress == terminal.address || pane.subAgents.any { it.address == terminal.address }
                } } }
                val tab = space?.tabs?.find { tab -> tab.panes.any { pane ->
                    pane.terminalAddress == terminal.address || pane.subAgents.any { it.address == terminal.address }
                } }
                add(QuickOpenResult(host, QuickOpenKind.AGENT, displayName(terminal), space?.id, tab?.id, terminal.address,
                    listOfNotNull(host.name, space?.name, tab?.name).joinToString(" / "), source.fetchedAtMs))
            }
        }.map { it.copy(live = source.live) }
    }
    if (query.isBlank()) return candidates
    return candidates.mapNotNull { result ->
        val score = quickOpenScore(query, result.name)
            ?: quickOpenScore(query, "${result.context} / ${result.name}")?.plus(10_000_000L)
        score?.let { it to result }
    }.sortedWith(compareBy<Pair<Long, QuickOpenResult>> { it.first }
        .thenBy { it.second.name.lowercase(Locale.ROOT) }.thenBy { it.second.key }).map { it.second }
}

enum class QuickOpenCommand(@StringRes val label: Int) {
    NEW_SPACE(R.string.layout_new_space_button), NEW_TAB(R.string.layout_new_tab_button),
    RENAME_SPACE(R.string.layout_rename_space_button), RENAME_TAB(R.string.layout_rename_tab_button),
    CLOSE_SPACE(R.string.layout_close_space_button), CLOSE_TAB(R.string.layout_close_tab_button),
    END_TERMINAL(R.string.layout_kill_button), RUN_AGENT(R.string.quick_open_run_agent), SETTINGS(R.string.settings_title),
}

internal val LocalQuickOpen = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Shared by the normal and Agent headers. */
@Composable
internal fun QuickOpenButton() {
    val open = LocalQuickOpen.current ?: return
    val label = stringResource(R.string.quick_open_title)
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(48.dp).testTag("quick-open-button").clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = open)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            drawCircle(color, radius = size.width * .30f, center = Offset(size.width * .4f, size.height * .4f), style = Stroke(1.5.dp.toPx()))
            drawLine(color, Offset(size.width * .63f, size.height * .63f), Offset(size.width * .94f, size.height * .94f), strokeWidth = 1.5.dp.toPx())
        }
    }
}

@Composable
internal fun QuickOpenDialog(model: HostsModel) {
    var query by remember { mutableStateOf("") }
    var commandError by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    Dialog(onDismissRequest = model::closeQuickOpen, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 600.dp).imePadding(), shape = RectangleShape) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_open_title), Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = model::closeQuickOpen) { Text(stringResource(R.string.cancel_button)) }
                }
                OutlinedTextField(query, { query = it; commandError = null }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("quick-open-query").focusRequester(focus),
                    label = { Text(stringResource(R.string.quick_open_hint)) })
                commandError?.let { Text(stringResource(it), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                if (query.startsWith(">")) {
                    val commands = QuickOpenCommand.entries.mapNotNull { command ->
                        val label = stringResource(command.label)
                        quickOpenScore(query.drop(1), label)?.let { Triple(command, label, it) }
                    }.sortedBy { it.third }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        if (commands.isEmpty()) item { EmptyQuickOpen() }
                        items(commands, key = { it.first.name }) { (command, label, _) ->
                            Column(Modifier.fillMaxWidth().testTag("quick-command:${command.name}")
                                .clickable {
                                    commandError = model.quickOpenCommandReason(command)
                                    if (commandError == null) model.runQuickOpenCommand(command)
                                }.padding(12.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    val results = quickOpenResults(query, model.quickOpenSources)
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        if (results.isEmpty()) item { EmptyQuickOpen() }
                        items(results, key = { it.key }) { result ->
                            Column(Modifier.fillMaxWidth().testTag("quick-result:${result.key}")
                                .clickable { model.selectQuickOpen(result) }.padding(12.dp)) {
                                Text(result.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.quick_open_result_context, stringResource(result.kind.label), result.context),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (result.live) stringResource(R.string.quick_open_live)
                                    else if (result.fetchedAtMs == null) stringResource(R.string.quick_open_unknown)
                                    else stringResource(R.string.quick_open_age, ((now.toULong().coerceAtLeast(result.fetchedAtMs) - result.fetchedAtMs) / 60_000uL).toLong()),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun EmptyQuickOpen() {
    Text(stringResource(R.string.quick_open_empty), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
}
