package io.github.shiroel.aries.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.SavedLaunch

@Composable
internal fun rememberLayoutEditor(browser: HostBrowser): LayoutEditor {
    val scope = rememberCoroutineScope()
    return remember(browser) { browser.layoutEditor ?: LayoutEditor(browser, scope).also { browser.layoutEditor = it } }
}

private fun hostControlsEnabled(browser: HostBrowser, editor: LayoutEditor) =
    browser.structure != null && !browser.closed && browser.link?.state == LinkState.Up && !editor.busy

/** Host-specific creation entries extend the same sheet used by the Agent list. */
@Composable
internal fun HostActions(
    browser: HostBrowser,
    onRunSaved: (SavedLaunch) -> Unit = {},
    onNewTerminal: (() -> Unit)? = null,
) {
    val startTask = LocalStartTask.current
    val history = LocalCreationHostHistory.current ?: rememberCreationHostHistory()
    val editor = rememberLayoutEditor(browser)
    val enabled = hostControlsEnabled(browser, editor)
    val space = browser.openSpace
    // Keep the controller outside the sheet so its worktree dialog survives sheet dismissal.
    WorktreeControls(browser, action = { worktreesEnabled, openWorktrees ->
        CreateActions(buttonEnabled = enabled, creationEnabled = enabled,
            savedLaunches = browser.savedLaunches, sheetTag = "host-create-sheet", dismissWhenDisabled = true,
            onNewTask = startTask?.let { start -> { history.used(browser.host.id); start(null) } },
            onRunSaved = { history.used(browser.host.id); onRunSaved(it) },
        ) { dismiss ->
            onNewTerminal?.let { launch -> item {
                HostSheetAction(stringResource(R.string.launch_open_button), R.drawable.ic_terminal_view,
                    stringResource(R.string.host_create_terminal_detail), enabled) { dismiss(); launch() }
            } }
            item {
                HostSheetAction(stringResource(R.string.layout_new_space_button), R.drawable.ic_folder,
                    stringResource(R.string.host_create_space_detail), enabled) {
                    dismiss()
                    if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.NEW_SPACE)
                }
                HostSheetAction(stringResource(R.string.layout_new_tab_button), R.drawable.ic_panels,
                    space?.let { stringResource(R.string.host_create_tab_detail, it.name) }
                        ?: stringResource(R.string.host_create_tab_no_space), enabled && space != null) {
                    dismiss()
                    if (space != null && editor.requireAvailable()) editor.naming = LayoutNameForm(
                        LayoutNameKind.NEW_TAB, space.id, folder = space.folder.orEmpty())
                }
                HostSheetAction(stringResource(R.string.worktrees_button), R.drawable.ic_folder_open,
                    stringResource(if (worktreesEnabled) R.string.host_create_worktree_detail else R.string.host_create_worktree_no_folder),
                    enabled && worktreesEnabled) { dismiss(); openWorktrees() }
            }
        }
    })
}

/** The title always identifies the phone's selected Space and Tab, even while the link is down. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostContextTitle(browser: HostBrowser, modifier: Modifier = Modifier) {
    val editor = rememberLayoutEditor(browser)
    val enabled = hostControlsEnabled(browser, editor)
    var switch by remember(browser) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) switch = false }
    val label = stringResource(R.string.menu_switch_context)
    Row(modifier.heightIn(min = 48.dp).testTag("host-layout-context")
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { switch = true }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ConsoleTitle(browser.host.name)
            browser.openSpace?.let { space ->
                Text(listOfNotNull(space.name, browser.tab?.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
    }
    if (switch) ModalBottomSheet(onDismissRequest = { switch = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("host-context-sheet"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                HostSheetHeading(label)
                Text(stringResource(R.string.host_context_edit_hint), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            browser.structure?.spaces.orEmpty().forEach { space ->
                item(key = "space:${space.id}") {
                    HostContextRow(space.name, R.drawable.ic_folder, "host-context-space:${space.id}",
                        space.id == browser.openSpaceId, enabled,
                        onSelect = { browser.openSpace(space.id); switch = false },
                        onRename = {
                            switch = false
                            if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.RENAME_SPACE,
                                space.id, if (space.named) space.name else "")
                        }, onClose = {
                            switch = false
                            if (editor.requireAvailable()) editor.ending = LayoutEndTarget.Space(space.id)
                        }, isSpace = true)
                }
                items(space.tabs, key = { "tab:${space.id}:${it.id}" }) { tab ->
                    HostContextRow(tab.name, R.drawable.ic_panels, "host-context-tab:${space.id}:${tab.id}",
                        space.id == browser.openSpaceId && tab.id == browser.tab?.id, enabled,
                        onSelect = { browser.openSpace(space.id); browser.selectTab(tab); switch = false },
                        onRename = {
                            switch = false
                            if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.RENAME_TAB,
                                tab.id, if (tab.named) tab.name else "")
                        }, onClose = {
                            switch = false
                            if (editor.requireAvailable()) editor.ending = LayoutEndTarget.Tab(tab.id)
                        }, isSpace = false)
                }
            }
            if (browser.structure?.spaces.isNullOrEmpty()) item {
                Text(stringResource(R.string.host_context_empty), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HostContextRow(
    name: String, @DrawableRes icon: Int, tag: String, current: Boolean, enabled: Boolean,
    onSelect: () -> Unit, onRename: () -> Unit, onClose: () -> Unit, isSpace: Boolean,
) {
    var menu by remember(tag) { mutableStateOf(false) }
    val actionsLabel = stringResource(R.string.layout_actions_button)
    Box(Modifier.padding(start = if (isSpace) 0.dp else 24.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag).semantics { selected = current }
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onSelect,
                onLongClickLabel = actionsLabel, onLongClick = { menu = true })
            .padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuIcon(icon)
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            if (current) Text(stringResource(R.string.menu_current), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
            ActionMenuItem(stringResource(if (isSpace) R.string.layout_rename_space_button else R.string.layout_rename_tab_button),
                R.drawable.ic_square_pen, onClick = { menu = false; onRename() })
            ActionMenuItem(stringResource(if (isSpace) R.string.layout_close_space_button else R.string.layout_close_tab_button),
                R.drawable.ic_x, destructive = true, onClick = { menu = false; onClose() })
        }
    }
}

@Composable
internal fun HostSheetHeading(title: String) {
    Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun HostSheetAction(
    title: String, @DrawableRes icon: Int, detail: String, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(24.dp), tint = color.copy(alpha = if (enabled) 1f else .38f))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color.copy(alpha = if (enabled) 1f else .38f))
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
        }
    }
}
