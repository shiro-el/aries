package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.github.shiroel.aries.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

@Composable
internal fun ImageViewerExport(bytes: ByteArray, mime: String?, name: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var status by remember { mutableIntStateOf(0) }
    val filename = remember(name, mime) {
        val base = name.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl)
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "image"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if ('.' !in base && extension != null) "$base.$extension" else base
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime ?: "image/*")) { uri: Uri? ->
        picking = false
        if (uri != null) {
            busy = true
            status = 0
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var finished = false
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { out ->
                            var offset = 0
                            while (offset < bytes.size) {
                                ensureActive()
                                val size = minOf(64 * 1024, bytes.size - offset)
                                out.write(bytes, offset, size)
                                offset += size
                            }
                        }
                        ensureActive()
                    }
                    finished = true
                    status = R.string.image_viewer_saved
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    status = R.string.image_viewer_export_failed
                } finally {
                    if (!finished) withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                    busy = false
                }
            }
        }
    }

    fun share() {
        val type = mime ?: return
        busy = true
        status = 0
        scope.launch {
            var cached: File? = null
            var shared = false
            try {
                withContext(Dispatchers.IO) {
                    val root = File(context.cacheDir, "file-exports").apply { mkdirs() }
                    root.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                        .forEach { it.deleteRecursively() }
                    val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }
                    cached = File(directory, filename)
                    cached!!.writeBytes(bytes)
                }
                ensureActive()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", checkNotNull(cached))
                val send = Intent(Intent.ACTION_SEND).apply {
                    this.type = type
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(filename, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.image_viewer_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                shared = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status = R.string.image_viewer_export_failed
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!shared) cached?.parentFile?.deleteRecursively()
                }
                busy = false
            }
        }
    }

    Column {
        Row {
            ActionIcon(R.drawable.ic_download, R.string.image_viewer_save, enabled = mime != null && !busy && !picking, onClick = {
                try { picking = true; status = 0; save.launch(filename) }
                catch (_: Exception) { picking = false; status = R.string.image_viewer_export_failed }
            })
            ActionIcon(R.drawable.ic_share_2, R.string.image_viewer_share, ::share, enabled = mime != null && !busy && !picking)
        }
        if (busy) Text(stringResource(R.string.image_viewer_exporting), style = MaterialTheme.typography.bodySmall)
        else if (status != 0) Text(stringResource(status), color = if (status == R.string.image_viewer_export_failed)
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall)
    }
}
