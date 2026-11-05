package io.github.shiroel.aries.ui

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the platform PdfRenderer with real PDF bytes, not a renderer substitute. */
@RunWith(AndroidJUnit4::class)
class RemotePdfDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun opensChunkedPdfTurnsPagesAndResetsZoomForEachPage() {
        val bytes = ByteArrayOutputStream().use { out ->
            val document = PdfDocument()
            try {
                listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(200, 200, index + 1).create())
                    page.canvas.drawColor(color)
                    document.finishPage(page)
                }
                document.writeTo(out)
            } finally { document.close() }
            out.toByteArray()
        }
        val offsets = mutableListOf<ULong>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage =
                throw CoreException.Rpc("not_text", "PDF")
            override suspend fun listFiles(address: String, path: String?) = RemoteDirectory("/repo", emptyList())
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt): RemoteFileBytes {
                assertEquals("box/t7", address)
                assertEquals("/repo/report.PDF", path)
                synchronized(offsets) { offsets += offset }
                val end = if (offset == 0uL) 19 else bytes.size
                return RemoteFileBytes(bytes.copyOfRange(offset.toInt(), end), offset, bytes.size.toULong(), end == bytes.size)
            }
        }
        compose.setContent { MaterialTheme {
            RemoteFilesScreen(conn, "box/t7", {}, initialLocation = FileLocation("/repo/report.PDF"))
        } }
        waitForPage(1)
        assertEquals(listOf(0uL, 19uL), synchronized(offsets) { offsets.toList() })
        compose.onNodeWithContentDescription(str(R.string.files_previous_button)).assertIsNotEnabled()
        assertPageColor(1, red = true)
        compose.onNodeWithTag("pdf-page:1").performTouchInput { doubleClick(center) }
        assertTrue(compose.onNodeWithTag("pdf-page:1").fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("300"))
        compose.onNodeWithContentDescription(str(R.string.files_next_button)).performClick()
        waitForPage(2)
        assertTrue(compose.onNodeWithTag("pdf-page:2").fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("100"))
        assertPageColor(2, red = false)
        compose.onNodeWithContentDescription(str(R.string.files_next_button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.files_previous_button)).performClick()
        waitForPage(1)
        assertPageColor(1, red = true)
        compose.onNodeWithContentDescription(str(R.string.files_back_folder_button)).performClick()
        compose.onNodeWithTag("pdf-page:1").assertDoesNotExist()
    }

    @Test fun corruptPdfShowsGuidanceAndKeepsFileActionsAvailable() {
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt) =
                RemoteFileBytes(byteArrayOf(1, 2, 3), 0uL, 3uL, true)
        }
        compose.setContent { MaterialTheme {
            RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/repo/broken.pdf"))
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(str(R.string.files_preview_failed)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_save_button)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.files_share_button)).assertIsEnabled()
        compose.onNodeWithTag("pdf-page:1").assertDoesNotExist()
    }

    private fun str(id: Int) = compose.activity.getString(id)
    private fun waitForPage(number: Int) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("pdf-page:$number").fetchSemanticsNodes().isNotEmpty()
    }
    private fun assertPageColor(number: Int, red: Boolean) {
        val pixels = compose.onNodeWithTag("pdf-page:$number").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        if (red) assertTrue(center.red > .9f && center.blue < .1f)
        else assertTrue(center.blue > .9f && center.red < .1f)
    }
}
