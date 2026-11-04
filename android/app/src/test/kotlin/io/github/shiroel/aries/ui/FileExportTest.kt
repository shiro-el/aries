package io.github.shiroel.aries.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.robolectric.shadows.ShadowContentResolver
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class FileExportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun str(id: Int) = if (id == R.string.files_export_too_large) compose.activity.getString(id, EXPORT_LIMIT / (1024 * 1024)) else compose.activity.getString(id)

    @Before fun clearExportsFromPreviousTests() {
        File(compose.activity.cacheDir, "file-exports").deleteRecursively()
        attachExportProvider(compose.activity)
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(5_000) { compose.waitForIdle(); condition() }
    }

    // An opaque archive keeps this transfer test independent of platform PDF
    // rendering, which is exercised on-device by RemotePdfDeviceTest.
    @Test fun sharesExactBinaryBytesThroughAReadOnlyContentUri() {
        val bytes = byteArrayOf(0, -1, -128, 13, 10, 42)
        val conn = object : ExportConnection("한글 'report.zip") {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                assertEquals("box/t7", address)
                assertEquals("/repo/한글 'report.zip", path)
                return RemoteFileBytes(bytes, 0u, 6u, true)
            }
        }
        openFile(conn, "한글 'report.zip")
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); shadowOf(compose.activity).peekNextStartedActivity() != null }
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertArrayEquals(bytes, compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }
    private open class ExportConnection(private val name: String = "report.bin") : HostConnection(NoHandle) {
        // The preview's bounded size probe is independent of the transfer under test.
        final override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes =
            if (length == 1u) RemoteFileBytes(byteArrayOf(), 0u, 0u, true)
            else readExportBytes(address, path, offset, length)
        protected open suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes =
            throw CoreException.Rpc("not_found", "missing")
        override suspend fun listFiles(address: String, path: String?) =
            RemoteDirectory("/repo", listOf(RemoteFileEntry(name, false)))
        override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage =
            throw CoreException.Rpc("not_text", "binary")
    }

    private fun openFile(connection: HostConnection, name: String = "report.bin") {
        compose.setContent { MaterialTheme { RemoteFilesScreen(connection, "box/t7", {}) } }
        compose.onNodeWithText(name).performClick()
    }

    private fun waitForStatus(id: Int) {
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithText(str(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun chooseFolder(configure: (ExportDocumentsProvider) -> Unit = {}): ExportDocumentsProvider {
        val provider = ExportDocumentsProvider().also(configure)
        provider.attachInfo(compose.activity, ProviderInfo().apply {
            authority = "exports.test"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("exports.test", provider)
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_save_button)).performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, request.intent.action)
        compose.runOnIdle {
            shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://exports.test/tree/folder")))
        }
        return provider
    }

    @Test fun savesBinaryChunksToTheChosenFolderAndShowsProgress() {
        val next = CompletableDeferred<Unit>()
        val offsets = mutableListOf<ULong>()
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                offsets += offset
                return if (offset == 0uL) RemoteFileBytes(byteArrayOf(0, -1), 0u, 4u, false)
                else { next.await(); RemoteFileBytes(byteArrayOf(1, 2), 2u, 4u, true) }
            }
        }
        openFile(conn)
        val provider = chooseFolder()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.files_export_progress, "2 B", "4 B")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle { next.complete(Unit) }
        waitForStatus(R.string.files_saved)
        assertEquals(listOf(0uL, 2uL), offsets)
        assertEquals("report.bin", provider.createdName)
        assertEquals("application/octet-stream", provider.createdMime)
        assertArrayEquals(byteArrayOf(0, -1, 1, 2), provider.output.readBytes())
        assertFalse(provider.deleted)
        assertTrue(File(compose.activity.cacheDir, "file-exports").listFiles().orEmpty().isEmpty())
    }

    private fun assertNoExportOrChooser() {
        // The picker also appears in the activity queue, separately from its result queue.
        shadowOf(compose.activity).nextStartedActivity?.let { assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, it.action) }
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertTrue(File(compose.activity.cacheDir, "file-exports").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellingDuringDownloadStopsReadsAndRemovesPartialBytes() {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                if (reads.incrementAndGet() == 1) return RemoteFileBytes(byteArrayOf(0, 1), 0u, 4u, false)
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        openFile(conn)
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).performClick()
        waitFor { entered.isCompleted }
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        waitForStatus(R.string.files_export_cancelled)
        assertTrue(cancelled.isCompleted)
        assertEquals(2, reads.get())
        assertNoExportOrChooser()
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).assertIsEnabled()
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
    }

    @Test fun rejectsFilesOver64MiBBeforeReadingTheRestOrSharing() {
        val reads = AtomicInteger()
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                reads.incrementAndGet()
                return RemoteFileBytes(byteArrayOf(0), 0u, 67_108_865u, false)
            }
        }
        openFile(conn)
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).performClick()
        waitForStatus(R.string.files_export_too_large)
        assertEquals(1, reads.get())
        assertNoExportOrChooser()
    }

    @Test fun changedFileSizeDoesNotPublishPartialContent() {
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt) =
                if (offset == 0uL) RemoteFileBytes(byteArrayOf(0, 1), 0u, 4u, false)
                else RemoteFileBytes(byteArrayOf(2, 3, 4), 2u, 5u, true)
        }
        openFile(conn)
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).performClick()
        waitForStatus(R.string.files_export_failed)
        assertNoExportOrChooser()
    }

    @Test fun deniedFolderWriteDeletesTheNewDocument() {
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt) =
                RemoteFileBytes(byteArrayOf(0, -1), 0u, 2u, true)
        }
        openFile(conn)
        val provider = chooseFolder { it.failWrites = true }
        waitForStatus(R.string.files_export_failed)
        assertTrue(provider.deleted)
        assertFalse(provider.output.exists())
        assertNoExportOrChooser()
    }

    @Test fun cancellingWhileSavingDeletesTheNewSafDocument() {
        val release = CountDownLatch(1)
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt) =
                RemoteFileBytes(byteArrayOf(0, -1), 0u, 2u, true)
        }
        openFile(conn)
        val provider = chooseFolder { it.blockOpening = release }
        try {
            waitFor { provider.opening }
            compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        } finally { release.countDown() }
        waitForStatus(R.string.files_export_cancelled)
        assertTrue(provider.deleted)
        assertFalse(provider.output.exists())
        assertNoExportOrChooser()
    }

    @Test fun dismissingFolderPickerDoesNotStartATransfer() {
        val reads = AtomicInteger()
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                reads.incrementAndGet()
                return RemoteFileBytes(byteArrayOf(), 0u, 0u, true)
            }
        }
        openFile(conn)
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_save_button)).performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, Intent()) }
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).assertIsEnabled()
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        assertEquals(0, reads.get())
        assertNoExportOrChooser()
    }

    @Test fun leavingTheFileCancelsThePendingTransfer() {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val conn = object : ExportConnection() {
            override suspend fun readExportBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        openFile(conn)
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_share_button)).performClick()
        waitFor { entered.isCompleted }
        compose.onNodeWithContentDescription(str(R.string.files_back_folder_button)).performClick()
        waitFor { cancelled.isCompleted && File(compose.activity.cacheDir, "file-exports").listFiles().orEmpty().isEmpty() }
        assertNoExportOrChooser()
    }

}


/** A real DocumentsProvider boundary: exercise create/open/write/delete via ContentResolver. */
class ExportDocumentsProvider : DocumentsProvider() {
    lateinit var output: File
    var createdName: String? = null
    var createdMime: String? = null
    var deleted = false
    var failWrites = false
    var blockOpening: CountDownLatch? = null
    @Volatile var opening = false
    override fun onCreate(): Boolean { output = File.createTempFile("saf-export-", ".bin", context!!.cacheDir); return true }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(emptyArray<String>())
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(emptyArray<String>())
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(emptyArray<String>())
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = parentDocumentId == "folder" && documentId == "new-file"
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(parentDocumentId == "folder")
        createdName = displayName
        createdMime = mimeType
        return "new-file"
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        opening = true
        check(blockOpening?.await(10, TimeUnit.SECONDS) != false)
        if (failWrites) throw FileNotFoundException("fixture failure")
        return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun deleteDocument(documentId: String) { check(documentId == "new-file"); deleted = true; output.delete() }
}

/** Uses real Activity recreation so transfer cancellation cannot hide behind restored UI state. */
class ExportRotationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { FileExportControls(connection, "box/t7", "/repo/report.bin") }
        }
    }
    companion object { lateinit var connection: HostConnection }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class FileExportRotationTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    // D121: a suspended remote read must finish and publish complete bytes after rotation.
    @Test fun rotatingDuringDownloadKeepsTheTransferAndItsProgress() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        ExportRotationActivity.connection = object : HostConnection(NoHandle) {
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                if (offset == 0uL) return RemoteFileBytes(byteArrayOf(0, -1), 0u, 4u, false)
                entered.complete(Unit)
                try { release.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled.complete(Unit); throw e }
                return RemoteFileBytes(byteArrayOf(1, 2), 2u, 4u, true)
            }
        }
        val controller = org.robolectric.Robolectric.buildActivity(ExportRotationActivity::class.java).setup()
        try {
            val context = controller.get()
            attachExportProvider(context)
            compose.onNodeWithContentDescription(context.getString(R.string.files_share_button)).performClick()
            compose.waitUntil(5_000) { compose.waitForIdle(); entered.isCompleted }
            controller.recreate()
            compose.waitForIdle()
            assertFalse("configuration changes must not cancel the remote read", cancelled.isCompleted)
            compose.onNodeWithText(context.getString(R.string.files_export_progress, "2 B", "4 B")).assertIsDisplayed()
            compose.runOnIdle { release.complete(Unit) }
            compose.waitUntil(5_000) { compose.waitForIdle(); shadowOf(controller.get()).peekNextStartedActivity() != null }
            val chooser = shadowOf(controller.get()).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertArrayEquals(byteArrayOf(0, -1, 1, 2), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            release.complete(Unit)
            controller.pause().stop().destroy()
        }
    }
}

/** Match Android's provider lifecycle when Robolectric replaces cacheDir between tests. */
private fun attachExportProvider(context: android.content.Context) {
    val info = context.packageManager.resolveContentProvider(
        "${context.packageName}.attachments", android.content.pm.PackageManager.GET_META_DATA)!!
    androidx.core.content.FileProvider().attachInfo(context, info)
}
