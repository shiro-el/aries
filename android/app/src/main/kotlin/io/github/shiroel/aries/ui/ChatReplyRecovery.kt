package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.AriesCoreInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Notification replies join the same encrypted, phone-owned outbox as Chat (221).
 * A live owner merges on the UI thread, so a late failure cannot overwrite its
 * newer draft or be overwritten by that owner's next journal save.
 */
internal object ChatReplyRecovery {
    private val owners = WeakHashMap<AriesCoreInterface, MutableMap<AgentKey, WeakReference<ChatModel>>>()

    @Synchronized fun register(core: AriesCoreInterface, key: AgentKey, owner: ChatModel, restore: () -> Unit) {
        restore()
        owners.getOrPut(core) { mutableMapOf() }.apply {
            entries.removeAll { it.value.get() == null }
            put(key, WeakReference(owner))
        }
    }

    /** Null means it was saved directly, with no live composer to update. */
    @Synchronized private fun saveOrOwner(core: AriesCoreInterface, key: AgentKey, message: QueuedChatMessage): ChatModel? {
        owners[core]?.get(key)?.get()?.let { return it }
        val previous = core.chatOutbox(key.hostId, key.address)?.let(::decodeChatOutbox)
            ?: ChatOutboxState("", emptyList(), emptyList())
        if (previous.messages.none { it.id == message.id }) {
            core.saveChatOutbox(key.hostId, key.address, previous.copy(messages = previous.messages + message).encode())
        }
        return null
    }

    fun recover(core: AriesCoreInterface, key: AgentKey, message: QueuedChatMessage) {
        saveOrOwner(core, key, message)?.recoverReply(message)
    }

    suspend fun recoverFromWorker(core: AriesCoreInterface, key: AgentKey, message: QueuedChatMessage) {
        val owner = saveOrOwner(core, key, message) ?: return
        // The notification worker must outlive the owner's asynchronous encrypted
        // checkpoint, including a debounce or earlier write already in flight.
        withContext(Dispatchers.Main.immediate) { owner.recoverReply(message)?.join() }
    }

    private data class QueuedReply(val owner: ChatModel?, val queued: Boolean)

    /** Reserve the same FIFO before a background sender may open its own line.
     * A disconnected journal still owns Waiting/ambiguous input; replies append
     * there instead of overtaking it. Failed rows are explicitly not barriers.
     */
    @Synchronized private fun enqueueOrOwner(core: AriesCoreInterface, key: AgentKey, message: QueuedChatMessage): QueuedReply {
        owners[core]?.get(key)?.get()?.let { return QueuedReply(it, true) }
        val previous = core.chatOutbox(key.hostId, key.address)?.let(::decodeChatOutbox)
            ?: return QueuedReply(null, false)
        if (previous.messages.none { it.phase != QueuedMessagePhase.Failed }) return QueuedReply(null, false)
        if (previous.messages.none { it.id == message.id }) {
            core.saveChatOutbox(key.hostId, key.address, previous.copy(messages = previous.messages + message).encode())
        }
        return QueuedReply(null, true)
    }

    /** True means durably queued; false permits one immediate background attempt. */
    suspend fun enqueueFromWorker(core: AriesCoreInterface, key: AgentKey, generation: String, text: String): Boolean {
        val message = QueuedChatMessage(id = "notification:$generation", draft = text,
            attachments = emptyList(), text = text, before = emptySet())
        val decision = enqueueOrOwner(core, key, message)
        return decision.owner?.let { owner ->
            withContext(Dispatchers.Main.immediate) {
                if (!owner.ownsReplyFifo) false
                else {
                    owner.recoverReply(message)?.join()
                    check(owner.journalError == null) { "Reply journal could not be saved" }
                    true
                }
            }
        } ?: decision.queued
    }

    fun message(generation: String, text: String, line: String, uncertain: Boolean) = QueuedChatMessage(
        id = "notification:$generation", draft = text, attachments = emptyList(), text = text, before = emptySet(),
        phase = if (uncertain) QueuedMessagePhase.Uncertain else QueuedMessagePhase.Failed, error = line,
    )
}
