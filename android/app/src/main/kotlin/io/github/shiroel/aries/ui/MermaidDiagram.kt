package io.github.shiroel.aries.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shared by fenced Chat/Markdown and standalone .mmd/.mermaid file previews. */
@Composable
internal fun MermaidDiagram(source: String, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    var failed by remember(source) { mutableStateOf(false) }
    val sharedDialogs = LocalChatDialogs.current
    val dialogs = sharedDialogs ?: remember { ChatDialogState() }
    if (failed) { fallback(); return }
    val context = LocalContext.current
    val label = stringResource(R.string.chat_mermaid_label)
    val copy = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, source))
        Unit
    }
    MermaidSurface(source, false, { dialogs.diagram = source }, copy, { failed = true },
        modifier.semantics {
            contentDescription = label
            onClick { dialogs.diagram = source; true }
            onLongClick { copy(); true }
        })
    if (sharedDialogs == null) ChatDialogs(dialogs)
}

@Composable
internal fun MermaidDialog(source: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(R.string.chat_mermaid_label)
    var failed by remember(source) { mutableStateOf(false) }
    val copy = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, source))
        Unit
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.back_button)) }
                if (failed) androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(source, Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
                } else MermaidSurface(source, true, {}, copy, { failed = true }, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

private fun Color.css(): String = String.format(Locale.ROOT, "#%06x", toArgb() and 0xffffff)

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun MermaidSurface(
    source: String, zoom: Boolean, onOpen: () -> Unit, onCopy: () -> Unit, onFailure: () -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    val theme = remember(colors) {
        JSONObject().apply {
            put("bg", colors.surface.css()); put("fg", colors.onSurface.css())
            put("muted", colors.onSurfaceVariant.css()); put("line", colors.onSurface.css())
            put("surface", colors.surfaceContainer.css()); put("surface-alt", colors.surfaceContainerHigh.css())
            put("border", colors.outline.css()); put("accent", colors.primary.css())
            for (index in 1..8) put("series-$index", listOf(colors.primary, colors.tertiary, colors.secondary)[(index - 1) % 3].css())
        }.toString()
    }
    val html by produceState<String?>(null, context) {
        value = withContext(Dispatchers.IO) {
            val bundle = context.assets.open("mermaid/pele-1.13.0.js").bufferedReader().use { it.readText() }
            val adapter = context.assets.open("mermaid/renderer.js").bufferedReader().use { it.readText() }
            """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'none'; connect-src 'none'; base-uri 'none'; form-action 'none'">
                <style>html,body{margin:0;width:100%;overflow:hidden}#diagram{width:100%}svg{display:block;width:100%;height:auto}#diagram.zoom svg{height:100%}</style>
                <script>$bundle</script><script>$adapter</script></head><body><div id="diagram"></div></body></html>"""
        }
    }
    var ratio by remember(source) { mutableFloatStateOf(1f) }
    var rendered by remember(source, theme) { mutableStateOf(false) }
    val failure by rememberUpdatedState(onFailure)
    val open by rememberUpdatedState(onOpen)
    val copy by rememberUpdatedState(onCopy)
    // A renderer crash or a missing callback must leave readable source, too.
    LaunchedEffect(source, theme) { delay(10_000); if (!rendered) failure() }
    val page = html ?: return
    key(source, theme, zoom) {
        AndroidView(
            modifier = if (zoom) modifier else modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.05f, 20f)),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(colors.surface.toArgb())
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        domStorageEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        builtInZoomControls = false
                    }
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setOnLongClickListener { copy(); true }
                    val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent) = true
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (!zoom) {
                                val density = resources.displayMetrics.density
                                evaluateJavascript("!!document.elementFromPoint(${e.x / density},${e.y / density})?.closest('a')") { link ->
                                    if (link != "true") open()
                                }
                            }
                            return false
                        }
                    }).apply { setIsLongpressEnabled(false) }
                    setOnTouchListener { _, event -> gestures.onTouchEvent(event); false }
                    webViewClient = object : WebViewClient() {
                        // The document and scripts arrive from app assets in memory. Nothing can load from a URL.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame && request.hasGesture() && request.url.scheme?.lowercase(Locale.ROOT) in setOf("https", "http", "mailto")) {
                                runCatching { uriHandler.openUri(request.url.toString()) }
                            }
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            val script = """(function(){
                                const theme=$theme; const el=document.getElementById('diagram');
                                for(const [key,value] of Object.entries(theme)) el.style.setProperty('--pele-'+key,value);
                                document.body.style.background=theme.bg;
                                if($zoom) { el.classList.add('zoom'); el.style.height='${view.height / resources.displayMetrics.density}px'; }
                                return ariesMermaidShow(${JSONObject.quote(source)},window.innerWidth,$zoom);
                            })()"""
                            view.evaluateJavascript(script) { result ->
                                val dimensions = runCatching { JSONObject(result) }.getOrNull()
                                val width = dimensions?.optDouble("width") ?: 0.0
                                val height = dimensions?.optDouble("height") ?: 0.0
                                if (width.isFinite() && height.isFinite() && width > 0 && height > 0) {
                                    ratio = (width / height).toFloat()
                                    rendered = true
                                } else failure()
                            }
                        }
                    }
                    loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}
