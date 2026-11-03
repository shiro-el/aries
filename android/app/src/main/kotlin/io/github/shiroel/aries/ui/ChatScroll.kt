package io.github.shiroel.aries.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.platform.LocalContext
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import org.json.JSONArray
import org.json.JSONObject

/** The viewport is independent of the Agent's `herdr.seen` turn. */
internal class ChatScrollState(following: Boolean) {
    val list = LazyListState()
    var following by mutableStateOf(following)
    var unreadCount by mutableIntStateOf(0)
    var latestRequest by mutableIntStateOf(0)
        private set

    fun latest() { latestRequest++ }
}

@Composable
internal fun rememberChatScrollState(
    chat: ChatModel,
    entries: List<ChatEntry>,
    callKeys: List<String>,
    markdown: Map<String, ChatMarkdownDocument>,
): ChatScrollState {
    val context = LocalContext.current
    val store = remember(chat, context) { ChatReadingStore(context, chat.host.id, chat.terminal.address) }
    val saved = remember(store) { store.load() }
    val state = remember(chat) { ChatScrollState(saved?.following ?: true) }
    val content = rememberUpdatedState(ChatScrollContent(entries, callKeys, markdown))
    LaunchedEffect(state) {
        var previousEntries: List<ChatEntry>? = null
        var previousCalls = emptyList<String>()
        var previousItemKeys = emptyList<String>()
        var lastViewport: Pair<Any?, Int>? = null
        var pending = emptySet<String>()
        var lastSaved = saved
        var request = state.latestRequest
        snapshotFlow {
            // Read layout as well as data: scrolling changes the follow mode;
            // a streaming update can change height without adding any rows.
            Triple(
                content.value,
                state.list.layoutInfo,
                state.latestRequest,
            )
        }.collect { (data, _, latestRequest) ->
            val (rows, calls, documents) = data
            val transcript = rows.map { "transcript:${it.id}" }
            val keys = buildList {
                rows.forEach { entry ->
                    add("transcript:${entry.id}")
                    if (entry.body is ChatEntryBody.User || entry.body is ChatEntryBody.Agent) {
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
            lastViewport = currentViewport
            val ready = rows.all { entry ->
                val text = when (val body = entry.body) {
                    is ChatEntryBody.User -> body.text
                    is ChatEntryBody.Agent -> body.text
                    else -> null
                }
                text == null || documents[entry.id]?.source == text
            }
            // Never replace a saved block anchor with an incomplete parse.
            if (!ready) return@collect
            val changed = previousEntries != rows || previousCalls != calls || previousItemKeys != keys
            val latest = request != latestRequest
            val alignBottom = calls.isEmpty() &&
                (rows.lastOrNull()?.body is ChatEntryBody.Agent || rows.lastOrNull()?.body is ChatEntryBody.User)
            if (changed || latest) {
                val previous = previousEntries
                if (previous != null) {
                    pending = (pending + addedTail(previous.map { "transcript:${it.id}" }, transcript) +
                        addedTail(previousCalls, calls)).intersect(keys.toSet())
                } else if (saved != null) {
                    pending = (saved.pending + after(saved.transcriptTail, transcript) + after(saved.callTail, calls))
                        .intersect(keys.toSet())
                    // Restore even when the user left at the end: rows may
                    // have arrived while Chat was closed. Follow again only
                    // if that saved viewport still reaches the current end.
                    val anchorIndex = keys.indexOf(saved.anchor)
                    state.list.scrollToItem(
                        if (anchorIndex >= 0) anchorIndex else saved.index.coerceIn(keys.indices),
                        if (anchorIndex >= 0) saved.offset else 0,
                    )
                    state.following = !state.list.canScrollForward
                }
                if (latest || (state.following && (previous != null || saved == null))) {
                    state.list.scrollToLatest(keys.lastIndex, alignBottom)
                    state.following = !state.list.canScrollForward
                }
                previousEntries = rows
                previousCalls = calls
                previousItemKeys = keys
                request = latestRequest
            } else if (state.following && state.list.canScrollForward) {
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
                val position = ChatReadingPosition(
                    anchor!!, keys.indexOf(anchor), state.list.firstVisibleItemScrollOffset,
                    state.following, pending, transcript.lastOrNull(), calls.lastOrNull(),
                )
                if (position != lastSaved) {
                    store.save(position)
                    lastSaved = position
                }
            }
        }
    }
    return state
}

private data class ChatScrollContent(
    val entries: List<ChatEntry>,
    val calls: List<String>,
    val markdown: Map<String, ChatMarkdownDocument>,
)

private fun after(tail: String?, keys: List<String>): List<String> {
    if (tail == null) return keys
    val index = keys.indexOf(tail)
    return if (index < 0) emptyList() else keys.drop(index + 1)
}

private data class ChatReadingPosition(
    val anchor: String,
    val index: Int,
    val offset: Int,
    val following: Boolean,
    val pending: Set<String>,
    val transcriptTail: String?,
    val callTail: String?,
)

/** Phone-local IDs and pixel offsets only; no transcript text or credentials. */
private class ChatReadingStore(context: Context, host: String, terminal: String) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences("chat_reading_positions", Context.MODE_PRIVATE)
    private val key = JSONArray(listOf(host, terminal)).toString()

    fun load(): ChatReadingPosition? = runCatching {
        val json = JSONObject(preferences.getString(key, null) ?: return null)
        val pending = json.getJSONArray("pending")
        ChatReadingPosition(
            json.getString("anchor"), json.getInt("index").coerceAtLeast(0),
            json.getInt("offset").coerceAtLeast(0), json.getBoolean("following"),
            (0 until pending.length()).map { pending.getString(it) }.toSet(),
            json.optString("transcriptTail").takeIf { it.isNotEmpty() },
            json.optString("callTail").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    fun save(position: ChatReadingPosition) {
        val json = JSONObject()
            .put("anchor", position.anchor).put("index", position.index).put("offset", position.offset)
            .put("following", position.following).put("pending", JSONArray(position.pending.toList()))
            .put("transcriptTail", position.transcriptTail).put("callTail", position.callTail)
        preferences.edit().putString(key, json.toString()).apply()
    }
}

/** Prepending older history and replacing a conversation are not new tail rows. */
private fun addedTail(previous: List<String>, current: List<String>): List<String> {
    if (previous.isEmpty()) return current
    val known = previous.toSet()
    val lastKnown = current.indexOfLast { it in known }
    return if (lastKnown < 0) emptyList() else current.drop(lastKnown + 1).filterNot { it in known }
}

private suspend fun LazyListState.scrollToLatest(lastIndex: Int, alignBottom: Boolean) {
    scrollToItem(lastIndex)
    // Keep a tall question/Call's heading and first Choices reachable, as in
    // the existing Chat. Text responses instead follow their newest lines.
    if (!alignBottom) return
    // scrollToItem aligns the top of an oversized final row. Align its bottom
    // too, so a long streaming response really stays at its newest text.
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull() ?: return
    scrollBy((last.offset + last.size - layout.viewportEndOffset).coerceAtLeast(0).toFloat())
}
