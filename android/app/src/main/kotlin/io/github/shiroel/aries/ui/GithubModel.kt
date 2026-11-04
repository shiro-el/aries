package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GithubQuery(val kind: GithubKind, val state: GithubState, val search: String, val page: Int)

/** Owned by the Host link, so an Activity recreation cannot discard unsaved text. */
internal class GithubModel(
    private val scope: CoroutineScope,
    private val finishWrite: (HostConnection, suspend () -> Unit) -> Job = { _, write -> scope.launch { write() } },
) {
    var shown by mutableStateOf(false)
        private set
    var repository by mutableStateOf<GithubRepository?>(null)
    var kind by mutableStateOf(GithubKind.ISSUE)
    var state by mutableStateOf(GithubState.OPEN)
    var input by mutableStateOf("")
    var search by mutableStateOf("")
    var page by mutableStateOf(1)
    var revision by mutableStateOf(0)
    var result by mutableStateOf<GithubPage?>(null)
    var resultQuery: GithubQuery? = null
    var error by mutableStateOf<Int?>(null)
    var loading by mutableStateOf(true)
    var selected by mutableStateOf<GithubItem?>(null)
    var prFile by mutableStateOf<GithubPrFile?>(null)
    private val conversations = mutableMapOf<Pair<GithubRepository, UInt>, GithubConversationModel>()

    fun conversation(repo: GithubRepository, number: UInt): GithubConversationModel =
        conversations.getOrPut(repo to number) { GithubConversationModel(scope, repo, number, finishWrite) }

    fun open() {
        // Resolve the Terminal's current repository for each explicit opening.
        repository = null
        selected = null
        prFile = null
        result = null
        error = null
        shown = true
    }

    fun dismiss() { shown = false }
}

internal class GithubConversationModel(
    private val scope: CoroutineScope,
    private val repo: GithubRepository,
    private val number: UInt,
    private val finishWrite: (HostConnection, suspend () -> Unit) -> Job,
) {
    var tab by mutableStateOf(GithubPrTab.Conversation)
    var resourceRefresh: Int? = null
    var detail by mutableStateOf<GithubDetail?>(null)
    var readError by mutableStateOf<Int?>(null)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<Int?>(null)
    var success by mutableStateOf<Int?>(null)
    var preview by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var revision by mutableStateOf(0)
        private set
    var draft by mutableStateOf("")
        private set
    var draftReady by mutableStateOf(false)
        private set
    var draftError by mutableStateOf<Int?>(null)
        private set
    private var storage: HostConnection? = null
    private var loading: Job? = null
    private var dirty = false
    private var generation = 0
    private val disk = Mutex()
    private val saves = Channel<Unit>(Channel.CONFLATED)

    private var saving: Job? = null

    private fun scheduleSave() {
        saves.trySend(Unit)
        if (saving?.isActive == true) return
        saving = scope.launch {
            do {
                // Wait for a quiet interval, then serialize writes even if new edits arrive
                // while the blocking core call is already running on IO.
                while (withTimeoutOrNull(300) { saves.receive(); true } == true) Unit
                save()
            } while (saves.tryReceive().isSuccess)
        }
    }

    fun attach(connection: HostConnection?) {
        // Draft storage is local to the phone and works independently of SSH liveness.
        if (storage == null) storage = connection
        if (!draftReady && draftError == null) loadDraft()
        if (dirty) scheduleSave()
    }

    private fun loadDraft() {
        val conn = storage ?: return
        if (draftReady || loading?.isActive == true) return
        draftError = null
        loading = scope.launch {
            try {
                val text = disk.withLock { withContext(Dispatchers.IO) { conn.githubLoadDraft(repo, number) } }
                if (!dirty) draft = text
                draftReady = true
            } catch (e: CancellationException) { throw e
            } catch (e: CoreException) { draftError = R.string.github_draft_read_failed }
        }
    }

    fun retryDraft() {
        if (!draftReady) loadDraft() else scheduleSave()
    }

    fun changeDraft(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 65536) {
            draftError = R.string.github_draft_limit
            return
        }
        draft = text
        draftError = null
        dirty = true
        generation++
        scheduleSave()
    }

    private suspend fun save(allowBusy: Boolean = false) {
        val conn = storage ?: return
        if (!dirty || busy && !allowBusy) return
        val version = generation
        val text = draft
        try {
            disk.withLock {
                if (busy && !allowBusy) return
                withContext(Dispatchers.IO) { conn.githubSaveDraft(repo, number, text) }
            }
            if (generation == version) {
                dirty = false
                if (draftError == R.string.github_draft_failed) draftError = null
            }
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) {
            if (generation == version) draftError = R.string.github_draft_failed
        }
    }

    /** Submitted writes finish on their original line and are never replayed on reconnect. */
    fun write(connection: HostConnection?, comment: Boolean) {
        val conn = connection ?: return
        val current = detail ?: return
        if (busy || comment && (!draftReady || draft.isBlank())) return
        val submitted = draft
        busy = true
        error = null
        success = null
        finishWrite(conn) {
            try {
                if (comment) {
                    conn.githubComment(repo, current.item.kind, number, submitted)
                    draft = ""
                    generation++
                    dirty = true
                    draftError = null
                    preview = false
                    save(allowBusy = true)
                    success = R.string.github_sent
                } else {
                    conn.githubSetIssueClosed(repo, number, current.item.state == "open")
                    success = R.string.github_state_updated
                }
                revision++
            } catch (e: CancellationException) {
                error = R.string.github_unknown
                throw e
            } catch (e: CoreException) {
                // A lost reply can mean GitHub accepted the write. Keep the draft
                // and require the user to check GitHub before explicitly resending.
                error = if (e is CoreException.Rpc && e.kind in listOf("github_missing", "github_auth")) githubError(e) else R.string.github_unknown
            } finally {
                busy = false
                if (dirty && draftError != R.string.github_draft_failed) scheduleSave()
            }
        }
    }
}
