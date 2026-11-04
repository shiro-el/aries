package io.github.shiroel.aries.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.io.File
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** System picker/camera results cross the same UI -> fake core seam as incoming shares. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentEntryPointsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun str(id: Int) = compose.activity.getString(id)

    private lateinit var model: HostsModel
    private var reconnectGate: CompletableDeferred<Unit>? = null

    private fun openChat(): FakeConnection {
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle, false, 0uL))
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(object : AriesCoreInterface by core {
                override suspend fun connect(hostId: String): HostConnection {
                    reconnectGate?.await()
                    return core.connect(hostId)
                }
            }, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        return core.connection!!
    }

    @Test fun filePickerRejectsOversizeWithAnExplanationAndKeepsTheDraft() {
        val connection = openChat()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("keep this")
        compose.onNodeWithContentDescription(str(R.string.attachments_file_button)).performClick()
        compose.waitForIdle()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertTrue(request.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val uri = Uri.parse("content://documents/oversize")
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1)))
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri)) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.attachments_too_large, MAX_ATTACHMENT_BYTES / (1024 * 1024))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("keep this")
        assertTrue(connection.uploads.isEmpty())
        assertTrue(connection.typed.isEmpty())
    }

    @Test fun pickerResultWaitsForTheConnectionReleasedWhileThePickerWasOpen() {
        val old = openChat()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("keep this")
        compose.onNodeWithContentDescription(str(R.string.attachments_file_button)).performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val ready = CompletableDeferred<Unit>()
        reconnectGate = ready
        compose.runOnIdle { model.backgrounded() }
        // A long picker visit outlasts the warm connection grace period.
        compose.mainClock.advanceTimeBy(120_100)
        compose.runOnIdle { model.resumeScreens() }
        val chat = (model.screen as Screen.Chat).chat
        val uri = Uri.parse("content://documents/returned-file")
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(4, 2)))
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri)) }
        compose.waitForIdle()
        assertTrue("The picker result must survive foreground reconnection", chat.uploading)
        assertNull(chat.attachmentError)
        assertTrue(old.uploads.isEmpty())
        ready.complete(Unit)
        compose.waitUntil(5_000) { chat.attachments.size == 1 }
        compose.waitForIdle()
        assertFalse(chat.uploading)
        assertNull(chat.attachmentError)
        assertArrayEquals(byteArrayOf(4, 2), (chat.link!!.connection!! as FakeConnection).uploads.single().third)
        assertTrue(old.uploads.isEmpty())
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("keep this")
    }

    @Test fun cameraGetsAFullSizeOutputUriThenKeepsTheUploadedPhotoOutsideTheTextField() {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.CAMERA)
        val connection = openChat()
        compose.onNodeWithContentDescription(str(R.string.attachments_camera_button)).performClick()
        compose.waitForIdle()
        val permission = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("Tapping the camera must request permission", permission)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", permission.intent.action)
        compose.runOnIdle {
            shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
            compose.activity.onRequestPermissionsResult(permission.requestCode,
                arrayOf(Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_GRANTED))
        }
        compose.waitForIdle()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull(compose.onRoot().printToString(), request)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        @Suppress("DEPRECATION")
        val uri = request.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        assertEquals("content", uri.scheme)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            request.intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals(uri, request.intent.clipData!!.getItemAt(0).uri)
        val bitmap = Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888)
        compose.activity.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent()) }
        compose.waitUntil(5_000) { connection.uploads.isNotEmpty() }
        compose.waitForIdle()
        val upload = connection.uploads.single()
        assertEquals("t1", upload.first)
        val decoded = BitmapFactory.decodeByteArray(upload.third, 0, upload.third.size)
        assertEquals(2048, decoded.width)
        assertEquals(682, decoded.height)
        decoded.recycle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        captureAttachmentScreenshot(compose, "camera-draft")
        assertTrue(connection.typed.isEmpty())
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to "'/host/it'\"'\"'s file.jpg'"), connection.typed)
        assertTrue("temporary capture removed", File(compose.activity.cacheDir, "attachments").listFiles().orEmpty().isEmpty())
    }
}
