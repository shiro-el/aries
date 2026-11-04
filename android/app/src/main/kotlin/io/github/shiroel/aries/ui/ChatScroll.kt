package io.github.shiroel.aries.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
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
import io.github.shiroel.aries.core.ChatEntryBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The viewport is independent of the Agent's `herdr.seen` turn. */
internal class ChatScrollState(following: Boolean) {
    // Clamp to the current tail on the first measure, even before Markdown is ready.
    val list = LazyListState(if (following) Int.MAX_VALUE else 0)
    var positioned by mutableStateOf(false)
    var following by mutableStateOf(following)
    var unreadCount by mutableIntStateOf(0)
    var latestRequest by mutableIntStateOf(0)
        private set
    var jumping by mutableStateOf(false)
    var dragging by mutableStateOf(false)

    fun latest() { jumping = true; latestRequest++ }

    /** Apply before changing a tool's height so LazyColumn keeps the same reading anchor. */
    fun readTool() {
        jumping = false
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
    auxiliaryKeys: List<String>,
    markdown: Map<String, ChatMarkdownDocument>,
    followEnabled: Boolean = true,
    callKeys: List<String> = emptyList(),
    // One logical unread key per entry; a Thread tool group shares one key.
    messageKeys: List<String> = entries.map { "transcript:${it.id}" },
): ChatScrollState {
    val session = chat.readingSession
    val saved = remember(session) { session.position }
    val state = remember(session) { ChatScrollState(saved?.following ?: true) }
    val content = rememberUpdatedState(ChatScrollContent(entries, auxiliaryKeys, markdown, followEnabled, callKeys, messageKeys))
    LaunchedEffect(state) {
        state.list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    state.dragging = true
                    state.jumping = false
                    state.following = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    state.dragging = false
                    // The final drag frame may already be at the end. A slow
                    // release need not produce another viewport change or fling.
                    state.following = !state.list.canScrollForward
                }
            }
        }
    }
    LaunchedEffect(state) {
        var previousEntries: List<ChatEntry>? = null
        var previousCalls = emptyList<String>()
        var previousTranscript = emptyList<String>()
        var previousItemKeys = emptyList<String>()
        var anchorKeys = emptyList<String>()
        var lastViewport: Pair<Any?, Int>? = null
        var readingAnchor: Pair<String, Int>? = null
        var pending = emptySet<String>()
        var submission = session.latestRequest
        var disclosures = chat.expanded to chat.rawInputsExpanded
        snapshotFlow {
            // Read layout as well as data: scrolling changes the follow mode;
            // a streaming update can change height without adding any rows.
            Triple(
                content.value,
                state.list.layoutInfo,
                Triple(state.latestRequest to session.latestRequest, chat.expanded, chat.rawInputsExpanded) to state.dragging,
            )
        }.collect { (data, _, controls) ->
            if (submission != session.latestRequest) {
                submission = session.latestRequest
                state.latest()
            }
            val (_, expanded, rawInputsExpanded) = controls.first
            val disclosureChanged = disclosures != (expanded to rawInputsExpanded)
            disclosures = expanded to rawInputsExpanded
            val (rows, auxiliary, documents, followEnabled, calls, transcript) = data
            // Overlays suspend placement, not the user's follow intent. Keep the
            // first visit/restore pending too, including automatically opened Tasks sheets.
            if (!followEnabled) {
                lastViewport = null
                return@collect
            }
            val userInput = rows.mapIndexedNotNull { index, entry ->
                transcript.getOrNull(index).takeIf { entry.body is ChatEntryBody.User }
            }.toSet()
            val countable = (transcript + calls).toSet() - userInput
            val keys = buildList {
                rows.forEach { entry ->
                    add("transcript:${entry.id}")
                    if (entry.body.markdownSource() != null) {
                        // Mirror chatMarkdownItems: a header, lazy blocks, then a footer.
                        documents[entry.id]?.blocks?.indices?.forEach { add("transcript:${entry.id}:markdown:$it") }
                        add("transcript:${entry.id}:footer")
                    }
                }
                addAll(auxiliary)
            }
            if (chat.snapshot == null || keys.isEmpty()) return@collect
            fun viewport(): Pair<Any?, Int> = state.list.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == state.list.firstVisibleItemIndex }?.key to state.list.firstVisibleItemScrollOffset
            val currentViewport = viewport()
            // Stable keys distinguish a deliberate scroll from a history
            // prepend or an asynchronous Markdown remeasure.
            if (previousEntries != null && lastViewport != null && lastViewport != currentViewport && !state.jumping && !state.dragging) {
                state.following = !state.list.canScrollForward
            }
            // A disclosure is a reading action, not new Agent output. Its next
            // measure must not trigger scrollToLatest and move the tapped header.
            if (disclosureChanged) {
                state.jumping = false
                state.following = false
            }
            lastViewport = currentViewport
            // Pin the measured body key before the provider measures a prepend,
            // including its unparsed header/footer and the later parsed blocks.
            val previous = previousEntries
            if (anchorKeys != keys && previous != null && !state.following && !state.jumping && !state.list.isScrollInProgress &&
                rows.indexOfFirst { it.id == previous.firstOrNull()?.id } > 0) {
                readingAnchor?.let { (key, offset) ->
                    val index = keys.indexOf(key)
                    if (index >= 0) state.list.requestScrollToItem(index, -offset)
                }
            }
            anchorKeys = keys
            val ready = rows.all { entry ->
                val text = entry.body.markdownSource()
                text == null || documents[entry.id]?.matches(entry.body) == true
            }
            // Never replace a saved block anchor with an incomplete parse.
            if (!ready) return@collect
            val changed = previousEntries != rows || previousCalls != calls || previousItemKeys != keys || previousTranscript != transcript
            val latest = state.jumping
            // Keep a tool's expand/collapse control reachable on arrival.
            val alignBottom = auxiliary.isNotEmpty() || rows.lastOrNull()?.body !is ChatEntryBody.Tool
            if (changed || latest) {
                if (previous != null) {
                    pending = (pending + addedTail(previousTranscript, transcript) +
                        calls.filterNot { it in previousCalls }).intersect(countable)
                } else if (saved != null) {
                    pending = (saved.pending + after(saved.transcriptTail, transcript) + calls.filterNot { it in saved.calls })
                        .intersect(countable)
                    // History readers restore their anchor. Followers return to
                    // the current tail, including output received while covered
                    // and a viewport whose height changed during rotation.
                    val anchorIndex = keys.indexOf(saved.anchor)
                    if (!saved.following) {
                        if (state.observeScroll {
                            state.list.scrollToItem(
                                if (anchorIndex >= 0) anchorIndex else saved.index.coerceIn(keys.indices),
                                if (anchorIndex >= 0) saved.offset else 0,
                            )
                        }) state.following = !state.dragging && !state.list.canScrollForward
                    }
                }
                if (!state.dragging && (latest || state.following)) {
                    // A streamed edit keeps the same tail key. Let its natural
                    // measure run before checking whether its height moved the
                    // bottom; forcing a jump here also measures the old height.
                    val tailMoved = previousItemKeys.lastOrNull() != keys.lastOrNull()
                    if (latest || previous == null || tailMoved || state.list.canScrollForward) {
                        val completed = state.observeScroll {
                            state.list.scrollToLatest(keys.lastIndex, alignBottom || latest, animate = latest)
                        }
                        // Data can advance while the animation is suspended. Keep
                        // the intent until a measured, current tail is reached.
                        state.following = completed && !state.dragging && !state.list.canScrollForward && data == content.value
                        if (state.following) state.jumping = false
                    }
                }
                previousEntries = rows
                previousCalls = calls
                previousTranscript = transcript
                previousItemKeys = keys
            } else if (!state.dragging && state.following && state.list.canScrollForward) {
                val completed = state.observeScroll { state.list.scrollToLatest(keys.lastIndex, alignBottom) }
                state.following = completed && !state.dragging && !state.list.canScrollForward
            }
            state.positioned = true
            lastViewport = viewport()
            val layout = state.list.layoutInfo
            if (state.following) pending = emptySet()
            else if (pending.isNotEmpty() && layout.totalItemsCount == keys.size &&
                layout.visibleItemsInfo.all { keys.getOrNull(it.index) == it.key }) {
                // Reading a message means reaching its end, including a tall
                // body or Call. Rows passed above the viewport are read too.
                val readThrough = layout.visibleItemsInfo.lastOrNull {
                    it.offset + it.size <= layout.viewportEndOffset
                }?.index
                if (readThrough != null) {
                    val indices = keys.withIndex().associate { it.value to it.index }
                    val ends = mutableMapOf<String, Int>()
                    rows.forEachIndexed { index, row ->
                        val key = "transcript:${row.id}"
                        // Empty footer/action rows may never be measured. The
                        // final body block is the message's readable end.
                        val block = documents[row.id]?.blocks?.lastIndex?.takeIf { it >= 0 }
                        val end = if (block != null) "$key:markdown:$block" else key
                        val message = transcript.getOrNull(index)
                        if (message != null && (message !in ends || message in expanded)) {
                            indices[end]?.let { ends[message] = it }
                        }
                    }
                    calls.forEach { call -> indices[call]?.let { ends[call] = it } }
                    pending = pending.filterTo(mutableSetOf()) { (ends[it] ?: Int.MAX_VALUE) > readThrough }
                }
            }
            state.unreadCount = pending.size

            // A data change can precede LazyColumn's next measure. Save the
            // actual measured key, never an old index into the new row list.
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
                    state.following, pending, transcript.lastOrNull(), calls.toSet(),
                )
                session.position = position
            }
        }
    }
    return state
}

/** A user-input mutation cancels this scroll, not the visit's long-lived observer. */
private suspend fun ChatScrollState.observeScroll(scroll: suspend () -> Unit): Boolean = try {
    scroll()
    true
} catch (_: CancellationException) {
    currentCoroutineContext().ensureActive()
    jumping = false
    following = false
    false
}

private data class ChatScrollContent(
    val entries: List<ChatEntry>,
    val auxiliary: List<String>,
    val markdown: Map<String, ChatMarkdownDocument>,
    val followEnabled: Boolean,
    val calls: List<String>,
    val messages: List<String>,
)

private fun after(tail: String?, keys: List<String>): List<String> {
    if (tail == null) return keys
    val index = keys.lastIndexOf(tail)
    return if (index < 0) emptyList() else keys.drop(index + 1)
}

/** Owned by one Chat visit, retained by the ViewModel across recreation and covered screens. */
internal class ChatReadingSession {
    var latestRequest by mutableIntStateOf(0)
    var position: ChatReadingPosition? = null
}

internal data class ChatReadingPosition(
    val anchor: String,
    val index: Int,
    val offset: Int,
    val following: Boolean,
    val pending: Set<String>,
    val transcriptTail: String?,
    val calls: Set<String>,
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
