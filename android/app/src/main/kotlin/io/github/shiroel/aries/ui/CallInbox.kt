package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus

internal class CallInboxAction(val calls: CallsModel, val open: Boolean, val toggle: () -> Unit)

/** Also supplies the inbox to Agent headers when they adopt ConsoleTopBar. */
internal val LocalCallInbox = staticCompositionLocalOf<CallInboxAction?> { null }

@Composable
internal fun ConsoleInboxAction() {
    val action = LocalCallInbox.current ?: return
    val count = action.calls.calls.count { it.answerable }
    if (count == 0) return
    val label = stringResource(R.string.call_inbox_label, count)
    val state = stringResource(if (action.open) R.string.call_inbox_expanded else R.string.call_inbox_collapsed)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("console-inbox")
            .clickable(role = Role.Button, onClick = action.toggle)
            .semantics { contentDescription = label; stateDescription = state; liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(20.dp)) {
            val path = Path().apply {
                moveTo(3f, 11.5f); lineTo(3f, 16f); lineTo(17f, 16f); lineTo(17f, 11.5f)
                moveTo(3f, 11.5f); lineTo(5f, 4f); lineTo(15f, 4f); lineTo(17f, 11.5f)
                moveTo(3f, 11.5f); lineTo(7f, 11.5f); lineTo(8f, 13.5f); lineTo(12f, 13.5f); lineTo(13f, 11.5f); lineTo(17f, 11.5f)
            }
            scale(size.width / 20f, size.height / 20f, pivot = Offset.Zero) {
                drawPath(path, ink, style = Stroke(width = 1.5f))
            }
        }
        if (count > 0) Text(
            count.toString(), Modifier.testTag("console-inbox-count"), fontSize = 12.sp, // i18n-ok: bare Console count digits
            fontWeight = FontWeight.Bold, color = statusColor(AgentStatus.Blocked(null, null)),
        )
    }
}

@Composable
internal fun CallInboxOverlay(model: HostsModel, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.calls_title)
    val close = stringResource(R.string.call_inbox_close)
    val sheet = LocalLayoutTokens.current != tokensFor(AppLayout.CONSOLE)
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().testTag("call-inbox-scrim")
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .45f))
            .semantics { contentDescription = close }
            .clickable(role = Role.Button, onClick = model::closeCallInbox))
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight * .7f)
            .then(if (sheet) Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)) else Modifier)
            .background(if (sheet) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface).testTag("call-inbox-panel")
            .semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AnnouncedText(stringResource(R.string.call_inbox_label, model.calls.calls.count { it.answerable }),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = model::closeCallInbox, modifier = Modifier.testTag("call-inbox-close")) { Text(close) }
            }
            model.calls.banner?.let { entry ->
                CallAttentionBanner(entry, model.callCallerName(entry), onOpen = model::openCallInbox,
                    onDismiss = model.calls::dismissBanner)
            }
            CallInboxPanel(model.calls, model::openCallChat, modifier = Modifier.weight(1f, fill = false), callerName = model::callCallerName,
                onInstallBeacon = model::openHostSettings, onFeedSettings = model::callFeedSettings, openingCall = model.openingCall,
                blockedAgents = model.drawerAgents.count { it.terminal.agentStatus is AgentStatus.Blocked },
                onWaitingAgents = { model.closeCallInbox(); model.openAgents() })
        }
    }
}
