package io.github.shiroel.aries.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.SavedLaunch

/** Shared by Host and Agent-list creation; independent of navigation restoration. */
internal class CreationHostHistory(private val preferences: SharedPreferences) {
    var hostId by mutableStateOf(preferences.getString("host", null))
        private set
    fun used(id: String) {
        hostId = id
        preferences.edit().putString("host", id).apply()
    }
}

@Composable
internal fun rememberCreationHostHistory(): CreationHostHistory {
    val context = LocalContext.current.applicationContext
    return remember(context) { CreationHostHistory(context.getSharedPreferences("creation", Context.MODE_PRIVATE)) }
}

internal val LocalCreationHostHistory = compositionLocalOf<CreationHostHistory?> { null }

/** Both headers use this button, sheet, task entry and saved-launch section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateActions(
    buttonEnabled: Boolean,
    creationEnabled: Boolean,
    savedLaunches: List<SavedLaunch>,
    onNewTask: (() -> Unit)?,
    onRunSaved: (SavedLaunch) -> Unit,
    sheetTag: String,
    onOpen: () -> Unit = {},
    dismissWhenDisabled: Boolean = false,
    heading: @Composable () -> Unit = {},
    extraActions: LazyListScope.(dismiss: () -> Unit) -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(buttonEnabled, creationEnabled) {
        if (!buttonEnabled || (dismissWhenDisabled && !creationEnabled)) visible = false
    }
    ActionIcon(R.drawable.ic_plus, R.string.host_create_title, { onOpen(); visible = true }, enabled = buttonEnabled)
    if (visible) ModalBottomSheet(onDismissRequest = { visible = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag(sheetTag), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                HostSheetHeading(stringResource(R.string.host_create_title))
                heading()
            }
            onNewTask?.let { start -> item {
                HostSheetAction(stringResource(R.string.new_task_title), R.drawable.ic_plus,
                    stringResource(R.string.host_create_task_detail), creationEnabled) { visible = false; start() }
            } }
            extraActions { visible = false }
            if (savedLaunches.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    HostSheetHeading(stringResource(R.string.launch_saved))
                }
                items(savedLaunches, key = { "saved:${it.id}" }) { saved ->
                    HostSheetAction(saved.name, R.drawable.ic_terminal_view,
                        saved.request.command.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.host_create_saved_detail), creationEnabled,
                        Modifier.testTag("saved_run:${saved.id}")) { visible = false; onRunSaved(saved) }
                }
            }
        }
    }
}
