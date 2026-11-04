package io.github.shiroel.aries.ui

import android.webkit.WebResourceError
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.PreviewForward
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Owned by the Agent screen model; the WebView and tunnel remain composition-local. */
@Stable
internal class LocalhostPreview {
    var ports by mutableStateOf<List<UShort>>(emptyList())
    private var selectedPort by mutableStateOf<UShort?>(null)
    var selected: UShort?
        get() = selectedPort
        set(value) {
            if (value != selectedPort) capture = null
            selectedPort = value
        }
    var desktop by mutableStateOf(false)
    var autoRefresh by mutableStateOf(false)
    // Pixels and annotations stay in memory, never in saved-instance-state Bundles.
    var capture by mutableStateOf<PreviewCapture?>(null)
}

@Composable
internal fun rememberLocalhostPreview(
    link: HostLink?, output: String, address: String,
    preview: LocalhostPreview = remember(link, address) { LocalhostPreview() },
): LocalhostPreview {
    val enabled = link?.host?.endpoint is Endpoint.Ssh && !address.contains('/')
    val connection = link?.connections?.collectAsState()?.value
    LaunchedEffect(preview, enabled, connection, output) {
        if (enabled && connection != null && output.isNotEmpty()) {
            preview.ports = (connection.previewPorts(output) + preview.ports).distinct().take(24)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(preview, enabled, connection, preview.selected != null, lifecycle) {
        if (enabled && connection != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                // A successful scan replaces stale hints, including stopped servers.
                // Missing machine.exec/tools leaves transcript-derived candidates available.
                try { preview.ports = connection.previewListeningPorts().distinct().take(24) }
                catch (_: io.github.shiroel.aries.core.CoreException) { }
                delay(30_000)
            } while (true)
        }
    }
    return preview
}

/** Parse changed entries independently; never concatenate or rescan the transcript per token. */
@Composable
internal fun rememberChatLocalhostPreview(
    link: HostLink?, entries: List<ChatEntry>, address: String,
    state: LocalhostPreview = remember(link, address) { LocalhostPreview() },
): LocalhostPreview {
    val preview = rememberLocalhostPreview(link, "", address, state)
    val parsed = remember(preview) { mutableMapOf<String, String>() }
    val connection = link?.connections?.collectAsState()?.value
    val latestEntries by rememberUpdatedState(entries)
    LaunchedEffect(preview, connection, address) {
        if (link?.host?.endpoint !is Endpoint.Ssh || address.contains('/') || connection == null) return@LaunchedEffect
        snapshotFlow { latestEntries }.collect { batch ->
            val recent = batch.takeLast(100)
            parsed.keys.retainAll(recent.mapTo(mutableSetOf()) { it.id })
            for (entry in recent) {
                val body = entry.body
                val output = if (body is ChatEntryBody.Tool) body.output else body.markdownSource()
                if (output != null && parsed[entry.id] != output) {
                    val ports = withContext(kotlinx.coroutines.Dispatchers.Default) { connection.previewPorts(output) }
                    parsed[entry.id] = output
                    preview.ports = (ports + preview.ports).distinct().take(24)
                }
            }
        }
    }
    return preview
}

/** Detected servers remain available when output scrolls away. */
@Composable
internal fun PreviewChips(preview: LocalhostPreview) {
    if (preview.selected == null || preview.ports.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        preview.ports.forEach { port ->
            FilterChip(selected = preview.selected == port,
                onClick = { preview.selected = if (preview.selected == port) null else port },
                label = { Text(stringResource(R.string.preview_open_port, port.toInt())) })
        }
    }
}

/** The tunnel follows STARTED; the browser and frozen capture live until the preview closes. */
@Composable
internal fun LocalhostPreviewBrowser(
    preview: LocalhostPreview,
    link: HostLink?,
    modifier: Modifier = Modifier,
    change: Any? = null,
    captureEnabled: Boolean = true,
    onAttachCapture: ((SharedContent, () -> Unit) -> Boolean)? = null,
) {
    val port = preview.selected ?: return
    BackHandler { preview.selected = null }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val connection = link?.connections?.collectAsState()?.value
    var forward by remember(port, connection) { mutableStateOf<PreviewForward?>(null) }
    var failed by remember(port, connection) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var capture by preview::capture
    var captureFailed by remember(port) { mutableStateOf(false) }
    var origin by remember(port) { mutableStateOf<String?>(null) }
    LaunchedEffect(lifecycle, port, connection, retry) {
        if (connection == null) return@LaunchedEffect
        // Cancel directly on STOP; a stopped Activity may no longer recompose.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            failed = false
            var opened: PreviewForward? = null
            try {
                opened = withTimeout(10_000) { connection.openPreview(port) }
                origin = opened.url()
                forward = opened
                kotlinx.coroutines.awaitCancellation()
            } catch (_: TimeoutCancellationException) {
                failed = true
            } catch (_: io.github.shiroel.aries.core.CoreException) {
                failed = true
            } finally {
                forward = null
                withContext(NonCancellable) { opened?.stop() }
            }
        }
    }
    Column(modifier) {
        var webView by remember { mutableStateOf<WebView?>(null) }
        var previousChange by remember { mutableStateOf(change) }
        LaunchedEffect(change) {
            if (change != previousChange && preview.autoRefresh) webView?.reload()
            previousChange = change
        }
        link?.let { ConnectionNotice(it) }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preview_address, port.toInt()), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { preview.selected = null }) { Text(stringResource(R.string.preview_close)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { webView?.reload() }, enabled = forward != null) { Text(stringResource(R.string.preview_reload)) }
            TextButton(onClick = { preview.desktop = !preview.desktop }) {
                Text(stringResource(if (preview.desktop) R.string.preview_mobile else R.string.preview_desktop))
            }
            if (onAttachCapture != null) {
                TextButton(enabled = captureEnabled && webView != null && forward != null, onClick = {
                    captureFailed = false
                    try {
                        val view = checkNotNull(webView)
                        val origin = checkNotNull(forward).url()
                        val url = view.url ?: origin
                        check(previewOriginAllows(origin, url))
                        check(view.width > 0 && view.height > 0)
                        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        try { view.draw(android.graphics.Canvas(bitmap)) }
                        catch (e: Exception) { bitmap.recycle(); throw e }
                        val page = java.net.URI(url)
                        // The Agent cannot reach the phone's ephemeral tunnel port.
                        val hostUrl = "http://127.0.0.1:$port" + page.rawPath.orEmpty().ifEmpty { "/" } +
                            (page.rawQuery?.let { "?$it" } ?: "") + (page.rawFragment?.let { "#$it" } ?: "")
                        capture = PreviewCapture(bitmap, hostUrl, preview.desktop)
                    } catch (_: Exception) { captureFailed = true }
                }) { Text(stringResource(R.string.preview_capture_button)) }
            }
        }
        if (captureFailed) Text(stringResource(R.string.preview_capture_failed), Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.error)
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.preview_auto_refresh), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(checked = preview.autoRefresh, onCheckedChange = { preview.autoRefresh = it })
        }
        val currentOrigin = origin
        if (currentOrigin != null) {
            key(port) {
                RestrictedPreviewWebView(currentOrigin, preview.desktop, Modifier.fillMaxWidth().weight(1f),
                    active = started && forward != null, forward = forward,
                    onReconnect = { retry++ }, onView = { webView = it })
            }
        }
        if (failed && connection != null) {
            Text(stringResource(R.string.preview_failed), Modifier.padding(12.dp))
            TextButton(onClick = { link.retryNow(); retry++ }) { Text(stringResource(R.string.preview_retry)) }
        } else if (started && forward == null && connection != null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.preview_loading), Modifier.padding(top = 12.dp))
            }
        }
    }
    capture?.let { image ->
        if (onAttachCapture != null) PreviewCaptureEditor(image, onDismiss = { capture = null }, onAttach = onAttachCapture)
    }
}

/** Exact scheme/host/port match; paths and fragments are allowed within that origin. */
internal fun previewOriginAllows(origin: String, candidate: String): Boolean = try {
    val base = java.net.URI(origin)
    val target = java.net.URI(candidate)
    target.scheme == base.scheme && target.host == base.host && target.port == base.port && target.rawUserInfo == null
} catch (_: Exception) { false }

@Composable
private fun RestrictedPreviewWebView(
    origin: String, desktop: Boolean, modifier: Modifier, active: Boolean, forward: PreviewForward?,
    onReconnect: () -> Unit, onView: (WebView?) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var state by remember { mutableStateOf(PreviewPageState.LOADING) }
    var status by remember { mutableStateOf<Int?>(null) }
    var navigation by remember { mutableIntStateOf(0) }
    var currentView by remember { mutableStateOf<WebView?>(null) }
    val currentOrigin by rememberUpdatedState(origin)
    val reconnect by rememberUpdatedState(onReconnect)
    val isActive by rememberUpdatedState(active)
    var loadedOrigin by remember { mutableStateOf(origin) }
    var reconnectAttempted by remember { mutableStateOf(false) }
    var restoreScroll by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun loading() { status = null; state = PreviewPageState.LOADING; navigation++ }
    LaunchedEffect(origin, state, navigation, active) {
        if (active && state == PreviewPageState.LOADING) {
            delay(10_000)
            state = PreviewPageState.TIMEOUT
            currentView?.stopLoading()
        }
    }
    LaunchedEffect(forward) {
        // A reopened tunnel can reuse its address; recover the retained error page too.
        if (forward != null && state in setOf(PreviewPageState.FAILED, PreviewPageState.TIMEOUT)) {
            loading()
            currentView?.reload()
        }
    }
    // Native WebView drawing must stay below the preview title and close button.
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant)) {
        AndroidView(modifier = Modifier.fillMaxSize().clipToBounds(), factory = {
            WebView(it).apply {
                setBackgroundColor(android.graphics.Color.WHITE)
                clipToOutline = true
                currentView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // No addJavascriptInterface, file chooser, download handler or permission grants.
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading() }
                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        if (state == PreviewPageState.LOADING) { state = PreviewPageState.READY; reconnectAttempted = false }
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (state == PreviewPageState.LOADING) state = PreviewPageState.READY
                        restoreScroll?.let { (x, y) -> view.scrollTo(x, y); restoreScroll = null }
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            if (!isActive) return
                            state = if (error.errorCode == ERROR_TIMEOUT) PreviewPageState.TIMEOUT else PreviewPageState.FAILED
                            if (!reconnectAttempted && error.errorCode in listOf(ERROR_CONNECT, ERROR_IO)) {
                                reconnectAttempted = true
                                reconnect()
                            }
                        }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) { status = response.statusCode; state = PreviewPageState.FAILED }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (previewOriginAllows(currentOrigin, url)) return false
                        if (request.isForMainFrame && request.hasGesture() && request.url.scheme in listOf("http", "https")) {
                            uriHandler.openUri(url)
                        }
                        return true
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (previewOriginAllows(currentOrigin, request.url.toString())) null
                        else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                loadUrl(origin)
                onView(this)
            }
        }, update = { view ->
            if (loadedOrigin != origin) {
                val previous = view.url
                val suffix = previous?.takeIf { previewOriginAllows(loadedOrigin, it) }?.removePrefix(loadedOrigin.trimEnd('/')) ?: "/"
                restoreScroll = view.scrollX to view.scrollY
                loadedOrigin = origin
                loading()
                view.loadUrl(origin.trimEnd('/') + suffix)
            }
            val default = WebSettings.getDefaultUserAgent(context)
            val agent = if (desktop) default.replace("; wv", "").replace("Mobile", "").replace("Android", "X11; Linux x86_64") else default
            if (view.settings.userAgentString != agent) {
                view.settings.userAgentString = agent
                view.settings.useWideViewPort = desktop
                view.settings.loadWithOverviewMode = desktop
                view.reload()
            }
        }, onRelease = { view ->
            onView(null)
            currentView = null
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        })
        if (active && state != PreviewPageState.READY) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state == PreviewPageState.LOADING) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.preview_loading), Modifier.padding(top = 12.dp))
                    } else {
                        val message = when {
                            state == PreviewPageState.TIMEOUT -> stringResource(R.string.preview_timeout)
                            status != null -> stringResource(R.string.preview_http_failed, status!!)
                            else -> stringResource(R.string.preview_page_failed)
                        }
                        Text(message)
                        TextButton(onClick = { reconnectAttempted = false; loading(); currentView?.reload(); reconnect() }) { Text(stringResource(R.string.preview_retry)) }
                    }
                }
            }
        }
    }
}

private enum class PreviewPageState { LOADING, READY, FAILED, TIMEOUT }

