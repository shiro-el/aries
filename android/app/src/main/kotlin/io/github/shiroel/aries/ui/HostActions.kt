package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.shiroel.aries.R

@Composable
internal fun rememberLayoutEditor(browser: HostBrowser): LayoutEditor {
    val scope = rememberCoroutineScope()
    return remember(browser) { browser.layoutEditor ?: LayoutEditor(browser, scope).also { browser.layoutEditor = it } }
}

private enum class HostMenuPage { ACTIONS, CONTEXT, EDIT }

/** Frequent actions stay at the root; layout maintenance has its own short page. */
@Composable
internal fun HostActions(browser: HostBrowser, onNewTerminal: (() -> Unit)? = null) {
    val startTask = LocalStartTask.current
    val editor = rememberLayoutEditor(browser)
    var menu by remember(browser) { mutableStateOf(false) }
    var page by remember(browser) { mutableStateOf(HostMenuPage.ACTIONS) }
    val structure = browser.structure
    val enabled = browser.link?.state == LinkState.Up && !editor.busy
    val space = browser.openSpace
    val tab = browser.tab
    val switchLabel = stringResource(R.string.menu_switch_context)
    WorktreeControls(browser)
    Box {
        ActionIcon(R.drawable.ic_ellipsis_vertical, R.string.hosts_actions_label,
            { page = HostMenuPage.ACTIONS; menu = true }, enabled = structure != null && !browser.closed)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (page != HostMenuPage.ACTIONS) {
                ActionMenuItem(stringResource(R.string.back_button), R.drawable.ic_arrow_left,
                    modifier = Modifier.testTag("host-menu-back"), onClick = { page = HostMenuPage.ACTIONS })
                MenuDivider()
            }
            when (page) {
                HostMenuPage.CONTEXT -> structure?.spaces.orEmpty().forEachIndexed { index, target ->
                    if (index > 0) MenuDivider()
                    val selectedSpace = target.id == space?.id
                    ActionMenuItem(target.name, R.drawable.ic_folder,
                        detail = if (selectedSpace) stringResource(R.string.menu_current) else null,
                        modifier = Modifier.testTag("host-context-space:${target.id}").semantics { selected = selectedSpace },
                        onClick = { browser.openSpace(target.id); menu = false })
                    target.tabs.forEach { targetTab ->
                        val selectedTab = selectedSpace && targetTab.id == tab?.id
                        ActionMenuItem(targetTab.name, R.drawable.ic_panels,
                            detail = if (selectedTab) stringResource(R.string.menu_current) else null,
                            modifier = Modifier.testTag("host-context-tab:${target.id}:${targetTab.id}").semantics { selected = selectedTab },
                            onClick = { browser.openSpace(target.id); browser.selectTab(targetTab); menu = false })
                    }
                }
                HostMenuPage.ACTIONS -> {
                    startTask?.let { start ->
                        ActionMenuItem(stringResource(R.string.new_task_title), R.drawable.ic_plus,
                            enabled = enabled, onClick = { menu = false; start(null) })
                    }

                    onNewTerminal?.let { launch ->
                        ActionMenuItem(stringResource(R.string.launch_open_button), R.drawable.ic_terminal_view,
                            enabled = enabled, onClick = { menu = false; launch() })
                    }
                    if (!structure?.spaces.isNullOrEmpty()) ActionMenuItem(
                        stringResource(R.string.menu_switch), R.drawable.ic_panels,
                        detail = space?.let { listOfNotNull(it.name, tab?.name).joinToString(" · ") },
                        submenu = true, modifier = Modifier.testTag("host-layout-context").semantics { contentDescription = switchLabel },
                        onClick = { page = HostMenuPage.CONTEXT },
                    )
                    if (onNewTerminal != null || !structure?.spaces.isNullOrEmpty()) MenuDivider()
                    ActionMenuItem(stringResource(R.string.layout_new_space_button), R.drawable.ic_folder,
                        enabled = enabled, onClick = {
                            menu = false
                            if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.NEW_SPACE)
                        })
                    if (space != null) {
                        ActionMenuItem(stringResource(R.string.layout_new_tab_button), R.drawable.ic_plus,
                            enabled = enabled, onClick = {
                                menu = false
                                if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.NEW_TAB, space.id, folder = space.folder.orEmpty())
                            })
                        MenuDivider()
                        ActionMenuItem(stringResource(R.string.layout_actions_button), R.drawable.ic_square_pen,
                            submenu = true, modifier = Modifier.testTag("host-edit-actions"),
                            onClick = { page = HostMenuPage.EDIT })
                    }
                }
                HostMenuPage.EDIT -> {
                    if (space != null) ActionMenuItem(stringResource(R.string.layout_rename_space_button), R.drawable.ic_square_pen,
                        enabled = enabled, onClick = {
                            menu = false
                            if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.RENAME_SPACE, space.id, if (space.named) space.name else "")
                        })
                    if (tab != null) ActionMenuItem(stringResource(R.string.layout_rename_tab_button), R.drawable.ic_square_pen,
                        enabled = enabled, onClick = {
                            menu = false
                            if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.RENAME_TAB, tab.id, if (tab.named) tab.name else "")
                        })
                    if (space != null || tab != null) MenuDivider()
                    if (tab != null) ActionMenuItem(stringResource(R.string.layout_close_tab_button), R.drawable.ic_x,
                        destructive = true, enabled = enabled, onClick = {
                            menu = false
                            if (editor.requireAvailable()) editor.ending = LayoutEndTarget.Tab(tab.id)
                        })
                    if (space != null) ActionMenuItem(stringResource(R.string.layout_close_space_button), R.drawable.ic_x,
                        destructive = true, enabled = enabled, onClick = {
                            menu = false
                            if (editor.requireAvailable()) editor.ending = LayoutEndTarget.Space(space.id)
                        })
                }
            }
        }
    }
}
