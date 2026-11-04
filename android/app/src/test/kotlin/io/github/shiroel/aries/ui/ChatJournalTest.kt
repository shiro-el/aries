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
    // D10: identical old User entries must not acknowledge live or process-restored notification recovery.
    @Test fun recoveredNotificationOnlyMatchesEntriesAfterItsRecoveryBaseline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (recoverBeforeOwner in listOf(false, true)) {
                val core = FakeCore()
                val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
                val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)
                val connection = core.connect(host.id) as FakeConnection
                val link = HostLink(host, connection, backgroundScope, { connection })
                val message = ChatReplyRecovery.message("lost", "same instruction", "lost reply", uncertain = true)
                if (recoverBeforeOwner) ChatReplyRecovery.recover(core, AgentKey(host.id, "t1"), message)
                val chat = ChatModel(core, host, terminal, link, journalWork = dispatcher)
                chat.start(backgroundScope)
                val old = ChatEntry("old", null, ChatEntryBody.User("same instruction"))
                core.pushChat(ChatSnapshot(listOf(old), ChatStatus.LIVE, null))
                runCurrent()
                if (!recoverBeforeOwner) ChatReplyRecovery.recover(core, AgentKey(host.id, "t1"), message)
                chat.update(listOf(terminal))
                runCurrent()
                assertEquals("Recovery must survive the identical historical entry", message.id, chat.queuedMessages.single().id)
                assertEquals(QueuedMessagePhase.Uncertain, chat.queuedMessages.single().phase)
                val newer = ChatEntry("new", null, ChatEntryBody.User("same instruction"))
                core.pushChat(ChatSnapshot(listOf(old, newer), ChatStatus.LIVE, null))
                runCurrent()
                assertTrue(chat.queuedMessages.isEmpty())
                chat.stop()
                runCurrent()
            }
        } finally { Dispatchers.resetMain() }
    }

    // D14: either failed checkpoint must stop at Failed, rather than leave idle input Queued or type it.
    @Test fun journalFailureBeforeEnqueueOrDispatchLeavesAnExplicitlyRetryableFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (failAttemptOnly in listOf(false, true)) {
                val backing = FakeCore()
                val core = object : AriesCoreInterface by backing {
                    override fun saveChatOutbox(hostId: String, terminal: String, content: String?) {
                        val rows = content?.let(::decodeChatOutbox)?.messages.orEmpty()
                        if (rows.any { !failAttemptOnly || it.phase == QueuedMessagePhase.Agent })
                            throw CoreException.Storage("disk full")
                        backing.saveChatOutbox(hostId, terminal, content)
                    }
                }
                val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
                val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)
                val connection = core.connect(host.id) as FakeConnection
                val link = HostLink(host, connection, backgroundScope, { connection })
                val chat = ChatModel(core, host, terminal, link, journalWork = dispatcher)
                chat.start(backgroundScope)
                backing.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
                runCurrent()
                chat.draft = "keep this instruction"
                chat.send()
                runCurrent()
                repeat(4) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); runCurrent() }
                assertEquals(QueuedMessagePhase.Failed, chat.queuedMessages.single().phase)
                assertEquals("disk full", chat.queuedMessages.single().error)
                assertNotNull(chat.journalError)
                assertTrue(connection.typed.isEmpty())
                chat.stop()
                runCurrent()
            }
        } finally { Dispatchers.resetMain() }
    }

    // D17: upload success must not destroy the camera source before its Host path is durable.
    @Test fun uploadedCameraSourceSurvivesUntilItsJournalCheckpointCompletes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pendingIo = java.util.ArrayDeque<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()
        val storage = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pendingIo.add(context to block) }
        }
        fun flushStorage() {
            while (pendingIo.isNotEmpty()) {
                val batch = pendingIo.toList(); pendingIo.clear()
                batch.forEach { (context, block) -> dispatcher.dispatch(context, block) }
                runCurrent()
            }
        }
        Dispatchers.setMain(dispatcher)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val photo = java.io.File.createTempFile("photo-", ".jpg", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        AttachmentSettings.from(context).chooseResizeImages(false)
        try {
            val core = FakeCore()
            val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
            val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)
            val connection = core.connect(host.id) as FakeConnection
            val link = HostLink(host, connection, backgroundScope, { connection })
            val chat = ChatModel(core, host, terminal, link, journalWork = storage)
            chat.start(backgroundScope)
            runCurrent(); flushStorage()
            chat.attach(context, SharedContent(listOf(android.net.Uri.fromFile(photo))), onUploaded = { photo.delete() })
            // Do not advance virtual RPC time while real file IO prepares the source.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (connection.uploads.isEmpty() && chat.uploading && System.nanoTime() < deadline) {
                runCurrent(); Thread.sleep(10)
            }
            runCurrent()
            assertNull(chat.attachmentError)
            assertEquals(1, connection.uploads.size)
            assertTrue("Upload success alone must retain the source", photo.exists())
            assertTrue(core.chatOutbox(host.id, "t1")?.let(::decodeChatOutbox)?.attachments.orEmpty().isEmpty())
            flushStorage()
            assertFalse(photo.exists())
            assertEquals(listOf("/host/it's file.jpg"), decodeChatOutbox(core.chatOutbox(host.id, "t1")!!).attachments.map { it.path })
            chat.stop(); runCurrent(); flushStorage()
        } finally { photo.delete(); Dispatchers.resetMain() }
    }

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
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
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
            // D16: a matching PC entry during the attempt checkpoint predates our RPC.
            val preAttempt = ChatEntry("pc-before-attempt", null, ChatEntryBody.User("last character!\n\nnewer!"))
            core.pushChat(ChatSnapshot(listOf(preAttempt), ChatStatus.LIVE, null))
            runCurrent()
            assertEquals(1, chat.queuedMessages.size)
            runIo() // Attempt checkpoint.
            runCurrent()
            assertEquals(listOf("t1" to "last character!\n\nnewer!"), connection.typed)
            core.pushChat(ChatSnapshot(listOf(preAttempt), ChatStatus.LIVE, null))
            runCurrent()
            assertEquals("The pre-attempt entry is still excluded after sending", 1, chat.queuedMessages.size)
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
