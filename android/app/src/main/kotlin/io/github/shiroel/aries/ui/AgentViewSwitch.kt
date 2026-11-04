package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView

/** A single header action showing the view that will open when tapped. */
@Composable
internal fun AgentViewSwitch(current: AgentView, onView: (AgentView) -> Unit) {
    val target = if (current == AgentView.CHAT) AgentView.TERMINAL else AgentView.CHAT
    IconButton(onClick = { onView(target) }, modifier = Modifier.size(48.dp).testTag(AGENT_VIEW_TOGGLE)) {
        Icon(
            painterResource(if (target == AgentView.TERMINAL) R.drawable.ic_terminal_view else R.drawable.ic_chat_view),
            contentDescription = stringResource(if (target == AgentView.TERMINAL) R.string.agent_switch_terminal_label else R.string.agent_switch_chat_label),
            modifier = Modifier.size(20.dp),
        )
    }
}
