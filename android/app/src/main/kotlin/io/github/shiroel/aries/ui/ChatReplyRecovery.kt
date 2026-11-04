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
        withContext(Dispatchers.Main.immediate) { owner.recoverReply(message) }
    }

    fun message(generation: String, text: String, line: String, uncertain: Boolean) = QueuedChatMessage(
        id = "notification:$generation", draft = text, attachments = emptyList(), text = text, before = emptySet(),
        phase = if (uncertain) QueuedMessagePhase.Uncertain else QueuedMessagePhase.Failed, error = line,
    )
}
