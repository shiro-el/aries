package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException

/** A repository is resolved once per opening. All later requests use that pinned identity. */
@Composable
internal fun GithubDialog(link: HostLink?, terminal: TerminalInfo, onDismiss: () -> Unit) {
    val fallbackScope = rememberCoroutineScope()
    val model = link?.github(terminal.address) ?: remember { GithubModel(fallbackScope) }
    var repository by model::repository
    var kind by model::kind
    var state by model::state
    var input by model::input
    var search by model::search
    var page by model::page
    var revision by model::revision
    var result by model::result
    var error by model::error
    var loading by model::loading
    var selected by model::selected
    var prFile by model::prFile
    val writing = repository?.let { repo -> selected?.let { model.conversation(repo, it.number).busy } } == true
    val listScroll = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    val connection = if (link?.state == LinkState.Up) link.connection else null
    fun back() { if (!writing) {
        if (prFile != null) prFile = null else if (selected != null) selected = null else onDismiss()
    } }
    LaunchedEffect(connection, kind, state, search, page, revision) {
        error = null
        val query = GithubQuery(kind, state, search, page)
        if (model.resultQuery != query) result = null
        loading = true
        try {
            val conn = connection ?: throw CoreException.Closed()
            val repo = repository ?: conn.githubRepository(terminal.address).also { repository = it }
            result = conn.githubList(repo, kind, state, search, page.toUInt())
            model.resultQuery = query
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) { error = githubError(e)
        } finally { loading = false }
    }
    CallAttentionDialog(onDismissRequest = ::back, onCloseForCall = onDismiss) {
        BackHandler(onBack = ::back)
        Surface(Modifier.fillMaxSize().testTag("github-dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                ConsoleTopBar(stringResource(R.string.github_button), onMenu = {}, onBack = ::back,
                    showMark = false, showNavigationActions = false, backEnabled = !writing, backTag = "github-back",
                    titleContent = {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_github), null, Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.github_button), style = MaterialTheme.typography.titleMedium)
                                repository?.let { Text(it.nameWithOwner, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }, actions = {
                        repository?.let { repo ->
                            ActionIcon(R.drawable.ic_external_link, R.string.github_open_browser, {
                                val item = selected
                                val path = if (item == null) "" else "/${if (item.kind == GithubKind.PULL_REQUEST) "pull" else "issues"}/${item.number}"
                                uriHandler.openUri("https://${repo.hostname}/${repo.nameWithOwner}$path")
                            })
                        }
                        ActionIcon(R.drawable.ic_refresh_cw, R.string.github_refresh, { revision++ }, enabled = !loading && !writing)
                })
                val opened = selected
                if (opened != null) {
                    GithubConversation(model.conversation(repository!!, opened.number), connection, repository!!, opened, revision, prFile, { prFile = it }, Modifier.weight(1f))
                } else {
                    ConsoleSegments(GithubKind.entries, kind, { kind = it; page = 1 },
                        label = { stringResource(if (it == GithubKind.ISSUE) R.string.github_issues else R.string.github_prs) })
                    Row(Modifier.fillMaxWidth().padding(horizontal = LocalLayoutTokens.current.contentPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        fun submitSearch() { search = input; page = 1; revision++ }
                        ConsoleField(value = input, onValueChange = { input = it.take(256) },
                            singleLine = true, placeholder = { Text(stringResource(R.string.github_search_hint)) },
                            trailingIcon = { ActionIcon(R.drawable.ic_search, R.string.github_search_button, ::submitSearch) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                            modifier = Modifier.weight(1f).testTag("github-search"))
                    }
                    Box(Modifier.padding(horizontal = 8.dp)) {
                        var filterMenu by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { filterMenu = true }, modifier = Modifier.testTag("github-state-filter")) {
                            Text(stringResource(githubFilterLabel(state)))
                            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.padding(start = 6.dp).size(LocalLayoutTokens.current.contentPadding))
                        }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            GithubState.entries.forEach { value ->
                                SingleChoiceMenuItem(selected = state == value,
                                    text = { Text(stringResource(githubFilterLabel(value))) },
                                    onClick = { state = value; page = 1; filterMenu = false },
                                    modifier = Modifier.widthIn(min = 200.dp))
                            }
                        }
                    }
                    error?.let { Text(stringResource(it), Modifier.padding(LocalLayoutTokens.current.contentPadding), color = MaterialTheme.colorScheme.error) }
                    if (loading) ConsoleProgress(Modifier.fillMaxWidth())
                    if (result?.limited == true) Text(stringResource(R.string.github_limited), Modifier.padding(12.dp))
                    LazyColumn(Modifier.weight(1f).testTag("github-items"), state = listScroll) {
                        if (result?.items?.isEmpty() == true) item { Text(stringResource(R.string.github_empty), Modifier.padding(LocalLayoutTokens.current.contentPadding)) }
                        items(result?.items.orEmpty(), key = { it.number.toString() }) { item ->
                            GithubItemHeading(item, Modifier.fillMaxWidth().clickable { selected = item }.padding(LocalLayoutTokens.current.contentPadding))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        ActionIcon(R.drawable.ic_chevron_left, R.string.github_previous, { page-- }, enabled = page > 1 && !loading)
                        Text(stringResource(R.string.github_page, page), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ActionIcon(R.drawable.ic_chevron_right, R.string.github_next, { page++ }, enabled = result?.hasMore == true && !loading)
                    }
                }
            }
        }
    }
}

/** Conversation and draft are independent of list filters, pages, and preview state. */
@Composable
private fun GithubConversation(
    model: GithubConversationModel, connection: HostConnection?, repo: GithubRepository, selected: GithubItem,
    refresh: Int,
    prFile: GithubPrFile?, onPrFile: (GithubPrFile?) -> Unit, modifier: Modifier,
) {
    var tab by model::tab
    var detail by model::detail
    var detailError by model::readError
    val error = model.error ?: detailError
    val draftError = model.draftError
    val success = model.success
    val draft = model.draft
    val draftReady = model.draftReady
    var preview by model::preview
    val busy = model.busy
    val revision = model.revision
    val startTask = LocalStartTask.current
    LaunchedEffect(connection, model) { model.attach(connection) }
    LaunchedEffect(model, refresh) {
        if (model.resourceRefresh != null && model.resourceRefresh != refresh) onPrFile(null)
        model.resourceRefresh = refresh
    }
    LaunchedEffect(connection, repo, selected.number, refresh, revision) {
        detailError = null
        model.refreshing = true
        try {
            val conn = connection ?: throw CoreException.Closed()
            detail = conn.githubDetail(repo, selected.number)
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) { detailError = githubError(e)
        } finally { model.refreshing = false }
    }
    val entries = remember(detail, preview, draft) {
        if (preview) listOf(ChatEntry("preview", null, ChatEntryBody.Agent(draft)))
        else buildList {
            detail?.let { thread ->
                add(ChatEntry("body", null, ChatEntryBody.Agent(thread.item.body)))
                thread.comments.forEach { add(ChatEntry("comment:${it.id}", null, ChatEntryBody.Agent(it.body))) }
            }
        }
    }
    val markdown by rememberChatMarkdown(entries)
    val overview = if (selected.kind == GithubKind.PULL_REQUEST) rememberGithubOverview(connection, repo, selected.number, refresh) else null
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = LocalLayoutTokens.current.contentPadding, top = LocalLayoutTokens.current.contentPadding, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GithubItemHeading(detail?.item ?: selected, detail = true)
                detail?.let { thread ->
                    val head = thread.headBranch
                    val base = thread.baseBranch
                    if (head != null && base != null) Text(
                        stringResource(R.string.github_branches, head, base),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected.kind == GithubKind.ISSUE) Box {
                var actions by rememberSaveable { mutableStateOf(false) }
                ActionIcon(R.drawable.ic_ellipsis_vertical, R.string.github_item_actions, { actions = true }, enabled = !busy)
                DropdownMenu(actions, onDismissRequest = { actions = false }) {
                    val closed = detail?.item?.state != "open"
                    ActionMenuItem(stringResource(if (closed) R.string.github_reopen_issue else R.string.github_close_issue),
                        if (closed) R.drawable.ic_history else R.drawable.ic_x,
                        { actions = false; model.write(connection, false) }, enabled = detail != null && !busy && connection != null)
                }
            }
        }
        if (selected.kind == GithubKind.ISSUE && startTask != null) {
            TextButton(onClick = { detail?.item?.let { startTask(TaskIssue(repo, it)) } },
                enabled = detail != null && connection != null && !busy && repo.on == null) {
                Text(stringResource(R.string.new_task_from_issue))
            }
            if (repo.on != null) Text(stringResource(R.string.new_task_nested_host), Modifier.padding(horizontal = LocalLayoutTokens.current.contentPadding))
        }
        if (selected.kind == GithubKind.PULL_REQUEST) {
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                ConsoleSegments(GithubPrTab.entries, tab, { if (!busy) { tab = it; onPrFile(null) } },
                    modifier = Modifier, segmentWidth = 140.dp, enabled = !busy,
                    label = { stringResource(it.title) },
                    segmentModifier = { Modifier.testTag("github-pr-tab-${it.name.lowercase()}") })
            }
        }
        if (tab != GithubPrTab.Conversation) {
            GithubPrResources(connection, repo, selected.number, tab, refresh, prFile, onPrFile, Modifier.weight(1f))
        } else {
            error?.let { Text(stringResource(it), Modifier.padding(horizontal = LocalLayoutTokens.current.contentPadding), color = MaterialTheme.colorScheme.error) }
            success?.let { Text(stringResource(it), Modifier.padding(horizontal = LocalLayoutTokens.current.contentPadding)) }
            if (model.refreshing || busy) ConsoleProgress(Modifier.fillMaxWidth())
            val thread = detail
            LazyColumn(Modifier.weight(1f).testTag("github-conversation")) {
                if (preview) {
                    item { Text(stringResource(R.string.github_preview), Modifier.padding(LocalLayoutTokens.current.contentPadding)) }
                    chatMarkdownItems("preview", markdown["preview"])
                } else if (thread != null) {
                    if (overview != null) githubOverviewItems(overview,
                        onTab = { tab = it; onPrFile(null) },
                        onFile = { tab = GithubPrTab.Files; onPrFile(it) })
                    if (thread.item.body.isBlank()) item { Text(stringResource(R.string.github_no_body), Modifier.padding(LocalLayoutTokens.current.contentPadding)) }
                    chatMarkdownItems("body", markdown["body"])
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); GithubSection(stringResource(R.string.github_comments), thread.comments.size) }
                    if (thread.comments.isEmpty()) item { Text(stringResource(R.string.github_no_comments), Modifier.padding(LocalLayoutTokens.current.contentPadding)) }
                    thread.comments.forEach { comment ->
                        item(key = "author:${comment.id}") {
                            Text(stringResource(R.string.github_author_date,
                                comment.author.ifBlank { stringResource(R.string.github_deleted_author) }, comment.createdAt.substringBefore("T")), Modifier.padding(horizontal = LocalLayoutTokens.current.contentPadding, vertical = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        chatMarkdownItems("comment:${comment.id}", markdown["comment:${comment.id}"])
                    }
                }
            }
            draftError?.let {
                Text(if (it == R.string.github_draft_limit) stringResource(it, GITHUB_DRAFT_BYTES / 1024) else stringResource(it), Modifier.padding(horizontal = LocalLayoutTokens.current.contentPadding), color = MaterialTheme.colorScheme.error)
                if (it != R.string.github_draft_limit) TextButton(onClick = model::retryDraft, enabled = !busy,
                    modifier = Modifier.testTag("github-draft-retry")) { Text(stringResource(R.string.github_draft_retry)) }
            }
            if (!preview) ConsoleField(value = draft, onValueChange = model::changeDraft,
                enabled = draftReady && !busy && connection != null, minLines = 2, maxLines = 5,
                label = { Text(stringResource(R.string.github_comment_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = LocalLayoutTokens.current.contentPadding).testTag("github-comment"))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { preview = !preview }, enabled = draftReady && !busy) { Text(stringResource(if (preview) R.string.github_edit else R.string.github_preview)) }
                ConsoleButton(onClick = { model.write(connection, true) }, enabled = draftReady && draft.isNotBlank() && detail != null && connection != null && !busy, modifier = Modifier.padding(8.dp)) {
                    Text(stringResource(R.string.github_send))
                }
            }
        }
    }
}

private fun githubFilterLabel(state: GithubState): Int = when (state) {
    GithubState.OPEN -> R.string.github_open
    GithubState.CLOSED -> R.string.github_closed
    GithubState.ALL -> R.string.github_all
}

internal fun githubError(error: CoreException): Int = when (error) {
    is CoreException.Closed -> R.string.github_disconnected
    is CoreException.Rpc -> when (error.kind) {
        "github_missing" -> R.string.github_missing
        "github_auth" -> R.string.github_auth
        "github_truncated" -> R.string.github_truncated
        "github_unknown" -> R.string.github_unknown
        else -> R.string.github_failed
    }
    else -> R.string.github_failed
}
