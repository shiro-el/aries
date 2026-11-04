package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A repository is resolved once per opening. All later requests use that pinned identity. */
@Composable
internal fun GithubDialog(link: HostLink?, terminal: TerminalInfo, onDismiss: () -> Unit) {
    var repository by remember { mutableStateOf<GithubRepository?>(null) }
    var kind by remember { mutableStateOf(GithubKind.ISSUE) }
    var state by remember { mutableStateOf(GithubState.OPEN) }
    var input by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var revision by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<GithubPage?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<GithubItem?>(null) }
    var prFile by remember(selected?.number) { mutableStateOf<GithubPrFile?>(null) }
    var writing by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val connection = if (link?.state == LinkState.Up) link.connection else null
    fun back() { if (!writing) {
        if (prFile != null) prFile = null else if (selected != null) selected = null else onDismiss()
    } }
    LaunchedEffect(connection, kind, state, search, page, revision, selected?.number) {
        if (selected != null) { loading = false; return@LaunchedEffect }
        error = null
        result = null
        loading = true
        try {
            val conn = connection ?: throw CoreException.Closed()
            val repo = repository ?: conn.githubRepository(terminal.address).also { repository = it }
            result = conn.githubList(repo, kind, state, search, page.toUInt())
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) { error = githubError(e)
        } finally { loading = false }
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = ::back)
        Surface(Modifier.fillMaxSize().testTag("github-dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(R.drawable.ic_arrow_left, R.string.back_button, ::back, enabled = !writing, modifier = Modifier.testTag("github-back"))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_github), null, Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.github_button), style = MaterialTheme.typography.titleMedium)
                            repository?.let { Text(it.nameWithOwner, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    repository?.let { repo ->
                        ActionIcon(R.drawable.ic_external_link, R.string.github_open_browser, {
                            val item = selected
                            val path = if (item == null) "" else "/${if (item.kind == GithubKind.PULL_REQUEST) "pull" else "issues"}/${item.number}"
                            uriHandler.openUri("https://${repo.hostname}/${repo.nameWithOwner}$path")
                        })
                    }
                    ActionIcon(R.drawable.ic_refresh_cw, R.string.github_refresh, { revision++ }, enabled = !loading && !writing)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val opened = selected
                if (opened != null) {
                    GithubConversation(connection, repository!!, opened, revision, { writing = it }, prFile, { prFile = it }, Modifier.weight(1f))
                } else {
                    SecondaryTabRow(selectedTabIndex = kind.ordinal) {
                        GithubKind.entries.forEach { value ->
                            Tab(selected = kind == value, onClick = { kind = value; page = 1 },
                                text = { Text(stringResource(if (value == GithubKind.ISSUE) R.string.github_issues else R.string.github_prs)) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        fun submitSearch() { search = input; page = 1; revision++ }
                        OutlinedTextField(value = input, onValueChange = { input = it.take(256) },
                            singleLine = true, placeholder = { Text(stringResource(R.string.github_search_hint)) },
                            trailingIcon = { ActionIcon(R.drawable.ic_search, R.string.github_search_button, ::submitSearch) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                            modifier = Modifier.weight(1f).testTag("github-search"))
                    }
                    Box(Modifier.padding(horizontal = 8.dp)) {
                        var filterMenu by remember { mutableStateOf(false) }
                        TextButton(onClick = { filterMenu = true }, modifier = Modifier.testTag("github-state-filter")) {
                            Text(stringResource(githubFilterLabel(state)))
                            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.padding(start = 6.dp).size(16.dp))
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
                    error?.let { Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (result?.limited == true) Text(stringResource(R.string.github_limited), Modifier.padding(12.dp))
                    LazyColumn(Modifier.weight(1f).testTag("github-items")) {
                        if (result?.items?.isEmpty() == true) item { Text(stringResource(R.string.github_empty), Modifier.padding(16.dp)) }
                        items(result?.items.orEmpty(), key = { it.number.toString() }) { item ->
                            GithubItemHeading(item, Modifier.fillMaxWidth().clickable { selected = item }.padding(16.dp))
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
    connection: HostConnection?, repo: GithubRepository, selected: GithubItem,
    refresh: Int, onWriting: (Boolean) -> Unit,
    prFile: GithubPrFile?, onPrFile: (GithubPrFile?) -> Unit, modifier: Modifier,
) {
    var tab by remember(selected.number) { mutableStateOf(GithubPrTab.Conversation) }
    var detail by remember(selected.number) { mutableStateOf<GithubDetail?>(null) }
    var error by remember(selected.number) { mutableStateOf<Int?>(null) }
    var draftError by remember(selected.number) { mutableStateOf<Int?>(null) }
    var success by remember(selected.number) { mutableStateOf<Int?>(null) }
    var draft by remember(selected.number) { mutableStateOf("") }
    var draftReady by remember(selected.number) { mutableStateOf(false) }
    var preview by remember(selected.number) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(connection, repo, selected.number) {
        draftReady = false
        try {
            val conn = connection ?: throw CoreException.Closed()
            draft = conn.githubLoadDraft(repo, selected.number)
            draftError = null
            draftReady = true
        } catch (e: CoreException) { draftError = R.string.github_draft_failed }
    }
    LaunchedEffect(connection, repo, selected.number, refresh, revision) {
        onPrFile(null)
        error = null
        detail = null
        try {
            val conn = connection ?: throw CoreException.Closed()
            detail = conn.githubDetail(repo, selected.number)
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) { error = githubError(e) }
    }
    fun changeDraft(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 65536) { draftError = R.string.github_draft_limit; return }
        draft = text
        draftError = null
        try { connection?.githubSaveDraft(repo, selected.number, text) ?: throw CoreException.Closed()
        } catch (e: CoreException) { draftError = R.string.github_draft_failed }
    }
    fun write(comment: Boolean) {
        val conn = connection ?: return
        val current = detail ?: return
        if (busy) return
        busy = true
        onWriting(true)
        error = null
        success = null
        scope.launch {
            try {
                if (comment) {
                    conn.githubComment(repo, current.item.kind, selected.number, draft)
                    changeDraft("")
                    preview = false
                    success = R.string.github_sent
                } else {
                    conn.githubSetIssueClosed(repo, selected.number, current.item.state == "open")
                    success = R.string.github_state_updated
                }
                revision++
            } catch (e: CancellationException) { throw e
            } catch (e: CoreException) {
                // Reads may reconnect/retry; writes never do. A lost reply may
                // mean GitHub accepted the comment, so preserve it and explain.
                error = if (e is CoreException.Rpc && e.kind in listOf("github_missing", "github_auth")) githubError(e) else R.string.github_unknown
            } finally { busy = false; onWriting(false) }
        }
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
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
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
                var actions by remember { mutableStateOf(false) }
                ActionIcon(R.drawable.ic_ellipsis_vertical, R.string.github_item_actions, { actions = true }, enabled = !busy)
                DropdownMenu(actions, onDismissRequest = { actions = false }) {
                    val closed = detail?.item?.state != "open"
                    ActionMenuItem(stringResource(if (closed) R.string.github_reopen_issue else R.string.github_close_issue),
                        if (closed) R.drawable.ic_history else R.drawable.ic_x,
                        { actions = false; write(false) }, enabled = detail != null && !busy && connection != null)
                }
            }
        }
        if (selected.kind == GithubKind.PULL_REQUEST) {
            SecondaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                GithubPrTab.entries.forEach { value ->
                    Tab(selected = tab == value, enabled = !busy,
                        onClick = { tab = value; onPrFile(null) },
                        text = { Text(stringResource(value.title)) },
                        modifier = Modifier.testTag("github-pr-tab-${value.name.lowercase()}"))
                }
            }
        }
        if (tab != GithubPrTab.Conversation) {
            GithubPrResources(connection, repo, selected.number, tab, refresh, prFile, onPrFile, Modifier.weight(1f))
        } else {
            error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            success?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp)) }
            if (detail == null && error == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val thread = detail
            LazyColumn(Modifier.weight(1f).testTag("github-conversation")) {
                if (preview) {
                    item { Text(stringResource(R.string.github_preview), Modifier.padding(16.dp)) }
                    chatMarkdownItems("preview", markdown["preview"])
                } else if (thread != null) {
                    if (overview != null) githubOverviewItems(overview,
                        onTab = { tab = it; onPrFile(null) },
                        onFile = { tab = GithubPrTab.Files; onPrFile(it) })
                    if (thread.item.body.isBlank()) item { Text(stringResource(R.string.github_no_body), Modifier.padding(16.dp)) }
                    chatMarkdownItems("body", markdown["body"])
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); GithubSection(stringResource(R.string.github_comments), thread.comments.size) }
                    if (thread.comments.isEmpty()) item { Text(stringResource(R.string.github_no_comments), Modifier.padding(16.dp)) }
                    thread.comments.forEach { comment ->
                        item(key = "author:${comment.id}") {
                            Text(stringResource(R.string.github_author_date,
                                comment.author.ifBlank { stringResource(R.string.github_deleted_author) }, comment.createdAt.substringBefore("T")), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        chatMarkdownItems("comment:${comment.id}", markdown["comment:${comment.id}"])
                    }
                }
            }
            draftError?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (!preview) OutlinedTextField(value = draft, onValueChange = ::changeDraft,
                enabled = draftReady && !busy && connection != null, minLines = 2, maxLines = 5,
                label = { Text(stringResource(R.string.github_comment_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("github-comment"))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { preview = !preview }, enabled = draftReady && !busy) { Text(stringResource(if (preview) R.string.github_edit else R.string.github_preview)) }
                Button(onClick = { write(true) }, enabled = draftReady && draft.isNotBlank() && detail != null && connection != null && !busy, modifier = Modifier.padding(8.dp)) {
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
