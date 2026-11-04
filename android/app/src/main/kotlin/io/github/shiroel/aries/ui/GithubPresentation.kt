package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

@Composable
internal fun githubStateColor(state: String): Color = when (state) {
    "open", "success", "APPROVED" -> statusColor(AgentStatus.Done)
    "merged" -> if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFFC4A7F5) else Color(0xFF7444AA)
    "failure", "error", "timed_out", "action_required", "startup_failure", "CHANGES_REQUESTED" -> MaterialTheme.colorScheme.error
    "queued", "pending", "in_progress", "waiting", "requested", "PENDING" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun githubStateIcon(state: String): Int = when (state) {
    "open" -> R.drawable.ic_circle_dot
    "merged" -> R.drawable.ic_git_merge
    "success", "APPROVED" -> R.drawable.ic_check
    "closed", "failure", "error", "timed_out", "action_required", "startup_failure", "CHANGES_REQUESTED" -> R.drawable.ic_x
    "draft" -> R.drawable.ic_git_pull_request
    else -> R.drawable.ic_history
}

internal fun githubItemStateLabel(state: String): Int = when (state) {
    "open" -> R.string.github_open
    "draft" -> R.string.github_draft
    "merged" -> R.string.github_merged
    else -> R.string.github_closed
}

@Composable
internal fun GithubStatus(state: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(githubStateIcon(state)), null, Modifier.size(16.dp), tint = githubStateColor(state))
        Text(label, style = MaterialTheme.typography.labelMedium, color = githubStateColor(state))
    }
}

/** Title is the entry point; repository number, state and author remain secondary. */
@Composable
internal fun GithubItemHeading(item: GithubItem, modifier: Modifier = Modifier, detail: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = if (detail) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            maxLines = if (detail) 4 else 3, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GithubStatus(item.state, stringResource(githubItemStateLabel(item.state)))
            Text(stringResource(R.string.github_number, item.number.toInt()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.author.ifBlank { stringResource(R.string.github_deleted_author) }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GithubFileRow(entry: GithubPrFile, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        FileTypeIcon(entry.path, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.path, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            entry.previousPath?.let { Text(stringResource(R.string.github_pr_previous_path, it),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(stringResource(R.string.github_pr_file_stats, stringResource(fileStatusLabel(entry.status)),
                entry.additions.toInt(), entry.deletions.toInt()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(top = 2.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun GithubSection(title: String, count: Int? = null, onClick: (() -> Unit)? = null) {
    val action = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().then(action).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        count?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onClick != null) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A completed run without a conclusion must never look like a passing check. */
internal fun githubCheckState(check: GithubPrCheck): String =
    if (check.status == "completed") check.conclusion ?: "unknown" else check.status

internal fun githubChecksSummary(checks: List<GithubPrCheck>): String {
    val states = checks.map(::githubCheckState)
    return when {
        states.any { it in setOf("failure", "error", "timed_out", "action_required", "startup_failure") } -> "failure"
        states.any { it in setOf("queued", "pending", "in_progress", "waiting", "requested") } -> "pending"
        states.isNotEmpty() && states.all { it == "success" } -> "success"
        else -> "unknown"
    }
}
