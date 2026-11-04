package io.github.shiroel.aries.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/** System picker/camera results cross the same UI -> fake core seam as incoming shares. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "h740dp") // Normal phone portrait; short windows use the tools dialog.
class UploadNoticeLifetimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun str(id: Int) = compose.activity.getString(id)

    private lateinit var model: HostsModel

    private fun openChat(): FakeConnection {
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle, false, 0uL))
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        return core.connection!!
    }

    // D14: a pending photo must survive leaving Chat, including its source file.
    @Test fun cameraUploadSurvivesTerminalToggle() = cameraUploadSurvivesLeaving(back = false)
    @Test fun cameraUploadSurvivesBack() = cameraUploadSurvivesLeaving(back = true)
    @Test fun cameraUploadSurvivesLeavingHost() = cameraUploadSurvivesLeaving(back = true, leaveHost = true)
    @Test fun canceledCameraUploadKeepsItsSourceAndExplainsWhichFileWasLeftOut() = cameraUploadSurvivesLeaving(back = false, cancel = true)

    private fun cameraUploadSurvivesLeaving(back: Boolean, cancel: Boolean = false, leaveHost: Boolean = false) {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.CAMERA)
        val connection = openChat()
        // Recreate the provider like Android does: Robolectric changes cacheDir per test,
        // while FileProvider's authority cache otherwise retains the previous test's root.
        val info = compose.activity.packageManager.resolveContentProvider(
            "${compose.activity.packageName}.attachments", PackageManager.GET_META_DATA)!!
        androidx.core.content.FileProvider().attachInfo(compose.activity, info)
        connection.uploadHold = CompletableDeferred()
        val existing = File(compose.activity.cacheDir, "attachments").listFiles().orEmpty().toSet()
        compose.onNodeWithContentDescription(str(R.string.attachments_camera_button)).performClick()
        compose.waitForIdle()
        val permission = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("Camera must request permission", permission)
        compose.runOnIdle {
            shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
            compose.activity.onRequestPermissionsResult(permission.requestCode,
                arrayOf(Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_GRANTED))
        }
        compose.waitForIdle()
        val request = run {
            var launched = shadowOf(compose.activity).nextStartedActivityForResult
            compose.waitUntil(5_000) {
                if (launched == null) launched = shadowOf(compose.activity).nextStartedActivityForResult
                launched != null
            }
            checkNotNull(launched)
        }
        @Suppress("DEPRECATION")
        val uri = request.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        compose.activity.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val photo = File(compose.activity.cacheDir, "attachments").listFiles()!!.single { it !in existing }
        val chat = compose.runOnIdle { (model.screen as Screen.Chat).chat }
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent()) }
        compose.waitForIdle()
        if (cancel) {
            compose.onNodeWithText("Cancel upload").performClick()
            compose.waitUntil(5_000) { !chat.uploading }
            assertTrue("Canceled captures must remain on the phone", photo.exists())
            compose.onNodeWithText(photo.name).assertExists()
            connection.uploadHold!!.complete(Unit)
            compose.waitForIdle()
            assertTrue(chat.attachments.isEmpty())
            assertTrue(connection.uploads.isEmpty())
            photo.delete()
            return
        }
        compose.runOnIdle {
            if (leaveHost) model.back() else if (back) model.closeChat() else model.switchView(AgentView.TERMINAL)
        }
        compose.waitForIdle()
        assertTrue("Leaving Chat must keep the pending upload", chat.uploading)
        assertTrue("The unuploaded camera source must remain", photo.exists())
        if (leaveHost) assertFalse("Navigation must not disconnect an in-flight upload", connection.disconnected)
        connection.uploadHold!!.complete(Unit)
        compose.waitUntil(5_000) { chat.attachments.size == 1 && !chat.uploading }
        compose.waitForIdle()
        assertFalse(chat.uploading)
        assertEquals(listOf("t1"), connection.uploads.map { it.first })
        assertTrue(connection.typed.isEmpty())
    }

}
