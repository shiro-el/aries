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

    // D117: delayed discovery errors must offer recovery, and Retry must run a fresh scan.
    @Test fun hostDiscoveryDistinguishesPendingFailureAndSuccessfulEmptyScan() {
        val scan = kotlinx.coroutines.CompletableDeferred<List<UShort>>()
        var scans = 0
        val conn = object : HostConnection(NoHandle) {
            override suspend fun previewListeningPorts(): List<UShort> {
                scans++
                return if (scans == 1) scan.await() else emptyList()
            }
        }
        val host = Host("h1", "home", Endpoint.Ssh("home.test", 22u, "me"), null)
        val scope = CoroutineScope(Dispatchers.Main)
        val browser = HostBrowser(host).apply {
            link = HostLink(host, conn, scope, { conn })
            updateTerminals(listOf(TerminalInfo("t1", "Build", "", listOf("sh"), "/repo", "live")))
        }
        compose.setContent { MaterialTheme { TasksHostContent(remember { HostsModel(FakeCore(), scope) }, browser) } }
        compose.onNodeWithTag("tasks-host-preview").performClick()
        val empty = compose.activity.getString(R.string.preview_no_servers)
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle { scan.completeExceptionally(CoreException.Rpc("preview_discovery", "tools unavailable")) }
        compose.onNodeWithText(compose.activity.getString(R.string.error_connection)).assertExists()
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.preview_retry)).performClick()
        compose.onNodeWithText(empty).assertExists()
        compose.runOnIdle { assertEquals(2, scans) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // D54: losing the connection during a delayed scan must cancel it and
    // replace the searching indicator with a disconnected state until reconnect.
    @Test fun connectionLossCancelsDiscoveryAndReconnectScansAgain() {
        val pending = kotlinx.coroutines.CompletableDeferred<List<UShort>>()
        val replacement = kotlinx.coroutines.CompletableDeferred<HostConnection>()
        var cancelled = false
        val first = object : HostConnection(NoHandle) {
            override suspend fun disconnect() {}
            override suspend fun previewListeningPorts(): List<UShort> = try { pending.await() }
                finally { cancelled = true }
        }
        val next = object : HostConnection(NoHandle) {
            override suspend fun previewListeningPorts() = listOf<UShort>(5173u)
        }
        val host = Host("h1", "home", Endpoint.Ssh("home.test", 22u, "me"), null)
        val scope = CoroutineScope(Dispatchers.Main)
        val link = HostLink(host, first, scope, { replacement.await() })
        lateinit var preview: LocalhostPreview
        compose.setContent { MaterialTheme {
            preview = rememberLocalhostPreview(link, "", "t1")
            PreviewDiscoveryNotice(preview) { link.retryNow(); preview.retryDiscovery() }
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.preview_searching)).assertExists()
        compose.runOnIdle { link.lost(first) }
        compose.onNodeWithText(compose.activity.getString(R.string.preview_searching)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.error_connection)).assertExists()
        compose.runOnIdle {
            assertTrue(cancelled)
            pending.complete(listOf(3000u))
            assertTrue(preview.ports.isEmpty())
            replacement.complete(next)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf<UShort>(5173u), preview.ports) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // D52: reopening the tunnel after rotation must restore the visited path and display mode.
    @Test fun previewRecreationRestoresTheVisitedPathOnTheNewTunnel() {
        var forwards = 0
        val conn = object : HostConnection(NoHandle) {
            override suspend fun openPreview(port: UShort) = object : PreviewForward(NoHandle) {
                val origin = "http://127.0.0.1:${49152 + forwards++}/"
                override fun url() = origin
                override suspend fun stop() {}
            }
        }
        val host = Host("h1", "home", Endpoint.Ssh("home.test", 22u, "me"), null)
        val scope = CoroutineScope(Dispatchers.Main)
        val link = HostLink(host, conn, scope, { conn })
        val preview = LocalhostPreview().apply { selected = 5173u }
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { LocalhostPreviewBrowser(preview, link, Modifier.fillMaxSize()) } }
        compose.onNodeWithText(compose.activity.getString(R.string.preview_desktop)).performClick()
        compose.runOnIdle {
            val web = findWebView(compose.activity.window.decorView)!!
            val url = "http://127.0.0.1:49152/docs?tab=api#methods"
            web.loadUrl(url)
            web.webViewClient.onPageStarted(web, url, null)
            web.webViewClient.onPageFinished(web, url)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("http://127.0.0.1:49153/docs?tab=api#methods", findWebView(compose.activity.window.decorView)!!.url)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.preview_mobile)).assertExists()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // Regression: streaming output must not rebuild/scan every frame on Main or miss the final server hint.
    @Test fun terminalOutputScanningCoalescesFramesOffMainAndKeepsTheLatestHint() {
        val scans = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        val conn = object : HostConnection(NoHandle) {
            override suspend fun previewListeningPorts(): List<UShort> = emptyList()
            override fun previewPorts(output: String): List<UShort> {
                scans += output to (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                return if (output.contains("latest")) listOf(5173u) else emptyList()
            }
        }
        val host = FakeCore().addHost("fixture", Endpoint.Ssh("host", 22u, "me"))
        val link = HostLink(host, conn, CoroutineScope(Dispatchers.Main), { conn })
        val viewer = TerminalViewer(TerminalInfo("t1", "build", "", listOf("sh"), "/repo", "live")).apply { this.link = link }
        compose.setContent { MaterialTheme { TerminalScreen(viewer, {}) } }
        compose.mainClock.autoAdvance = false
        repeat(20) { frame ->
            val text = if (frame == 19) "latest http://localhost:5173" else "output $frame"
            compose.runOnIdle {
                viewer.apply(ScreenFrame(40u, 4u, true, listOf(ScreenLine(0u, listOf(
                    CellRun(0u, text.length.toUShort(), text, false, null, null, false, false, false, false, false, false),
                ))), null, null))
            }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitUntil(5_000) { scans.lastOrNull()?.first?.contains("latest") == true }
        assertTrue("coalesce the burst instead of scanning all 20 frames: ${scans.size}", scans.size <= 3)
        assertTrue("the synchronous core parser must run off Main", scans.none { it.second })
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.preview_title)).assertExists()
    }

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

    // Regression: reconnecting must preserve an in-progress capture and the user's note.
    @Test fun reconnectKeepsCaptureEditorAndNote() {
        val conn = object : HostConnection(NoHandle) {
            override suspend fun disconnect() {}
            override suspend fun openPreview(port: UShort) = object : PreviewForward(NoHandle) {
                override fun url() = "http://127.0.0.1:49152/"
                override suspend fun stop() {}
            }
        }
        val host = FakeCore().addHost("fixture", Endpoint.Ssh("host", 22u, "me"))
        val link = HostLink(host, conn, CoroutineScope(Dispatchers.Main), { conn })
        val preview = LocalhostPreview().apply { selected = 5173u }
        var generation by mutableIntStateOf(0)
        compose.setContent { generation; MaterialTheme { LocalhostPreviewBrowser(preview, link, Modifier.fillMaxSize(), onAttachCapture = { _, _ -> true }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Capture").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { click ->
            val web = findWebView(compose.activity.window.decorView)!!
            web.right = web.left + web.measuredWidth
            web.bottom = web.top + web.measuredHeight
            click()
        }
        compose.onNodeWithText("Short note").performTextInput("Keep my note")
        compose.runOnIdle { link.pause(); generation++ }
        compose.onNodeWithText("Keep my note").assertExists()
        compose.runOnIdle { link.resume() }
        compose.onNodeWithText("Keep my note").assertExists()
    }

    // Regression: stopped candidates must disappear and later servers must be found without new output.
    @Test fun portCandidatesRefreshOnOpenAndWhileVisible() {
        var listening = listOf<UShort>(5173u)
        val conn = object : HostConnection(NoHandle) {
            override suspend fun previewListeningPorts() = listening
        }
        val host = FakeCore().addHost("fixture", Endpoint.Ssh("host", 22u, "me"))
        val link = HostLink(host, conn, CoroutineScope(Dispatchers.Main), { conn })
        lateinit var preview: LocalhostPreview
        compose.setContent { MaterialTheme {
            preview = rememberLocalhostPreview(link, "", "t1")
            PreviewChips(preview)
        } }
        compose.runOnIdle { assertEquals(listOf<UShort>(5173u), preview.ports); listening = listOf(3000u); preview.selected = 5173u }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf<UShort>(3000u), preview.ports); listening = listOf(8080u) }
        compose.mainClock.advanceTimeBy(30_100)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf<UShort>(8080u), preview.ports) }
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
        // Regression: STOP/START must retain the WebView object and its page state.
        compose.runOnIdle { assertSame(web, findWebView(compose.activity.window.decorView)) }
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
