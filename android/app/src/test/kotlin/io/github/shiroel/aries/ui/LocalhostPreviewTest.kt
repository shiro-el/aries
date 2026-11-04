package io.github.shiroel.aries.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import org.robolectric.annotation.GraphicsMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI/core seam: preview ownership must follow visibility rather than the Host connection. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalhostPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Regression: a forwarded SSH banner must offer recovery instead of a blank WebView. */
    @Test fun nonHttpMainFrameErrorShowsRetryAndCloseAndRetryLoadsAgain() {
        val connection = object : HostConnection(NoHandle) {
            override suspend fun openPreview(port: UShort) = object : PreviewForward(NoHandle) {
                override fun url() = "http://127.0.0.1:49152/"
                override suspend fun stop() {}
            }
        }
        val host = FakeCore().addHost("fixture", Endpoint.Ssh("host", 22u, "me"))
        val link = HostLink(host, connection, CoroutineScope(Dispatchers.Main), { connection })
        val preview = LocalhostPreview().apply { selected = 22u }
        compose.setContent { MaterialTheme { LocalhostPreviewBrowser(preview, link, Modifier.fillMaxSize()) } }
        compose.waitForIdle()
        val web = compose.runOnIdle { findWebView(compose.activity.window.decorView)!! }
        compose.runOnIdle {
            val request = object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse("http://127.0.0.1:49152/")
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
            val error = android.webkit.PreviewWebResourceError()
            web.webViewClient.onReceivedError(web, request, error)
            // WebView finishes failed navigations too; that must not erase the error.
            web.webViewClient.onPageFinished(web, request.url.toString())
        }
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Close preview").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(preview.selected) }
    }

    private fun findWebView(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
            findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Regression: a silent TCP service sends no WebView error and must still time out. */
    @Test fun unresponsivePageTimesOutAndStillOffersRetryAndClose() {
        val connection = object : HostConnection(NoHandle) {
            override suspend fun openPreview(port: UShort) = object : PreviewForward(NoHandle) {
                override fun url() = "http://127.0.0.1:49152/"
                override suspend fun stop() {}
            }
        }
        val host = FakeCore().addHost("fixture", Endpoint.Ssh("host", 22u, "me"))
        val link = HostLink(host, connection, CoroutineScope(Dispatchers.Main), { connection })
        val preview = LocalhostPreview().apply { selected = 5173u }
        compose.setContent { MaterialTheme { LocalhostPreviewBrowser(preview, link, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("Loading preview…").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_100)
        compose.onNodeWithText("The preview did not respond within 10 seconds. Check the development server and try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Close preview").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(preview.selected) }
    }

    /** Regression: detected ports must not reserve a transcript row until preview is opened. */
    @Test fun portChipsFollowPreviewVisibilityAndStillSwitchPorts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preview = LocalhostPreview().apply { ports = listOf(5173u, 3000u) }
        compose.setContent { MaterialTheme { Column {
            AgentScreenHeader(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working),
                "home", {}, AgentView.CHAT, {}, preview = preview)
            PreviewChips(preview)
        } } }
        val first = context.getString(R.string.preview_open_port, 5173)
        val second = context.getString(R.string.preview_open_port, 3000)
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.preview_title)).performClick()
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.onNodeWithText(second).performClick()
        compose.runOnIdle { assertEquals(3000.toUShort(), preview.selected) }
        compose.onNodeWithContentDescription(context.getString(R.string.preview_title)).performClick()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertDoesNotExist()
    }

    // Regression: Host loopback links must select the Aries preview rather than launch the phone browser.
    @Test fun chatLinksRouteHostPortsToPreviewAndReportBrowserFailures() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val core = FakeCore()
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        val connection = object : HostConnection(NoHandle) {}
        val link = HostLink(host, connection, CoroutineScope(Dispatchers.Main), { connection })
        val preview = LocalhostPreview()
        val opened = mutableListOf<String>()
        val browser = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                opened += uri
                throw android.content.ActivityNotFoundException()
            }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides browser) {
                    val handler = rememberAgentUriHandler(preview, link, "t1")
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides handler) {
                        Column {
                            ChatFileText("http://localhost:5173/path")
                            ChatFileText("https://127.0.0.1:3000/")
                            ChatFileText("https://example.org/docs")
                            PreviewChips(preview)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("http://localhost:5173/path").performFirstLinkClick()
        compose.onNodeWithText(context.getString(R.string.preview_open_port, 5173)).assertIsDisplayed()
        compose.onNodeWithText("https://127.0.0.1:3000/").performFirstLinkClick()
        compose.onNodeWithText(context.getString(R.string.preview_open_port, 3000)).assertIsDisplayed()
        assertTrue(opened.isEmpty())
        compose.onNodeWithText("https://example.org/docs").performFirstLinkClick()
        assertEquals(listOf("https://example.org/docs"), opened)
        assertEquals(context.getString(R.string.chat_link_open_failed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun backgroundingAndLeavingCloseTheForwardWithoutDisconnectingTheHost() {
        var opened = 0
        var closed = 0
        var disconnected = false
        val connection = object : HostConnection(NoHandle) {
            override suspend fun openPreview(port: UShort): PreviewForward {
                assertEquals(5173.toUShort(), port)
                opened++
                return object : PreviewForward(NoHandle) {
                    override fun url() = "http://127.0.0.1:49152/"
                    override suspend fun stop() { closed++ }
                }
            }
            override suspend fun disconnect() { disconnected = true }
        }
        val host = FakeCore().addHost("home", Endpoint.Ssh("host", 22u, "me"))
        val link = HostLink(host, connection, CoroutineScope(Dispatchers.Main), { connection })
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        var visible by mutableStateOf(true)
        val preview = LocalhostPreview().apply { selected = 5173u }
        assertFalse(preview.autoRefresh)
        // Regression: WebView gestures must use the same failure-reporting browser boundary as Chat.
        val openedUris = mutableListOf<String>()
        val unavailableBrowser = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
                throw android.content.ActivityNotFoundException()
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner,
                androidx.compose.ui.platform.LocalUriHandler provides unavailableBrowser) {
                val handler = rememberAgentUriHandler(preview, link, "t1")
                CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides handler) {
                    MaterialTheme {
                        if (visible) LocalhostPreviewBrowser(preview, link, Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitUntil { opened == 1 }
        compose.waitForIdle()
        val screenshot = java.io.File("build/reports/preview/controls.png")
        screenshot.parentFile?.mkdirs()
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val web = compose.runOnIdle {
            fun find(view: android.view.View): android.webkit.WebView? {
                if (view is android.webkit.WebView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            find(compose.activity.window.decorView)!!
        }
        val client = compose.runOnIdle { web.webViewClient }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil { closed == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { opened == 2 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.waitUntil { closed == 2 }
        compose.runOnIdle {
            fun request(url: String) = object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse(url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
            assertFalse(client.shouldOverrideUrlLoading(web, request("http://127.0.0.1:49152/page")))
            for (url in listOf("file:///etc/passwd", "content://private/file", "http://127.0.0.1:49153/", "http://127.0.0.1.evil:49152/")) {
                assertTrue(url, client.shouldOverrideUrlLoading(web, request(url)))
                assertEquals(403, client.shouldInterceptRequest(web, request(url))!!.statusCode)
            }
            assertTrue(client.shouldOverrideUrlLoading(web, request("https://example.org/docs")))
            assertEquals(listOf("http://127.0.0.1.evil:49152/", "https://example.org/docs"), openedUris)
            assertEquals(compose.activity.getString(R.string.chat_link_open_failed),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        }
        assertFalse(disconnected)
    }
}
