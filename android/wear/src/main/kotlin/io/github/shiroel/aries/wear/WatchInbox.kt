package io.github.shiroel.aries.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
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
    MaterialTheme(colors = Colors(
        primary = Color(0xff98c7ff), secondary = Color(0xff202a35),
        background = Color.Black, surface = Color(0xff202a35),
        onPrimary = Color.Black, onSecondary = Color(0xfff2f4f5),
        onBackground = Color(0xfff2f4f5), onSurface = Color(0xfff2f4f5),
    )) {
        val dismiss = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(state = dismiss, onDismissed = { onTarget(null) }, hasBackground = selected != null) { background ->
            if (!background) key(selected?.hostId, selected?.terminal) {
                val scroll = rememberScalingLazyListState(initialCenterItemIndex = 0)
                Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scroll) }) {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(), state = scroll, autoCentering = null,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item { Heading(if (selected == null) stringResource(R.string.wear_inbox_title) else selected.name) }
                        item { Caption(WatchTile.age(LocalContext.current, summary, now)) }
                        if (selected == null) {
                            if (summary.inbox.isEmpty()) item {
                                Caption(stringResource(if (summary.fetchedAtMs == 0L) R.string.wear_open_phone else R.string.wear_no_agents))
                            }
                            val sections = summary.inbox.groupBy { it.status.equals("blocked", true) || it.status.equals("error", true) }
                            listOf(true, false).forEach { attention ->
                                val rows = sections[attention].orEmpty()
                                if (rows.isNotEmpty()) item { Heading(stringResource(if (attention) R.string.wear_inbox_attention else R.string.wear_inbox_agents), attention) }
                                rows.groupBy { it.hostId to it.project }.forEach { (_, agents) ->
                                    item { GroupLabel(agents.first()) }
                                    agents.forEach { agent -> item(key = "${agent.hostId}/${agent.terminal}") {
                                        Chip(
                                            onClick = { onTarget(agent.hostId to agent.terminal) },
                                            label = { Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            secondaryLabel = { Text(statusLabel(agent.status)) },
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
                            item { GroupLabel(selected) }
                            item { Caption(statusLabel(selected.status)) }
                            if (selected.contentHidden) item { Caption(stringResource(R.string.wear_inbox_hidden)) }
                            else {
                                item { DetailSection(R.string.wear_inbox_request, selected.requestBody.ifBlank { stringResource(R.string.wear_inbox_no_request) }) }
                                selected.choices.forEach { choice -> item { Text(choice, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) } }
                                item { DetailSection(R.string.wear_inbox_summary, selected.conversationSummary.ifBlank { stringResource(R.string.wear_inbox_no_summary) }) }
                            }
                            item { detailActions(selected) }
                            item { InboxChip(stringResource(R.string.wear_open_phone), true, { onOpenPhone(selected) }) }
                            item { InboxChip(stringResource(R.string.wear_inbox_back), true, { onTarget(null) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(agent: WatchAgent) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(agent.hostName.ifBlank { agent.hostId }, style = MaterialTheme.typography.caption1)
        if (agent.project.isNotBlank()) Text(agent.project, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.caption2, color = Color(0xffaab8c5))
    }
}

@Composable
private fun Heading(text: String, attention: Boolean = false) {
    Text(text, textAlign = TextAlign.Center, color = if (attention) Color(0xffffca74) else Color(0xfff2f4f5),
        style = MaterialTheme.typography.title3, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Color(0xffaab8c5), style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DetailSection(title: Int, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.caption1, color = Color(0xffffca74))
        Text(text, style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun InboxChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Chip(label = { Text(label) }, onClick = onClick, enabled = enabled,
        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
}

/** 190 = Answer, 192 = Stop, 191 = ongoing progress. No command is dispatched here. */
@Composable
fun WatchDetailActionPlaceholders(agent: WatchAgent) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (agent.status.equals("blocked", true)) InboxChip(stringResource(R.string.wear_inbox_answer), false, {})
        InboxChip(stringResource(R.string.wear_inbox_stop), false, {})
        InboxChip(stringResource(R.string.wear_inbox_watch), false, {})
    }
}

@Composable
private fun statusLabel(status: String): String = stringResource(when (status.lowercase()) {
    "blocked" -> R.string.wear_status_blocked
    "error" -> R.string.wear_status_error
    "done" -> R.string.wear_status_done
    "working" -> R.string.wear_status_working
    else -> R.string.wear_status_idle
})
