package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView

/** Test tag of the Terminal/Chat toggle, at the end of both screens' title row. */
const val AGENT_VIEW_TOGGLE = "agent_view_toggle"

/** Switches an open Agent between its Terminal and its Chat; [current] is the one showing. */
@Composable
fun AgentViewToggle(current: AgentView, onView: (AgentView) -> Unit) {
    val views = listOf(AgentView.TERMINAL to R.string.agent_view_terminal, AgentView.CHAT to R.string.agent_view_chat)
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(end = 8.dp).border(1.dp, colors.outline).selectableGroup().testTag(AGENT_VIEW_TOGGLE)) {
        views.forEachIndexed { index, (view, label) ->
            if (index > 0) Box(Modifier.width(1.dp).height(32.dp).background(colors.outline))
            val selected = view == current
            Box(
                Modifier.width(60.dp).height(32.dp)
                    .background(if (selected) colors.surfaceContainerHighest else colors.surface)
                    .selectable(selected, role = Role.RadioButton, onClick = { if (!selected) onView(view) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), color = if (selected) colors.primary else colors.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(colors.primary))
            }
        }
    }
}
