package io.github.shiroel.aries.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.scrollAway
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchSummary

/**
 * Agent identity is always Host + whole Terminal address. Later watch action tickets
 * can replace detailActions without replacing Inbox navigation or copying the detail.
 */
@Composable
fun WatchInbox(
    summary: WatchSummary,
    now: Long,
    target: Pair<String, String>?,
    onTarget: (Pair<String, String>?) -> Unit,
    refreshing: Boolean,
    notice: Int?,
    onRefresh: () -> Unit,
    onOpenPhone: (WatchAgent) -> Unit,
    detailActions: @Composable (WatchAgent) -> Unit = { WatchDetailActionPlaceholders(it) },
) {
    val selected = summary.inbox.firstOrNull { it.hostId == target?.first && it.terminal == target.second }
    BackHandler(selected != null) { onTarget(null) }
    WatchConsoleTheme {
        val inboxScroll = rememberScalingLazyListState(initialCenterItemIndex = 0)
        val dismiss = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(state = dismiss, onDismissed = { onTarget(null) }, hasBackground = selected != null) { background ->
            if (!background) key(selected?.hostId, selected?.terminal) {
                val scroll = if (selected == null) inboxScroll else rememberScalingLazyListState(initialCenterItemIndex = 0)
                Scaffold(timeText = { TimeText(modifier = Modifier.scrollAway(scroll)) }, positionIndicator = { PositionIndicator(scroll) }) {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(), state = scroll, autoCentering = null,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item { Heading(if (selected == null) stringResource(R.string.wear_inbox_title) else selected.name) }
                        item { Caption(WatchTile.age(LocalContext.current, selected?.let { summary.fetchedAt(it.hostId) } ?: summary.fetchedAtMs, now)) }
                        if (selected == null) {
                            if (summary.inbox.isEmpty()) item {
                                Caption(stringResource(if (summary.fetchedAtMs == 0L) R.string.wear_open_phone else R.string.wear_no_agents))
                            }
                            val sections = summary.inbox.groupBy { it.status.equals("blocked", true) || it.status.equals("error", true) }
                            listOf(true, false).forEach { attention ->
                                val rows = sections[attention].orEmpty()
                                if (rows.isNotEmpty()) item { Heading(stringResource(if (attention) R.string.wear_inbox_attention else R.string.wear_inbox_agents), section = true) }
                                rows.groupBy { it.hostId to it.project }.forEach { (_, agents) ->
                                    item { GroupLabel(agents.first(), summary, now) }
                                    agents.forEach { agent -> item(key = "${agent.hostId}/${agent.terminal}") {
                                        Chip(
                                            onClick = { onTarget(agent.hostId to agent.terminal) },
                                            label = { Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            secondaryLabel = { StatusCode(agent) },
                                            icon = { StatusIcon(agent.status) },
                                            shape = WatchConsoleShape,
                                            colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth(),
                                        )
                                    } }
                                }
                            }
                            if (summary.inbox.size < summary.totalAgents) item {
                                Caption(stringResource(R.string.wear_inbox_more, summary.inbox.size))
                            }
                            item {
                                InboxChip(stringResource(if (refreshing) R.string.wear_inbox_refreshing else R.string.wear_inbox_refresh), !refreshing, onRefresh)
                            }
                            notice?.let { resource -> item { Caption(stringResource(resource)) } }
                        } else {
                            item { GroupLabel(selected, summary, now) }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatusIcon(selected.status)
                                    StatusCode(selected)
                                }
                            }
                            if (selected.contentHidden) item { Caption(stringResource(R.string.wear_inbox_hidden)) }
                            else {
                                item { DetailSection(R.string.wear_inbox_request, selected.requestBody.ifBlank { stringResource(if (selected.status.equals("blocked", true)) R.string.wear_open_phone else R.string.wear_inbox_no_request) }) }
                                selected.choices.forEach { choice -> item { Text(choice, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) } }
                                item { DetailSection(R.string.wear_inbox_summary, selected.conversationSummary.ifBlank { stringResource(R.string.wear_inbox_no_summary) }) }
                            }
                            item { detailActions(selected) }
                            item { InboxChip(stringResource(R.string.wear_inbox_open_phone), true, { onOpenPhone(selected) }) }
                            item { InboxChip(stringResource(R.string.wear_inbox_back), true, { onTarget(null) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(agent: WatchAgent, summary: WatchSummary, now: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.wear_row_age, agent.hostName.ifBlank { agent.hostId },
            WatchTile.age(LocalContext.current, summary.fetchedAt(agent.hostId), now)), style = MaterialTheme.typography.caption1)
        if (agent.project.isNotBlank()) Text(agent.project, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.caption2, color = Color(WatchConsole.MUTED))
    }
}

@Composable
private fun Heading(text: String, section: Boolean = false) {
    Text(text, textAlign = TextAlign.Center, color = Color(WatchConsole.SAFFRON),
        style = if (section) MaterialTheme.typography.caption1 else MaterialTheme.typography.title3,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Color(WatchConsole.MUTED), style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DetailSection(title: Int, text: String) {
    Column(Modifier.fillMaxWidth().background(Color(WatchConsole.CARD), WatchConsoleShape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.caption1, color = Color(WatchConsole.SAFFRON))
        Text(text, style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun InboxChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Chip(label = { Text(label) }, onClick = onClick, enabled = enabled,
        colors = ChipDefaults.secondaryChipColors(), shape = WatchConsoleShape, modifier = Modifier.fillMaxWidth())
}

/** Separate composables keep watch actions reusable as the other action slots are filled. */
@Composable
fun WatchDetailActionPlaceholders(agent: WatchAgent) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Answer actions are provided by WatchAnswerActions.
        WatchInterruptAction(agent)
        InboxChip(stringResource(R.string.wear_inbox_watch), false, {})
    }
}

@Composable
private fun StatusCode(agent: WatchAgent) {
    val context = LocalContext.current
    val description = WatchConsole.status(context, agent.status)
    Text(WatchConsole.code(context, agent.status, agent.requestKind), color = Color(WatchConsole.color(agent.status)),
        modifier = Modifier.semantics { contentDescription = description })
}

@Composable
private fun StatusIcon(status: String) {
    Icon(painterResource(WatchConsole.icon(status)), contentDescription = null,
        tint = Color(WatchConsole.color(status)), modifier = Modifier.size(16.dp))
}
