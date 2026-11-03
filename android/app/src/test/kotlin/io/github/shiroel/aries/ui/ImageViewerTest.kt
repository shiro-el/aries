package io.github.shiroel.aries.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.app.Activity
import android.net.Uri
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.captureToImage
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ChatImage
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun initializeTheManifestFileProvider() {
        // Robolectric loads providers lazily. Attach this test's provider before
        // FileProvider resolves paths, so its static path cache uses this app's
        // cache directory rather than a previous test's temporary directory.
        compose.activity.contentResolver.acquireContentProviderClient("${compose.activity.packageName}.attachments")!!.close()
    }

    @Test fun tappingAChatImageOpensTheViewerAndCloseReturnsToChat() {
        val bytes = png()
        compose.setContent {
            MaterialTheme {
                ChatImageView(ChatImage("shot#0", "image/png", bytes.size.toULong(), 800u, 600u, true)) { bytes }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(context.getString(R.string.chat_image)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_image)).performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Save image").assertIsDisplayed()
        compose.onNodeWithText("Share image").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.back_button)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_image)).assertIsDisplayed()
    }

    private val viewer get() = compose.onNodeWithContentDescription("Image viewer")

    private fun openViewer() {
        val bytes = png()
        compose.setContent { MaterialTheme { ImageViewer(bytes, onDismiss = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Image viewer"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun zoom() = viewer.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .filter(Char::isDigit).toInt()
    private fun x() = viewer.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
    private fun y() = viewer.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun pinchZoomEnablesPanningInBothDirectionsAndStaysWithinTheImage() {
        openViewer()
        assertEquals(100, zoom())
        viewer.performTouchInput { swipe(center, center + Offset(100f, 100f)) }
        assertEquals(0f, x(), 0.01f)
        assertEquals(0f, y(), 0.01f)
        viewer.performTouchInput {
            pinch(center - Offset(30f, 0f), center - Offset(150f, 0f),
                center + Offset(30f, 0f), center + Offset(150f, 0f))
        }
        assertTrue(zoom() > 100)
        val startX = x()
        val startY = y()
        viewer.performTouchInput { swipe(center, center - Offset(100f, 100f)) }
        assertTrue(x() > startX + 10f)
        assertTrue(y() > startY + 10f)
        repeat(8) { viewer.performTouchInput { swipe(center, center - Offset(150f, 150f)) } }
        val ranges = viewer.fetchSemanticsNode().config
        assertEquals(ranges[SemanticsProperties.HorizontalScrollAxisRange].maxValue(), x(), 1f)
        assertEquals(ranges[SemanticsProperties.VerticalScrollAxisRange].maxValue(), y(), 1f)
    }

    @Test fun doubleTapZoomsAtTheTapAndThenResetsToFit() {
        openViewer()
        viewer.performTouchInput { doubleClick(center + Offset(80f, 0f)) }
        assertEquals(300, zoom())
        assertTrue(x() > viewer.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() / 2f)
        viewer.performTouchInput { advanceEventTime(500); doubleClick(center) }
        assertEquals(100, zoom())
        assertEquals(0f, x(), 0.01f)
        assertEquals(0f, y(), 0.01f)
    }

    @Test fun sharesTheOriginalImageAsAReadOnlyContentUri() {
        val bytes = png()
        compose.setContent { MaterialTheme { ImageViewer(bytes, onDismiss = {}, name = "/repo/한글 shot.png") } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Image viewer"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Share image").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); shadowOf(compose.activity).peekNextStartedActivity() != null }
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals("한글 shot.png", uri.lastPathSegment)
        assertArrayEquals(bytes, compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun savesTheOriginalBytesToTheDocumentChosenByTheUser() {
        val bytes = png()
        compose.setContent { MaterialTheme { ImageViewer(bytes, onDismiss = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Image viewer"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val provider = imageDocuments()
        compose.onNodeWithText("Save image").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("image/png", request.intent.type)
        assertEquals("image.png", request.intent.getStringExtra(Intent.EXTRA_TITLE))
        compose.runOnIdle {
            shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://image-viewer.test/document/new-file")))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Image saved").fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals(bytes, provider.output.readBytes())
    }

    @Test fun cancellingTheSavePickerLeavesTheViewerUsable() {
        openViewer()
        compose.onNodeWithText("Save image").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        compose.onNodeWithText("Share image").assertIsNotEnabled()
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, Intent()) }
        compose.onNodeWithText("Save image").assertIsEnabled()
        compose.onNodeWithText("Share image").assertIsEnabled()
        compose.onNodeWithText("Image saved").assertDoesNotExist()
    }

    @Test fun aFailedSaveRemovesThePartialDocumentAndAllowsRetry() {
        openViewer()
        val provider = imageDocuments(failWrites = true)
        compose.onNodeWithText("Save image").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnIdle {
            shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://image-viewer.test/document/new-file")))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Could not save or share this image. Try again.").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(provider.deleted)
        compose.onNodeWithText("Could not save or share this image. Try again.").assertIsDisplayed()
        assertFalse(provider.output.exists())
        compose.onNodeWithText("Save image").assertIsEnabled()
    }

    @Test fun unreadableImagesKeepTheCloseActionAndDisableExports() {
        compose.setContent { MaterialTheme { ImageViewer(byteArrayOf(1, 2, 3), onDismiss = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.chat_image_unreadable)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save image").assertIsNotEnabled()
        compose.onNodeWithText("Share image").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.back_button)).assertIsEnabled()
    }

    @Test fun pinchCannotShrinkBelowFitOrEnlargePastEightTimes() {
        openViewer()
        repeat(2) {
            viewer.performTouchInput {
                pinch(center - Offset(10f, 0f), center - Offset(150f, 0f),
                    center + Offset(10f, 0f), center + Offset(150f, 0f))
            }
        }
        assertEquals(800, zoom())
        repeat(2) {
            viewer.performTouchInput {
                pinch(center - Offset(150f, 0f), center - Offset(10f, 0f),
                    center + Offset(150f, 0f), center + Offset(10f, 0f))
            }
        }
        assertEquals(100, zoom())
        assertEquals(0f, x(), .01f)
        assertEquals(0f, y(), .01f)
    }

    @Test fun zoomAndPanChangeTheRenderedImagePixels() {
        openViewer()
        val fit = markerBounds()
        viewer.performTouchInput { doubleClick(center) }
        val enlarged = markerBounds()
        assertTrue("the image itself grows", enlarged.width() > fit.width() * 2)
        assertTrue(enlarged.height() > fit.height() * 2)
        viewer.performTouchInput { swipe(center, center + Offset(40f, 40f)) }
        val moved = markerBounds()
        assertTrue("drag moves the drawn image horizontally", moved.left > enlarged.left + 10)
        assertTrue("drag moves the drawn image vertically", moved.top > enlarged.top + 10)
    }

    private fun markerBounds(): Rect {
        val pixels = viewer.captureToImage().toPixelMap()
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.red > .9f && pixel.green < .1f && pixel.blue < .1f) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("the fixture marker is drawn", right >= left && bottom >= top)
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun imageDocuments(failWrites: Boolean = false): ExportDocumentsProvider {
        val provider = ExportDocumentsProvider().also { it.failWrites = failWrites }
        provider.attachInfo(compose.activity, ProviderInfo().apply {
            authority = "image-viewer.test"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("image-viewer.test", provider)
        return provider
    }

    private fun png(): ByteArray = ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(320f, 520f, 480f, 680f, Paint().apply { color = android.graphics.Color.RED })
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
}
