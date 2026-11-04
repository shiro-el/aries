package io.github.shiroel.aries.ui

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Captured page context must survive the editor, attachment upload and Chat send. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun desktopCaptureSendsItsPageContextAndNoteToItsAgent() = captureAndSend(desktop = true)
    @Test fun mobileCaptureSendsItsPageContextAndNoteToItsAgent() = captureAndSend(desktop = false)

    private fun captureAndSend(desktop: Boolean) {
        var visible by mutableStateOf(true)
        val core = FakeCore()
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        val terminal = TerminalInfo("t7", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val messages = mutableListOf<Pair<String, String>>()
        val connection = object : HostConnection(NoHandle) {
            override fun previewPorts(output: String) = emptyList<UShort>()
            override suspend fun previewListeningPorts() = listOf(5173.toUShort())
            override suspend fun openPreview(port: UShort) = object : PreviewForward(NoHandle) {
                override fun url() = "http://127.0.0.1:49152/"
                override suspend fun stop() { }
            }
            override suspend fun uploadAttachment(terminal: String, name: String, bytes: ByteArray): String {
                uploads += terminal to bytes
                return "/host/it's capture.jpg"
            }
            override suspend fun sendMessage(address: String, text: String, listener: SendListener): SendOutcome {
                messages += address to text
                return SendOutcome.Sent
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val link = remember { HostLink(host, connection, scope, { connection }) }
            val chat = remember { ChatModel(core, host, terminal, link).apply { start(scope) } }
            DisposableEffect(chat) { onDispose { chat.stop() } }
            MaterialTheme { if (visible) ChatScreen(chat, onBack = {}) }
        }
        compose.onNodeWithContentDescription("Preview").performClick()
        if (desktop) compose.onNodeWithText("Desktop view").performClick()
        lateinit var web: WebView
        compose.runOnIdle {
            fun find(view: android.view.View): WebView? {
                if (view is WebView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            web = find(compose.activity.window.decorView)!!
            web.loadUrl("http://127.0.0.1:49152/settings?tab=layout#panel")
        }
        compose.onNodeWithText("Capture").performSemanticsAction(SemanticsActions.OnClick) { click ->
            // The fake WebView provider does not implement setFrame. Apply the measured
            // viewport through View's bounds setters instead of the provider's no-op layout.
            web.right = web.left + web.measuredWidth
            web.bottom = web.top + web.measuredHeight
            click()
        }
        if (desktop) {
            val canvas = compose.onNodeWithTag("preview-capture-canvas")
            canvas.performTouchInput { swipe(Offset(width * .3f, height * .4f), Offset(width * .65f, height * .5f)) }
            compose.onNodeWithText("Rectangle").performClick()
            canvas.performTouchInput { swipe(Offset(width * .3f, height * .55f), Offset(width * .7f, height * .7f)) }
            screenshot("pen-and-rectangle")
            compose.onNodeWithText("Undo").performClick()
            screenshot("undo-rectangle")
            canvas.performTouchInput { swipe(Offset(width * .3f, height * .55f), Offset(width * .7f, height * .7f)) }
        }
        compose.onNodeWithText("Short note").performTextInput("Fix the spacing here")
        // Regression: lock removes Chat's composition; reopening must retain the
        // frozen capture, editable note and drawn annotations through upload.
        if (desktop) {
            compose.runOnIdle { visible = false }
            compose.runOnIdle { visible = true }
            compose.onNodeWithText("Fix the spacing here").assertExists()
            compose.onNodeWithText("Undo").assertIsEnabled()
        }
        screenshot(if (desktop) "desktop-note" else "mobile-note")
        compose.onNodeWithText("Add to Agent Chat").performClick()
        compose.waitUntil(10_000) { uploads.isNotEmpty() }
        compose.waitForIdle()
        assertTrue("Reviewing a capture must not type into the Agent", messages.isEmpty())
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(5_000) { messages.isNotEmpty() }
        assertEquals("t7", uploads.single().first)
        assertTrue(uploads.single().second.isNotEmpty())
        File("build/reports/preview-capture/${if (desktop) "desktop" else "mobile"}-attachment.jpg")
            .writeBytes(uploads.single().second)
        assertEquals("t7", messages.single().first)
        val text = messages.single().second
        assertTrue(text, text.contains("http://127.0.0.1:5173/settings?tab=layout#panel"))
        assertTrue(text, text.contains(if (desktop) "Desktop view" else "Mobile view"))
        assertTrue(text, text.contains("Fix the spacing here"))
        assertTrue(text, text.contains("'/host/it'\"'\"'s capture.jpg'"))
        assertFalse(text, text.contains(":49152"))
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/preview-capture/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
