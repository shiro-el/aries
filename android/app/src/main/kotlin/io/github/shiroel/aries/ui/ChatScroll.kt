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
internal fun rememberChatScrollState(chat: ChatModel, entries: List<ChatEntry>, callKeys: List<String>): ChatScrollState {
    val context = LocalContext.current
    val store = remember(chat, context) { ChatReadingStore(context, chat.host.id, chat.terminal.address) }
    val saved = remember(store) { store.load() }
    val state = remember(chat) { ChatScrollState(saved?.following ?: true) }
    val currentEntries = rememberUpdatedState(entries)
    val currentCalls = rememberUpdatedState(callKeys)
    LaunchedEffect(state) {
        var previousEntries: List<ChatEntry>? = null
        var previousCalls = emptyList<String>()
        var pending = emptySet<String>()
        var lastSaved = saved
        var request = state.latestRequest
        snapshotFlow {
            // Read layout as well as data: scrolling changes the follow mode;
            // a streaming update can change height without adding any rows.
            Triple(
                currentEntries.value to currentCalls.value,
                state.list.layoutInfo,
                state.latestRequest,
            )
        }.collect { (data, _, latestRequest) ->
            val (rows, calls) = data
            val transcript = rows.map { "transcript:${it.id}" }
            val keys = transcript + calls
            if (chat.snapshot == null || keys.isEmpty()) return@collect
            val changed = previousEntries != rows || previousCalls != calls
            val latest = request != latestRequest
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
                if (state.following || latest) {
                    state.following = true
                    state.list.scrollToLatest(keys.lastIndex)
                }
                previousEntries = rows
                previousCalls = calls
                request = latestRequest
            } else {
                state.following = !state.list.canScrollForward
            }
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

private suspend fun LazyListState.scrollToLatest(lastIndex: Int) {
    scrollToItem(lastIndex)
    // scrollToItem aligns the top of an oversized final row. Align its bottom
    // too, so a long streaming response really stays at its newest text.
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull() ?: return
    scrollBy((last.offset + last.size - layout.viewportEndOffset).coerceAtLeast(0).toFloat())
}
