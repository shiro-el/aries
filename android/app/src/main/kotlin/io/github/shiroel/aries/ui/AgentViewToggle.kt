package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView

/** Test tag shared by the Agent view switch and Settings default-view selector. */
const val AGENT_VIEW_TOGGLE = "agent_view_toggle"

/** Explicit default-view selection in Settings; open Agents use [AgentViewSwitch]. */
@Composable
fun AgentViewToggle(current: AgentView, onView: (AgentView) -> Unit) {
    ConsoleSegments(
        options = listOf(AgentView.TERMINAL, AgentView.CHAT), selected = current, onSelect = onView,
        modifier = Modifier.fillMaxWidth().testTag(AGENT_VIEW_TOGGLE),
        height = 48.dp,
        textStyle = MaterialTheme.typography.bodyMedium,
        inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { view ->
        stringResource(if (view == AgentView.TERMINAL) R.string.agent_view_terminal else LocalLayoutScreens.current.chatViewLabel)
    }
}
