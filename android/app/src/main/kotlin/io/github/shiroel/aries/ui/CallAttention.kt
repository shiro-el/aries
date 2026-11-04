package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CallUrgency

/** Screens declare the Calls they actually present, rather than guessing from the current route.
 * The waiting rules belong to those screens; this owner only prevents duplicate attention.
 */
internal class CallAttentionState(val model: HostsModel) {
    val presentations = mutableStateMapOf<Any, CallPresentation>()
    val dialogs = mutableStateListOf<CallAttentionDialogSurface>()
}

internal class CallAttentionDialogSurface(val close: () -> Unit)

internal data class CallPresentation(val surface: CallAttentionDialogSurface?, val keys: Set<String>)

private val LocalCallAttentionSurface = staticCompositionLocalOf<CallAttentionDialogSurface?> { null }
private val LocalCallAttention = staticCompositionLocalOf<CallAttentionState?> { null }
internal val LocalCallAttentionEnabled = compositionLocalOf { true }

@Composable
internal fun CallAttentionHost(model: HostsModel, content: @Composable () -> Unit) {
    val state = remember(model) { CallAttentionState(model) }
    CompositionLocalProvider(LocalCallAttention provides state, content = content)
}

/** Register only drawn content. Leaving Chat for preview or scrolling a Call offscreen restores the banner. */
@Composable
internal fun Modifier.presentsCalls(keys: Set<String>): Modifier {
    val state = LocalCallAttention.current ?: return this
    val owner = remember { Any() }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(state, owner) { onDispose { state.presentations.remove(owner) } }
    val presented = if (visible) keys else emptySet()
    val surface = LocalCallAttentionSurface.current
    SideEffect { state.presentations[owner] = CallPresentation(surface, presented) }
    return onGloballyPositioned { visible = !it.boundsInWindow().isEmpty }
}

/** Place immediately below the active screen's header. Embedded Agent headers use the same slot. */
@Composable
internal fun CallAttentionSlot(
    compact: Boolean = LocalConfiguration.current.screenHeightDp < 480 || WindowInsets.ime.getBottom(LocalDensity.current) > 0,
    onOpen: ((CallEntry) -> Unit)? = null,
) {
    val state = LocalCallAttention.current ?: return
    val model = state.model
    val surface = LocalCallAttentionSurface.current
    if (surface != state.dialogs.lastOrNull()) return
    if (!LocalCallAttentionEnabled.current || model.callInboxOpen || model.quickOpenVisible) return
    val entry = model.calls.banner ?: return
    if (state.presentations.values.any { it.surface == surface && entry.key in it.keys }) return
    CallAttentionBanner(entry, model.callCallerName(entry), onOpen = { onOpen?.invoke(entry) ?: model.openCallInbox() },
        onDismiss = model.calls::dismissBanner, compact = compact)
}

/** Full-screen tools own attention while their Android window obscures the screen below.
 * Close all obscuring tools before opening the Call, including nested viewers and sibling task forms.
 */
@Composable
internal fun CallAttentionDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    onCloseForCall: () -> Unit = onDismissRequest,
    content: @Composable () -> Unit,
) {
    val state = LocalCallAttention.current
    val latestClose by rememberUpdatedState(onCloseForCall)
    val surface = remember { CallAttentionDialogSurface { latestClose() } }
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        if (state == null) content()
        else {
            DisposableEffect(state, surface) {
                state.dialogs.add(surface)
                onDispose { state.dialogs.remove(surface) }
            }
            CompositionLocalProvider(LocalCallAttentionSurface provides surface, LocalCallAttentionEnabled provides true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        CallAttentionSlot(onOpen = { entry ->
                            state.dialogs.toList().asReversed().forEach { it.close() }
                            state.model.openCallInbox()
                            state.model.calls.openThreadCall(entry)
                            state.model.openCallChat(entry)
                        })
                        Box(Modifier.weight(1f)) { content() }
                    }
                }
            }
        }
    }
}

/** Each fresh arrival owns its haptic; snapshots and recreated screens never replay it. */
@Composable
internal fun CallAttentionEffect(calls: CallsModel) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(calls) {
        calls.attention.collect { entry ->
            val feedback = when (entry.call.urgency) {
                CallUrgency.RECORD -> return@collect
                CallUrgency.CHECK -> HapticFeedbackType.Confirm
                CallUrgency.URGENT -> HapticFeedbackType.LongPress
                CallUrgency.EMERGENCY -> HapticFeedbackType.Reject
            }
            haptic.performHapticFeedback(feedback)
        }
    }
}

/** Persistent, compact attention; opening the inbox never answers the Call. */
@Composable
internal fun CallAttentionBanner(entry: CallEntry, callerName: String?, onOpen: () -> Unit, onDismiss: () -> Unit,
    compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val accent = when (entry.call.urgency) {
        CallUrgency.RECORD, CallUrgency.CHECK -> colors.primary
        CallUrgency.URGENT -> statusColor(io.github.shiroel.aries.core.AgentStatus.Blocked(null, null))
        CallUrgency.EMERGENCY -> colors.error
    }
    val urgency = stringResource(when (entry.call.urgency) {
        CallUrgency.RECORD -> R.string.call_banner_record
        CallUrgency.CHECK -> R.string.call_banner_check
        CallUrgency.URGENT -> R.string.call_banner_urgent
        CallUrgency.EMERGENCY -> R.string.call_banner_emergency
    })
    val caller = stringResource(R.string.call_inbox_caller, callerName ?: stringResource(R.string.call_caller_unknown), entry.host.name)
    val meta = stringResource(R.string.call_banner_meta, urgency, caller)
    val announcement = "$meta. ${entry.call.title}"
    val open = stringResource(R.string.call_banner_open)
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerHigh).testTag("call_banner")
            .clickable(onClickLabel = open, role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = if (compact) 0.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (compact) Text(stringResource(R.string.call_banner_meta, urgency, entry.call.title),
                Modifier.semantics { contentDescription = announcement },
                color = accent, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else {
                Text(meta, color = accent, style = MaterialTheme.typography.labelMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.call.title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.call.body.isNotEmpty()) Text(entry.call.body, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_x), stringResource(R.string.call_banner_dismiss), Modifier.size(20.dp))
        }
    }
    HorizontalDivider(color = accent)
}
