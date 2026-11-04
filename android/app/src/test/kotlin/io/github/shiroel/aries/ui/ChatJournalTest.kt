package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatJournalTest {
    // D17: rapid edits must coalesce, while background/navigation flushes retain the final character.
    @Test fun typingIsDebouncedAndPauseAndStopFlushTheLatestDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val core = FakeCore()
            val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
            val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
            val chat = ChatModel(core, host, terminal, journalWork = dispatcher)
            val owner = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.Job())
            chat.start(owner)
            runCurrent()
            chat.draft = "fir"
            chat.draft = "first"
            runCurrent()
            assertNull(core.chatOutbox(host.id, terminal.address))
            advanceTimeBy(299)
            runCurrent()
            assertNull(core.chatOutbox(host.id, terminal.address))
            advanceTimeBy(1)
            runCurrent()
            assertEquals("first", decodeChatOutbox(core.chatOutbox(host.id, terminal.address)!!).draft)
            for (flush in listOf<() -> Unit>(chat::pause, { chat.stop(); owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel() })) {
                chat.draft += "!"
                flush()
                runCurrent()
                assertEquals(chat.draft, decodeChatOutbox(core.chatOutbox(host.id, terminal.address)!!).draft)
            }
        } finally { Dispatchers.resetMain() }
    }
    // D17: Send/cancel during debounce must checkpoint the final text, and delayed IO must precede typing.
    @Test fun sendAndCancelFlushWithoutWaitingForDebounceAndDispatchWaitsForStorage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pendingIo = java.util.ArrayDeque<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()
        val ioDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                pendingIo.add(context to block)
            }
        }
        fun runIo() {
            val batch = pendingIo.toList()
            pendingIo.clear()
            batch.forEach { (context, block) -> dispatcher.dispatch(context, block) }
            runCurrent()
        }
        Dispatchers.setMain(dispatcher)
        try {
            val core = FakeCore()
            val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
            val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
            core.terminals = listOf(terminal)
            val connection = core.connect(host.id) as FakeConnection
            val link = HostLink(host, connection, backgroundScope, { connection })
            val chat = ChatModel(core, host, terminal, link, journalWork = ioDispatcher)
            chat.start(backgroundScope)
            core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
            runCurrent()
            runIo()
            runCurrent()
            chat.draft = "last character!"
            chat.send()
            runCurrent()
            runIo()
            runCurrent()
            assertEquals("last character!", decodeChatOutbox(core.chatOutbox(host.id, "t1")!!).messages.single().draft)
            chat.draft = "newer!"
            chat.cancelQueuedMessage(chat.queuedMessages.single().id)
            runCurrent()
            runIo()
            runCurrent()
            val cancelled = decodeChatOutbox(core.chatOutbox(host.id, "t1")!!)
            assertEquals("last character!\n\nnewer!", cancelled.draft)
            assertTrue(cancelled.messages.isEmpty())
            chat.update(listOf(terminal.copy(agentStatus = AgentStatus.Idle)))
            chat.send()
            runCurrent()
            assertTrue(connection.typed.isEmpty())
            runIo() // Waiting checkpoint only.
            runCurrent()
            assertTrue(connection.typed.isEmpty())
            runIo() // Attempt checkpoint.
            runCurrent()
            assertEquals(listOf("t1" to "last character!\n\nnewer!"), connection.typed)
            repeat(4) { runIo(); runCurrent() }
            // An Agent ending during a delayed checkpoint must return the unattempted row to the input.
            chat.update(listOf(terminal))
            chat.draft = "not typed before ending!"
            chat.send()
            chat.update(listOf(terminal.copy(agentStatus = AgentStatus.Idle)))
            runCurrent()
            chat.update(listOf(terminal.copy(agentStatus = null)))
            repeat(4) { runIo(); runCurrent() }
            assertEquals("not typed before ending!", chat.draft)
            assertEquals("not typed before ending!", decodeChatOutbox(core.chatOutbox(host.id, "t1")!!).draft)
            assertEquals(listOf("t1" to "last character!\n\nnewer!"), connection.typed)
            chat.stop()
            repeat(4) { runIo(); runCurrent() }
        } finally { Dispatchers.resetMain() }
    }

}
