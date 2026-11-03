package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException

internal enum class GithubPrTab(val title: Int) {
    Conversation(R.string.github_pr_conversation), Files(R.string.github_pr_files),
    Commits(R.string.github_pr_commits), Checks(R.string.github_pr_checks), Reviews(R.string.github_pr_reviews),
}

private sealed interface PrResource {
    data class Files(val data: GithubPrFiles) : PrResource
    data class Commits(val data: GithubPrCommits) : PrResource
    data class Checks(val data: GithubPrChecks) : PrResource
    data class Reviews(val data: List<GithubPrReview>) : PrResource
}

/** Read lazily per tab. Conversation stays composed above this view, retaining draft/preview state. */
@Composable
internal fun GithubPrResources(
    connection: HostConnection?, repo: GithubRepository, number: UInt, tab: GithubPrTab,
    refresh: Int, file: GithubPrFile?, onFile: (GithubPrFile?) -> Unit, modifier: Modifier,
) {
    var resource by remember(connection, repo, number, tab, refresh) { mutableStateOf<PrResource?>(null) }
    var error by remember(connection, repo, number, tab, refresh) { mutableStateOf<Int?>(null) }
    var visible by remember(file) { mutableIntStateOf(200) }
    LaunchedEffect(connection, repo, number, tab, refresh) {
        onFile(null)
        try {
            val conn = connection ?: throw CoreException.Closed()
            resource = when (tab) {
                GithubPrTab.Files -> PrResource.Files(conn.githubPrFiles(repo, number))
                GithubPrTab.Commits -> PrResource.Commits(conn.githubPrCommits(repo, number))
                GithubPrTab.Checks -> PrResource.Checks(conn.githubPrChecks(repo, number))
                GithubPrTab.Reviews -> PrResource.Reviews(conn.githubPrReviews(repo, number))
                GithubPrTab.Conversation -> return@LaunchedEffect
            }
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) {
            error = when {
                e is CoreException.Rpc && e.kind == "github_truncated" -> R.string.github_pr_truncated
                e is CoreException.Rpc && e.kind == "github_unknown" -> R.string.github_failed
                else -> githubError(e)
            }
        }
    }
    val reviews = (resource as? PrResource.Reviews)?.data.orEmpty()
    val entries = remember(reviews) { reviews.map { ChatEntry("review:${it.id}", null, ChatEntryBody.Agent(it.body)) } }
    val markdown by rememberChatMarkdown(entries)
    Column(modifier.fillMaxWidth()) {
        error?.let { Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (resource == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        val opened = file
        if (opened != null) {
            Text(opened.path, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace)
            val patch = opened.patch
            if (patch == null) Text(stringResource(R.string.github_pr_patch_unavailable), Modifier.padding(16.dp))
            else GitDiffView(patch, Modifier.weight(1f), visible, { visible += 200 })
        } else {
            LazyColumn(Modifier.weight(1f).testTag("github-pr-resources")) {
                when (val data = resource) {
                    is PrResource.Files -> {
                        if (data.data.limited) item { Text(stringResource(R.string.github_pr_files_limited), Modifier.padding(16.dp)) }
                        if (data.data.files.isEmpty()) item { Text(stringResource(R.string.github_pr_no_files), Modifier.padding(16.dp)) }
                        items(data.data.files) { entry ->
                            Column(Modifier.fillMaxWidth().clickable { onFile(entry) }.padding(16.dp)) {
                                Text(entry.path, fontFamily = FontFamily.Monospace)
                                entry.previousPath?.let { Text(stringResource(R.string.github_pr_previous_path, it), style = MaterialTheme.typography.bodySmall) }
                                Text(stringResource(R.string.github_pr_file_stats, stringResource(fileStatusLabel(entry.status)), entry.additions.toInt(), entry.deletions.toInt()), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                    is PrResource.Commits -> {
                        if (data.data.limited) item { Text(stringResource(R.string.github_pr_commits_limited), Modifier.padding(16.dp)) }
                        if (data.data.commits.isEmpty()) item { Text(stringResource(R.string.github_pr_no_commits), Modifier.padding(16.dp)) }
                        items(data.data.commits) { commit ->
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(commit.sha, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text(commit.message)
                                PrAuthorDate(commit.author, commit.createdAt)
                            }
                            HorizontalDivider()
                        }
                    }
                    is PrResource.Checks -> {
                        item { Text(data.data.headSha, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        if (data.data.checks.isEmpty()) item { Text(stringResource(R.string.github_pr_no_checks), Modifier.padding(16.dp)) }
                        items(data.data.checks) { check ->
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(check.name)
                                // A run is conclusive only once completed; legacy statuses use status directly.
                                val state = if (check.status == "completed") check.conclusion ?: "unknown" else check.status
                                Text(stringResource(checkStatusLabel(state)), style = MaterialTheme.typography.labelMedium)
                                if (check.description.isNotBlank()) Text(check.description, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                    is PrResource.Reviews -> {
                        if (reviews.isEmpty()) item { Text(stringResource(R.string.github_pr_no_reviews), Modifier.padding(16.dp)) }
                        reviews.forEach { review ->
                            item {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    PrAuthorDate(review.author, review.submittedAt)
                                    Text(stringResource(reviewStatusLabel(review.state)), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            chatMarkdownItems("review:${review.id}", markdown["review:${review.id}"])
                            item { HorizontalDivider() }
                        }
                    }
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun PrAuthorDate(author: String, date: String) {
    val name = author.ifBlank { stringResource(R.string.github_deleted_author) }
    Text(if (date.isBlank()) name else stringResource(R.string.github_author_date, name, date), style = MaterialTheme.typography.bodySmall)
}

private fun fileStatusLabel(status: String): Int = when (status) {
    "added" -> R.string.github_pr_added
    "removed" -> R.string.github_pr_removed
    "renamed" -> R.string.github_pr_renamed
    "copied" -> R.string.github_pr_copied
    else -> R.string.github_pr_changed
}

private fun reviewStatusLabel(state: String): Int = when (state) {
    "APPROVED" -> R.string.github_pr_approved
    "CHANGES_REQUESTED" -> R.string.github_pr_changes_requested
    "COMMENTED" -> R.string.github_pr_commented
    "DISMISSED" -> R.string.github_pr_dismissed
    "PENDING" -> R.string.github_pr_pending
    else -> R.string.github_pr_unknown
}

private fun checkStatusLabel(state: String): Int = when (state) {
    "queued" -> R.string.github_pr_queued
    "in_progress" -> R.string.github_pr_in_progress
    "pending" -> R.string.github_pr_pending
    "waiting" -> R.string.github_pr_waiting
    "requested" -> R.string.github_pr_requested
    "success" -> R.string.github_pr_success
    "failure" -> R.string.github_pr_failure
    "neutral" -> R.string.github_pr_neutral
    "cancelled" -> R.string.github_pr_cancelled
    "skipped" -> R.string.github_pr_skipped
    "timed_out" -> R.string.github_pr_timed_out
    "action_required" -> R.string.github_pr_action_required
    "stale" -> R.string.github_pr_stale
    "error", "startup_failure" -> R.string.github_pr_error
    else -> R.string.github_pr_unknown
}
