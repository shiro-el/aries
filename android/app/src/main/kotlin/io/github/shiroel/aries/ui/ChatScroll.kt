package io.github.shiroel.aries.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import io.github.shiroel.aries.core.ChatEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The viewport is independent of the Agent's `herdr.seen` turn. */
internal class ChatScrollState(following: Boolean) {
    val list = LazyListState()
    var following by mutableStateOf(following)
    var unreadCount by mutableIntStateOf(0)
    var latestRequest by mutableIntStateOf(0)
        private set

    fun latest() { latestRequest++ }

    /** Apply before changing a tool's height so LazyColumn keeps the same reading anchor. */
    fun readTool() {
        following = false
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
    }
}

/** Keep the reading position relative to the bottom as the IME resizes Chat. */
@Composable
internal fun Modifier.chatImeAnchor(list: LazyListState): Modifier {
    val ime = WindowInsets.ime
    return this.then(remember(list, ime) {
        var previousIme: Int? = null
        Modifier.layout { measurable, constraints ->
            val bottom = ime.getBottom(this)
            val imeChanged = previousIme != null && previousIme != bottom
            previousIme = bottom
            if (imeChanged && constraints.hasBoundedHeight) {
                // Request the offset before LazyColumn measures this animation
                // frame. A post-layout effect would show the old position for a
                // frame, then jump. Do not observe scroll state from this layout.
                Snapshot.withoutReadObservation {
                    val layout = list.layoutInfo
                    val delta = layout.viewportSize.height - constraints.maxHeight
                    if (delta != 0 && layout.visibleItemsInfo.isNotEmpty()) {
                        list.requestScrollToItem(
                            list.firstVisibleItemIndex,
                            list.firstVisibleItemScrollOffset + delta,
                        )
                    }
                }
            }
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    })
}

@Composable
internal fun rememberChatScrollState(
    chat: ChatModel,
    entries: List<ChatEntry>,
    callKeys: List<String>,
    markdown: Map<String, ChatMarkdownDocument>,
    followEnabled: Boolean = true,
): ChatScrollState {
    val session = chat.readingSession
    val saved = remember(session) { session.position }
    val state = remember(session) { ChatScrollState(saved?.following ?: true) }
    val content = rememberUpdatedState(ChatScrollContent(entries, callKeys, markdown, followEnabled))
    LaunchedEffect(state) {
        var previousEntries: List<ChatEntry>? = null
        var previousCalls = emptyList<String>()
        var previousItemKeys = emptyList<String>()
        var lastViewport: Pair<Any?, Int>? = null
        var readingAnchor: Pair<String, Int>? = null
        var pending = emptySet<String>()
        var request = state.latestRequest
        var disclosures = chat.expanded to chat.rawInputsExpanded
        snapshotFlow {
            // Read layout as well as data: scrolling changes the follow mode;
            // a streaming update can change height without adding any rows.
            Triple(
                content.value,
                state.list.layoutInfo,
                Triple(state.latestRequest, chat.expanded, chat.rawInputsExpanded),
            )
        }.collect { (data, _, controls) ->
            val (latestRequest, expanded, rawInputsExpanded) = controls
            val disclosureChanged = disclosures != (expanded to rawInputsExpanded)
            disclosures = expanded to rawInputsExpanded
            val (rows, calls, documents, followEnabled) = data
            val transcript = rows.map { "transcript:${it.id}" }
            val keys = buildList {
                rows.forEach { entry ->
                    add("transcript:${entry.id}")
                    if (entry.body.markdownSource() != null) {
                        // Mirror chatMarkdownItems: a header, lazy blocks, then a footer.
                        documents[entry.id]?.blocks?.indices?.forEach { add("transcript:${entry.id}:markdown:$it") }
                        add("transcript:${entry.id}:footer")
                    }
                }
                addAll(calls)
            }
            if (chat.snapshot == null || keys.isEmpty()) return@collect
            fun viewport(): Pair<Any?, Int> = state.list.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == state.list.firstVisibleItemIndex }?.key to state.list.firstVisibleItemScrollOffset
            val currentViewport = viewport()
            // Stable keys distinguish a deliberate scroll from a history
            // prepend or an asynchronous Markdown remeasure.
            if (previousEntries != null && lastViewport != currentViewport) {
                state.following = !state.list.canScrollForward
            }
            // A disclosure is a reading action, not new Agent output. Its next
            // measure must not trigger scrollToLatest and move the tapped header.
            if (!followEnabled || disclosureChanged) state.following = false
            lastViewport = currentViewport
            val ready = rows.all { entry ->
                val text = entry.body.markdownSource()
                text == null || documents[entry.id]?.matches(entry.body) == true
            }
            // Never replace a saved block anchor with an incomplete parse.
            if (!ready) return@collect
            val changed = previousEntries != rows || previousCalls != calls || previousItemKeys != keys
            val latest = request != latestRequest
            // Keep a tool's expand/collapse control reachable on arrival.
            val alignBottom = calls.isNotEmpty() || rows.lastOrNull()?.body !is io.github.shiroel.aries.core.ChatEntryBody.Tool
            if (changed || latest) {
                val previous = previousEntries
                if (previous != null) {
                    pending = (pending + addedTail(previous.map { "transcript:${it.id}" }, transcript) +
                        addedTail(previousCalls, calls)).intersect(keys.toSet())
                    if (!state.following && !latest && rows.indexOfFirst { it.id == previous.firstOrNull()?.id } > 0) {
                        // A prepend can move the turn's read-aloud control to an
                        // older entry and collapse the old first header. Keep
                        // the measured body block in place, rather than that header.
                        readingAnchor?.let { (key, offset) ->
                            val index = keys.indexOf(key)
                            if (index >= 0) state.list.scrollToItem(index, -offset)
                        }
                    }
                } else if (saved != null) {
                    pending = (saved.pending + after(saved.transcriptTail, transcript) + after(saved.callTail, calls))
                        .intersect(keys.toSet())
                    // Restore even when the user left at the end: rows may
                    // have arrived while this screen was covered. Follow again only
                    // if that saved viewport still reaches the current end.
                    val anchorIndex = keys.indexOf(saved.anchor)
                    if (followEnabled) {
                        state.list.scrollToItem(
                            if (anchorIndex >= 0) anchorIndex else saved.index.coerceIn(keys.indices),
                            if (anchorIndex >= 0) saved.offset else 0,
                        )
                        state.following = !state.list.canScrollForward
                    }
                }
                if (followEnabled && (latest || (state.following && (previous != null || saved == null)))) {
                    // A streamed edit keeps the same tail key. Let its natural
                    // measure run before checking whether its height moved the
                    // bottom; forcing a jump here also measures the old height.
                    val tailMoved = previousItemKeys.lastOrNull() != keys.lastOrNull()
                    if (latest || previous == null || tailMoved || state.list.canScrollForward) {
                        try {
                            state.list.scrollToLatest(keys.lastIndex, alignBottom || latest, animate = latest)
                        } catch (e: CancellationException) {
                            // A user drag may interrupt the animation. Keep observing
                            // the viewport so a later Latest tap still works.
                            currentCoroutineContext().ensureActive()
                        }
                        state.following = !state.list.canScrollForward
                    }
                }
                previousEntries = rows
                previousCalls = calls
                previousItemKeys = keys
                request = latestRequest
            } else if (followEnabled && state.following && state.list.canScrollForward) {
                state.list.scrollToLatest(keys.lastIndex, alignBottom)
                state.following = !state.list.canScrollForward
            }
            lastViewport = viewport()
            if (state.following) pending = emptySet()
            state.unreadCount = pending.size

            // A data change can precede LazyColumn's next measure. Save the
            // actual measured key, never an old index into the new row list.
            val layout = state.list.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull { it.index == state.list.firstVisibleItemIndex }
            val anchor = first?.key as? String
            if (layout.totalItemsCount == keys.size && anchor in keys) {
                val visible = layout.visibleItemsInfo.firstOrNull {
                    it.size > 0 && it.offset + it.size > layout.viewportStartOffset
                }
                val body = visible?.let { item ->
                    layout.visibleItemsInfo.firstOrNull {
                        it.size > 0 && (it.key as? String)?.startsWith("${item.key}:markdown:") == true
                    } ?: item
                }
                readingAnchor = (body?.key as? String)?.let { it to body.offset }
                val position = ChatReadingPosition(
                    anchor!!, keys.indexOf(anchor), state.list.firstVisibleItemScrollOffset,
                    state.following, pending, transcript.lastOrNull(), calls.lastOrNull(),
                )
                session.position = position
            }
        }
    }
    return state
}

private data class ChatScrollContent(
    val entries: List<ChatEntry>,
    val calls: List<String>,
    val markdown: Map<String, ChatMarkdownDocument>,
    val followEnabled: Boolean,
)

private fun after(tail: String?, keys: List<String>): List<String> {
    if (tail == null) return keys
    val index = keys.indexOf(tail)
    return if (index < 0) emptyList() else keys.drop(index + 1)
}

/** Owned by one Chat visit, retained by the ViewModel across recreation and covered screens. */
internal class ChatReadingSession {
    var position: ChatReadingPosition? = null
}

internal data class ChatReadingPosition(
    val anchor: String,
    val index: Int,
    val offset: Int,
    val following: Boolean,
    val pending: Set<String>,
    val transcriptTail: String?,
    val callTail: String?,
)

/** Prepending older history and replacing a conversation are not new tail rows. */
private fun addedTail(previous: List<String>, current: List<String>): List<String> {
    if (previous.isEmpty()) return current
    val known = previous.toSet()
    val lastKnown = current.indexOfLast { it in known }
    return if (lastKnown < 0) emptyList() else current.drop(lastKnown + 1).filterNot { it in known }
}

private suspend fun LazyListState.scrollToLatest(lastIndex: Int, alignBottom: Boolean, animate: Boolean = false) {
    if (animate) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    if (!alignBottom) return
    // A tall question/Call is one lazy item: reaching its heading is not
    // reaching the end. Include the scrollable clearance below its choices.
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull() ?: return
    val distance = (last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset)
        .coerceAtLeast(0).toFloat()
    if (animate) animateScrollBy(distance) else scrollBy(distance)
}
