package io.github.shiroel.aries.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatUploadLifetimeTest {
    // D05: the previous single batch timeout discarded the second individually timely put.
    @Test fun eachFileGetsItsOwnBudgetEvenWhenTheBatchTakesMoreThanThirtySeconds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = (1..2).map { File(context.cacheDir, "timely-$it.txt").apply { writeText("notes") } }
        val core = FakeCore()
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        val connection = (core.connect(host.id) as FakeConnection).apply { uploadDelays = listOf(20_000, 20_000) }
        val link = HostLink(host, connection, backgroundScope, { connection })
        val chat = ChatModel(core, host,
            TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle), link,
            journalWork = dispatcher)
        try {
            chat.start(backgroundScope); runCurrent()
            assertTrue(chat.attach(context, SharedContent(files.map(Uri::fromFile))))
            fun awaitPut(count: Int) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (connection.uploadStarted < count && chat.uploading && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
                assertEquals(count, connection.uploadStarted)
            }
            awaitPut(1)
            advanceTimeBy(20_000); runCurrent()
            awaitPut(2)
            advanceTimeBy(20_000); runCurrent()
            assertEquals(files.map { it.name }, connection.uploads.map { it.second })
            assertEquals(2, chat.attachments.size)
            assertNull(chat.attachmentError)
            assertFalse(chat.uploading)
        } finally {
            chat.cancelUpload(); chat.stop(); link.close(); runCurrent()
            files.forEach { it.delete() }; Dispatchers.resetMain()
        }
    }
    // D05: a single slow file must retain the unfinished filenames and report a timeout, not a lost Host.
    @Test fun anIndividualTimeoutKeepsCompletedFilesAndIdentifiesTheUnfinishedFiles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = (1..3).map { File(context.cacheDir, "timely-$it.txt").apply { writeText("notes") } }
        val core = FakeCore()
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        val connection = (core.connect(host.id) as FakeConnection).apply { uploadDelays = listOf(20_000, 31_000) }
        val link = HostLink(host, connection, backgroundScope, { connection })
        val chat = ChatModel(core, host,
            TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle), link,
            journalWork = dispatcher)
        try {
            chat.start(backgroundScope); runCurrent()
            assertTrue(chat.attach(context, SharedContent(files.map(Uri::fromFile))))
            fun awaitPut(count: Int) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (connection.uploadStarted < count && chat.uploading && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
                assertEquals(count, connection.uploadStarted)
            }
            awaitPut(1)
            advanceTimeBy(20_000); runCurrent()
            awaitPut(2)
            advanceTimeBy(30_000); runCurrent()
            assertEquals(listOf(files.first().name), connection.uploads.map { it.second })
            assertEquals(1, chat.attachments.size)
            assertNotNull(chat.attachmentError)
            assertNotEquals(io.github.shiroel.aries.R.string.attachments_connection_failed, chat.attachmentError!!.res)
            assertEquals(files.drop(1).map { it.name }, chat.failedAttachments)
            assertEquals(2, connection.uploadStarted)
            assertFalse(chat.uploading)
        } finally {
            chat.cancelUpload(); chat.stop(); link.close(); runCurrent()
            files.forEach { it.delete() }; Dispatchers.resetMain()
        }
    }
    // D18: provider cancellation must release Send before a misbehaving provider returns.
    @Test fun cancelReleasesSendImmediatelyWhileProviderOpenIsStillBlocked() = blockedProvider(cancel = true)

    // D18: the per-file deadline must also release a provider read that has not returned.
    @Test fun deadlineReleasesSendWhileProviderOpenIsStillBlocked() = blockedProvider(cancel = false)

    private fun blockedProvider(cancel: Boolean) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = BlockingAttachmentProvider().apply {
            attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "blocked-upload" })
        }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("blocked-upload", provider)
        val core = FakeCore()
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        val connection = core.connect(host.id) as FakeConnection
        val link = HostLink(host, connection, backgroundScope, { connection })
        val chat = ChatModel(core, host,
            TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle), link,
            journalWork = dispatcher)
        try {
            chat.start(backgroundScope)
            core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null)); runCurrent()
            chat.draft = "Keep this instruction"
            assertTrue(chat.attach(context, SharedContent(listOf(Uri.parse("content://blocked-upload/file")))))
            runCurrent()
            assertTrue(provider.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(chat.uploading)
            assertFalse(chat.canSend)
            if (cancel) chat.cancelUpload() else { advanceTimeBy(30_000); runCurrent() }
            assertFalse("Cancellation/deadline must not wait for provider IO", chat.uploading)
            assertTrue("Existing text can be sent immediately", chat.canSend)
            runCurrent()
            assertTrue("The provider must receive cancellation", provider.canceled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("file"), chat.failedAttachments)
            val next = File(context.cacheDir, "next-upload.txt").apply { writeText("next file") }
            try {
                connection.uploadHold = CompletableDeferred()
                assertTrue(chat.attach(context, SharedContent(listOf(Uri.fromFile(next)))))
                val deadline = System.nanoTime() + 5_000_000_000L
                while (connection.uploadStarted == 0 && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
                assertEquals(1, connection.uploadStarted)
                provider.release.countDown()
                runCurrent()
                assertTrue("An old provider completion must not release the new upload", chat.uploading)
                assertTrue(chat.attachments.isEmpty())
                connection.uploadHold!!.complete(Unit); runCurrent()
                assertEquals(listOf("next-upload.txt"), chat.attachments.map { it.name })
                assertFalse(chat.uploading)
            } finally { next.delete(); connection.uploadHold?.complete(Unit) }
        } finally {
            provider.release.countDown()
            chat.cancelUpload(); chat.stop(); link.close(); runCurrent()
            Dispatchers.resetMain()
        }
    }

}


/** An open call that ignores cancellation until the test explicitly releases it. */
class BlockingAttachmentProvider : android.content.ContentProvider() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val canceled = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor? = null
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: android.os.Bundle?, signal: android.os.CancellationSignal?) =
        openAssetFile(uri, "r", signal)
    override fun openAssetFile(uri: Uri, mode: String) = openAssetFile(uri, mode, null)
    override fun openAssetFile(uri: Uri, mode: String, signal: android.os.CancellationSignal?): android.content.res.AssetFileDescriptor? {
        signal?.setOnCancelListener { canceled.countDown() }
        entered.countDown()
        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        signal?.throwIfCanceled()
        throw java.io.FileNotFoundException("test provider released")
    }
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
