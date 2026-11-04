package io.github.shiroel.aries.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint

/** Chat and Terminal share the same phone/Host routing and visible failure handling. */
@Composable
internal fun rememberAgentUriHandler(preview: LocalhostPreview, link: HostLink?, address: String): UriHandler {
    val browser = LocalUriHandler.current
    val context = LocalContext.current
    val canPreview = link?.host?.endpoint is Endpoint.Ssh && !address.contains('/')
    return remember(browser, context, preview, canPreview) {
        object : UriHandler {
            override fun openUri(uri: String) {
                try {
                    val parsed = android.net.Uri.parse(uri)
                    val host = parsed.host?.lowercase()?.removeSurrounding("[", "]")?.trimEnd('.')
                    val loopback = host in setOf("localhost", "::1", "0.0.0.0") ||
                        host?.matches(loopbackIpv4) == true
                    if (loopback) {
                        require(canPreview && parsed.scheme?.lowercase() in listOf("http", "https"))
                        val port = (if (parsed.port == -1) {
                            if (parsed.scheme.equals("https", ignoreCase = true)) 443 else 80
                        } else parsed.port).also { require(it in 1..65535) }.toUShort()
                        // Use the existing preview entry point; its owner manages loading and errors.
                        preview.ports = (listOf(port) + preview.ports).distinct().take(24)
                        preview.selected = port
                    } else browser.openUri(uri)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.chat_link_open_failed, Toast.LENGTH_SHORT).show()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, R.string.chat_link_open_failed, Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, R.string.chat_link_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private val loopbackIpv4 = Regex("127(?:\\.[0-9]{1,3}){3}")
