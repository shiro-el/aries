package io.github.shiroel.aries.ui

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView

/** Test tag of the Terminal/Chat toggle, at the end of both screens' title row. */
const val AGENT_VIEW_TOGGLE = "agent_view_toggle"

/** Switches an open Agent between its Terminal and its Chat; [current] is the one showing. */
@Composable
fun AgentViewToggle(current: AgentView, onView: (AgentView) -> Unit) {
    val views = listOf(AgentView.TERMINAL to R.string.agent_view_terminal, AgentView.CHAT to R.string.agent_view_chat)
    SingleChoiceSegmentedButtonRow(Modifier.testTag(AGENT_VIEW_TOGGLE)) {
        views.forEachIndexed { i, (view, label) ->
            SegmentedButton(
                selected = view == current,
                onClick = { if (view != current) onView(view) },
                shape = SegmentedButtonDefaults.itemShape(i, views.size),
                icon = {},
            ) { Text(stringResource(label)) }
        }
    }
}
