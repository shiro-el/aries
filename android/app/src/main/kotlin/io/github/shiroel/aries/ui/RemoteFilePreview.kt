package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.HostConnection
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*

internal enum class RemoteMediaKind { IMAGE, PDF }

internal fun remoteMediaKind(path: String): RemoteMediaKind? =
    when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png", "jpg", "jpeg", "gif", "webp", "svg" -> RemoteMediaKind.IMAGE
        "pdf" -> RemoteMediaKind.PDF
        else -> null
    }

/** Owns the bounded transfer and removes even partial previews on navigation or cancellation. */
@Composable
internal fun RemoteFilePreview(connection: HostConnection?, address: String, path: String, modifier: Modifier = Modifier, preview: FilePreview? = null) {
    val context = LocalContext.current
    var localFile by remember { mutableStateOf<File?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tooLarge by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(true) }
    val pdf = remoteMediaKind(path) == RemoteMediaKind.PDF
    val limitMiB = if (pdf) 64L else 16L
    LaunchedEffect(connection, address, path, preview) {
        var temporary: File? = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                if (preview != null && !pdf) {
                    if (preview.bytes.size > limitMiB * 1024 * 1024) throw ExportTooLarge()
                    return@withContext preview.bytes
                }
                val destination = File.createTempFile("remote-preview-", ".bin", context.cacheDir)
                temporary = destination
                downloadExport(checkNotNull(connection), address, path, destination, limitMiB * 1024 * 1024)
                if (pdf) null else destination.readBytes()
            }
            bytes = loaded
            localFile = temporary
            loading = false
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ExportTooLarge) {
            tooLarge = true
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
            withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
        }
    }
    Column(modifier) {
        when {
            loading -> Text(stringResource(R.string.files_loading))
            tooLarge -> Text(stringResource(R.string.files_preview_too_large, limitMiB), color = MaterialTheme.colorScheme.error)
            failed -> Text(stringResource(R.string.files_preview_failed), color = MaterialTheme.colorScheme.error)
            pdf -> localFile?.let { PdfPreview(it) }
            else -> TextButton(onClick = { open = true }) { Text(stringResource(R.string.files_preview_open)) }
        }
    }
    if (open) bytes?.let { ImageViewer(it, onDismiss = { open = false }, name = path, mediaType = preview?.mediaType) }
}
