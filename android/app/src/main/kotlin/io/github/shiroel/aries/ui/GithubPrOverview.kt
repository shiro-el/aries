package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class GithubOverview(
    val checks: GithubPrChecks? = null, val checksError: Int? = null,
    val files: GithubPrFiles? = null, val filesError: Int? = null,
)

/** Key every read to the PR/connection/refresh so a failed refresh cannot show stale success. */
@Composable
internal fun rememberGithubOverview(connection: HostConnection?, repo: GithubRepository, number: UInt, refresh: Int): GithubOverview {
    var overview by remember(connection, repo, number, refresh) { mutableStateOf(GithubOverview()) }
    LaunchedEffect(connection, repo, number, refresh) {
        launch {
            try {
                val data = (connection ?: throw CoreException.Closed()).githubPrChecks(repo, number)
                overview = overview.copy(checks = data)
            } catch (e: CancellationException) { throw e
            } catch (e: CoreException) { overview = overview.copy(checksError = githubError(e)) }
        }
        launch {
            try {
                val data = (connection ?: throw CoreException.Closed()).githubPrFiles(repo, number)
                overview = overview.copy(files = data)
            } catch (e: CancellationException) { throw e
            } catch (e: CoreException) { overview = overview.copy(filesError = githubError(e)) }
        }
    }
    return overview
}

internal fun LazyListScope.githubOverviewItems(overview: GithubOverview, onTab: (GithubPrTab) -> Unit, onFile: (GithubPrFile) -> Unit) {
    item("checks-summary") {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onTab(GithubPrTab.Checks) }
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("github-checks-summary"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val checks = overview.checks
            when {
                overview.checksError != null -> Text(stringResource(overview.checksError), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                checks == null -> {
                    Text(stringResource(R.string.github_pr_checks), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    ConsoleBusyIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                checks.checks.isEmpty() -> Text(stringResource(R.string.github_pr_no_checks), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val state = githubChecksSummary(checks.checks)
                    GithubStatus(state, stringResource(when (state) {
                        "success" -> R.string.github_checks_passed
                        "failure" -> R.string.github_checks_failed
                        "pending" -> R.string.github_checks_pending
                        else -> R.string.github_checks_other
                    }), Modifier.weight(1f))
                    Text(stringResource(R.string.github_checks_count, checks.checks.count { githubCheckState(it) == "success" }, checks.checks.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    item("files-summary") {
        GithubSection(stringResource(R.string.github_pr_files), overview.files?.files?.size) { onTab(GithubPrTab.Files) }
        overview.filesError?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (overview.files == null && overview.filesError == null) ConsoleProgress(Modifier.fillMaxWidth())
        if (overview.files?.files?.isEmpty() == true) Text(stringResource(R.string.github_pr_no_files), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (overview.files?.limited == true) Text(stringResource(R.string.github_pr_files_limited), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    overview.files?.files?.take(3)?.forEach { file ->
        item("summary-file:${file.path}") { GithubFileRow(file) { onFile(file) } }
    }
    item("overview-end") {
        if ((overview.files?.files?.size ?: 0) > 3) TextButton(onClick = { onTab(GithubPrTab.Files) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.github_view_files))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
