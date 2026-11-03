package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.R
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteMediaTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun opensAnImageFromSequentialBinaryChunksAndReturnsToItsFolder() {
        val bytes = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
                compress(Bitmap.CompressFormat.PNG, 100, out)
                recycle()
            }
            out.toByteArray()
        }
        val offsets = mutableListOf<ULong>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?) =
                RemoteDirectory("/repo", listOf(RemoteFileEntry("shot.PNG", false)))
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage =
                error("Images must use binary reads")
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                assertEquals("box/t7", address)
                assertEquals("/repo/shot.PNG", path)
                synchronized(offsets) { offsets += offset }
                val end = if (offset == 0uL) 17 else bytes.size
                return RemoteFileBytes(bytes.copyOfRange(offset.toInt(), end), offset, bytes.size.toULong(), end == bytes.size)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "box/t7", {}) } }
        compose.onNodeWithText("shot.PNG").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Image viewer").assertIsDisplayed()
        assertEquals(listOf(0uL, 17uL), synchronized(offsets) { offsets.toList() })
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Back to folder").performClick()
        compose.onNodeWithText("shot.PNG").assertExists()
    }
    @Test fun opensSvgAsAnImageRatherThanXmlSource() {
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><rect width="200" height="100" fill="red"/></svg>""".toByteArray()
        val conn = mediaConnection(bytes)
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/repo/chart.svg")) } }
        waitForImage()
        val pixels = compose.onNodeWithContentDescription("Image viewer").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(center.red > .9f && center.green < .1f && center.blue < .1f)
    }

    @Test fun opensJpegGifAndWebpWithTheSharedImageViewer() {
        fun encoded(format: Bitmap.CompressFormat) = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
                compress(format, 100, out)
                recycle()
            }
            out.toByteArray()
        }
        val fixtures = mapOf(
            "jpg" to encoded(Bitmap.CompressFormat.JPEG),
            "webp" to encoded(Bitmap.CompressFormat.WEBP_LOSSLESS),
            "gif" to Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"),
        )
        var target by mutableStateOf(FileLocation("/repo/shot.jpg"))
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                val bytes = fixtures.getValue(path.substringAfterLast('.'))
                return RemoteFileBytes(bytes, 0uL, bytes.size.toULong(), true)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = target) } }
        for (extension in fixtures.keys) {
            compose.runOnIdle { target = FileLocation("/repo/shot.$extension") }
            waitForImage()
            compose.onNodeWithContentDescription("Image viewer").assertIsDisplayed()
            compose.onNodeWithText("Back").performClick()
        }
    }

    @Test fun oversizedImagesStopAtTheFirstChunkAndKeepFileExportsAvailable() {
        var reads = 0
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                reads++
                return RemoteFileBytes(byteArrayOf(1), 0uL, 17uL * 1024uL * 1024uL, false)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/repo/large.png")) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This file exceeds the 16 MiB preview limit.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, reads)
        compose.onNodeWithText(compose.activity.getString(R.string.files_save_button)).assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.files_share_button)).assertIsEnabled()
        compose.onNodeWithContentDescription("Image viewer").assertDoesNotExist()
    }

    @Test fun anInconsistentTransferNeverDisplaysPartialDataAndRefreshCanRetry() {
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10"><rect width="20" height="10" fill="red"/></svg>""".toByteArray()
        val valid = java.util.concurrent.atomic.AtomicBoolean(false)
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                return if (valid.get()) RemoteFileBytes(bytes, 0uL, bytes.size.toULong(), true)
                else if (offset == 0uL) RemoteFileBytes(bytes.copyOfRange(0, 10), 0uL, bytes.size.toULong(), false)
                else RemoteFileBytes(bytes.copyOfRange(10, bytes.size), 10uL, bytes.size.toULong() + 1uL, false)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/repo/chart.svg")) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Could not preview this file. Check the file and connection, then refresh to retry.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Image viewer").assertDoesNotExist()
        valid.set(true)
        compose.onNodeWithText("Refresh").performClick()
        waitForImage()
    }

    @Test fun returningToTheFolderCancelsAnIncompletePreview() {
        val reading = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?) = RemoteDirectory("/repo", emptyList())
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                if (offset == 0uL) return RemoteFileBytes(byteArrayOf(1), 0uL, 2uL, false)
                reading.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/repo/shot.png")) } }
        compose.waitUntil(5_000) { reading.isCompleted }
        compose.onNodeWithText("Back to folder").performClick()
        compose.onNodeWithText("This folder is empty.").assertExists()
        compose.waitUntil(5_000) { compose.waitForIdle(); cancelled.isCompleted }
        compose.onNodeWithContentDescription("Image viewer").assertDoesNotExist()
        compose.onNodeWithText("This folder is empty.").assertExists()
    }

    private fun mediaConnection(bytes: ByteArray) = object : HostConnection(NoHandle) {
        override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage =
            error("Media must use binary reads")
        override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt) =
            RemoteFileBytes(bytes, 0uL, bytes.size.toULong(), true)
    }

    private fun waitForImage() {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
    }
}
