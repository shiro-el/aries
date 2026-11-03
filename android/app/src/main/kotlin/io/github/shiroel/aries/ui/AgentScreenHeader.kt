package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.TerminalInfo

/** One layout for both views, including ordinary Terminals that have no Chat. */
@Composable
internal fun AgentScreenHeader(
    terminal: TerminalInfo,
    hostName: String?,
    onBack: () -> Unit,
    current: AgentView,
    onView: ((AgentView) -> Unit)?,
    title: String = displayName(terminal),
    link: HostLink? = null,
    onTerminalUpdate: (TerminalInfo) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val hostPrefix = hostName?.let { stringResource(R.string.console_agent_host_prefix, it) }
    ConsoleTopBar(
        title = title, onMenu = {}, onBack = onBack, showMark = false, inbox = {},
        titleContent = {
            Text(
                buildAnnotatedString {
                    hostPrefix?.let {
                        withStyle(SpanStyle(color = colors.onSurfaceVariant, fontSize = 12.sp)) {
                            append(it)
                        }
                    }
                    append(title)
                },
                Modifier.weight(1f).padding(end = 8.dp), fontFamily = FontFamily.Monospace,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        actions = { onView?.let { AgentViewToggle(current, it) } },
    )
    Row(
        Modifier.fillMaxWidth().testTag("agent-meta").padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        terminal.agentStatus?.let { status ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentStatusMark(status)
                Text(stringResource(agentStatusLabel(status)), color = statusColor(status), fontSize = 12.sp, maxLines = 1)
            }
        }
        Text(terminal.cwd ?: stringResource(R.string.console_agent_unknown_folder), Modifier.weight(1f),
            color = colors.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // TerminalInfo exposes a turn counter, not a timestamp. Do not turn it into a fabricated age.
        val ageLabel = stringResource(R.string.console_agent_unknown_age)
        Text(stringResource(R.string.console_agent_missing_value), Modifier.semantics { contentDescription = ageLabel },
            color = colors.onSurfaceVariant, fontSize = 12.sp)
    }
    WatchAgentToggle(terminal, link, onTerminalUpdate)
    HorizontalDivider()
}
