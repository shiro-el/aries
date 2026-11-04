package io.github.shiroel.aries.ui

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.PreviewForward
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Local to the Agent screen: switching views drops its WebView and forward. */
@Stable
internal class LocalhostPreview {
    var ports by mutableStateOf<List<UShort>>(emptyList())
    var selected by mutableStateOf<UShort?>(null)
    var desktop by mutableStateOf(false)
    var autoRefresh by mutableStateOf(false)
}

@Composable
internal fun rememberLocalhostPreview(link: HostLink?, output: String, address: String): LocalhostPreview {
    val preview = remember(link) { LocalhostPreview() }
    val enabled = link?.host?.endpoint is Endpoint.Ssh && !address.contains('/')
    val connection = link?.connection
    LaunchedEffect(preview, enabled, connection, output) {
        if (enabled && connection != null && output.isNotEmpty()) {
            preview.ports = (preview.ports + connection.previewPorts(output)).distinct().take(24)
        }
    }
    LaunchedEffect(preview, enabled, connection) {
        if (enabled && connection != null) {
            // Missing tools and Hosts which forbid machine.exec quietly skip this source.
            try { preview.ports = (preview.ports + connection.previewListeningPorts()).distinct().take(24) }
            catch (_: io.github.shiroel.aries.core.CoreException) { }
        }
    }
    return preview
}

/** Detected servers remain available when output scrolls away. */
@Composable
internal fun PreviewChips(preview: LocalhostPreview) {
    if (preview.ports.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        preview.ports.forEach { port ->
            FilterChip(selected = preview.selected == port,
                onClick = { preview.selected = if (preview.selected == port) null else port },
                label = { Text(stringResource(R.string.preview_open_port, port.toInt())) })
        }
    }
}

/** The browser only exists while STARTED; disposal waits for tunnel cancellation. */
@Composable
internal fun LocalhostPreviewBrowser(preview: LocalhostPreview, link: HostLink?, modifier: Modifier = Modifier, change: Any? = null) {
    val port = preview.selected ?: return
    BackHandler { preview.selected = null }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var forward by remember(port, link?.connection) { mutableStateOf<PreviewForward?>(null) }
    var failed by remember(port, link?.connection) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val connection = link?.connection
    LaunchedEffect(lifecycle, port, connection, retry) {
        if (connection == null) return@LaunchedEffect
        // Cancel directly on STOP; a stopped Activity may no longer recompose.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            failed = false
            var opened: PreviewForward? = null
            try {
                opened = connection.openPreview(port)
                forward = opened
                kotlinx.coroutines.awaitCancellation()
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
        Text(stringResource(R.string.preview_address, port.toInt()), Modifier.padding(horizontal = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { webView?.reload() }, enabled = forward != null) { Text(stringResource(R.string.preview_reload)) }
            TextButton(onClick = { preview.desktop = !preview.desktop }) {
                Text(stringResource(if (preview.desktop) R.string.preview_mobile else R.string.preview_desktop))
            }
            TextButton(onClick = { preview.selected = null }) { Text(stringResource(R.string.preview_close)) }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.preview_auto_refresh), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(checked = preview.autoRefresh, onCheckedChange = { preview.autoRefresh = it })
        }
        val tunnel = forward
        if (started && tunnel != null) {
            key(tunnel) {
                RestrictedPreviewWebView(tunnel.url(), preview.desktop, Modifier.fillMaxWidth().weight(1f)) { webView = it }
            }
        } else if (failed || connection == null) {
            Text(stringResource(R.string.preview_failed), Modifier.padding(12.dp))
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.preview_retry)) }
        } else if (started) {
            CircularProgressIndicator(Modifier.padding(12.dp))
        }
    }
}

/** Exact scheme/host/port match; paths and fragments are allowed within that origin. */
internal fun previewOriginAllows(origin: String, candidate: String): Boolean = try {
    val base = java.net.URI(origin)
    val target = java.net.URI(candidate)
    target.scheme == base.scheme && target.host == base.host && target.port == base.port && target.rawUserInfo == null
} catch (_: Exception) { false }

@Composable
private fun RestrictedPreviewWebView(origin: String, desktop: Boolean, modifier: Modifier, onView: (WebView?) -> Unit) {
    val context = LocalContext.current
    AndroidView(modifier = modifier, factory = {
        WebView(it).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // No addJavascriptInterface, file chooser, download handler or permission grants.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (previewOriginAllows(origin, url)) return false
                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme in listOf("http", "https")) {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        catch (_: android.content.ActivityNotFoundException) { }
                    }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (previewOriginAllows(origin, request.url.toString())) null
                    else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            loadUrl(origin)
            onView(this)
        }
    }, update = { view ->
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
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.destroy()
    })
}
