package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

@Composable
internal fun rememberLayoutEditor(browser: HostBrowser): LayoutEditor {
    val scope = rememberCoroutineScope()
    return remember(browser) { browser.layoutEditor ?: LayoutEditor(browser, scope).also { browser.layoutEditor = it } }
}

/** Host actions share the screen header; choosing context never adds a list level. */
@Composable
internal fun HostActions(browser: HostBrowser, onNewTerminal: (() -> Unit)? = null) {
    val editor = rememberLayoutEditor(browser)
    var menu by remember(browser) { mutableStateOf(false) }
    var contextMenu by remember(browser) { mutableStateOf(false) }
    val structure = browser.structure
    val enabled = browser.link?.state == LinkState.Up && !editor.busy
    val space = browser.openSpace
    val tab = browser.tab
    WorktreeControls(browser)
    Box {
        ActionIcon(R.drawable.ic_ellipsis_vertical, R.string.hosts_actions_label,
            { contextMenu = false; menu = true }, enabled = structure != null && !browser.closed)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (contextMenu) {
                structure?.spaces.orEmpty().forEach { target ->
                    DropdownMenuItem(text = { Text(target.name) }, modifier = Modifier.testTag("host-context-space:${target.id}"), onClick = {
                        browser.openSpace(target.id); menu = false
                    })
                    target.tabs.forEach { targetTab ->
                        DropdownMenuItem(text = { Text(targetTab.name, Modifier.padding(start = 16.dp)) },
                            modifier = Modifier.testTag("host-context-tab:${target.id}:${targetTab.id}"), onClick = {
                                browser.openSpace(target.id); browser.selectTab(targetTab); menu = false
                            })
                    }
                }
            } else {
                onNewTerminal?.let { launch ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.launch_open_button)) }, enabled = enabled,
                        onClick = { menu = false; launch() })
                }
                if (!structure?.spaces.isNullOrEmpty()) DropdownMenuItem(
                    text = { Text(space?.let { listOfNotNull(it.name, tab?.name).joinToString(" · ") }
                        ?: stringResource(R.string.hosts_layout_context_button)) }, modifier = Modifier.testTag("host-layout-context"),
                    onClick = { contextMenu = true },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.layout_new_space_button)) }, enabled = enabled, onClick = {
                    menu = false
                    if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.NEW_SPACE)
                })
                if (space != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_new_tab_button)) }, enabled = enabled, onClick = {
                        menu = false
                        if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.NEW_TAB, space.id, folder = space.folder.orEmpty())
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_rename_space_button)) }, enabled = enabled, onClick = {
                        menu = false
                        if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.RENAME_SPACE, space.id, if (space.named) space.name else "")
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_close_space_button)) }, enabled = enabled, onClick = {
                        menu = false
                        if (editor.requireAvailable()) editor.ending = LayoutEndTarget.Space(space.id)
                    })
                }
                if (tab != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_rename_tab_button)) }, enabled = enabled, onClick = {
                        menu = false
                        if (editor.requireAvailable()) editor.naming = LayoutNameForm(LayoutNameKind.RENAME_TAB, tab.id, if (tab.named) tab.name else "")
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_close_tab_button)) }, enabled = enabled, onClick = {
                        menu = false
                        if (editor.requireAvailable()) editor.ending = LayoutEndTarget.Tab(tab.id)
                    })
                }
            }
        }
    }
}
