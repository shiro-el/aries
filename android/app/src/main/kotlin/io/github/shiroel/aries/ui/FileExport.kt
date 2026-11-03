package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.HostConnection
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.UUID

private const val EXPORT_LIMIT = 64L * 1024 * 1024
private const val EXPORT_CHUNK = 1_048_576u
private const val SHARE_LIFETIME_MS = 24L * 60 * 60 * 1000
internal class ExportTooLarge : Exception()

/** Disposing the file/Host panel cancels its transfer, including partial output cleanup. */
@Composable
internal fun FileExportControls(connection: HostConnection?, address: String, path: String) {
    key(connection, address, path) { FileExportActions(connection, address, path) }
}

@Composable
private fun FileExportActions(connection: HostConnection?, address: String, path: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var completed by remember { mutableLongStateOf(0) }
    var total by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableIntStateOf(0) }
    val name = path.substringAfterLast('/').takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download.bin"
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        ?: "application/octet-stream"

    fun start(tree: Uri?) {
        val host = connection ?: return
        busy = true
        saving = false
        completed = 0
        total = null
        status = 0
        job = scope.launch {
            var cached: File? = null
            var shared = false
            try {
                withContext(Dispatchers.IO) {
                    val root = File(context.cacheDir, "file-exports").apply { mkdirs() }
                    // Keep successful shares long enough for recipient apps to open them.
                    root.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > SHARE_LIFETIME_MS }
                        .forEach { it.deleteRecursively() }
                    cached = File(File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }, name)
                }
                val local = checkNotNull(cached)
                downloadExport(host, address, path, local) { count, size ->
                    withContext(Dispatchers.Main) { completed = count; total = size }
                }
                ensureActive()
                if (tree != null) {
                    saving = true
                    saveExport(context, tree, local, name, mime)
                    status = R.string.files_saved
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", local)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.files_share_title))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    shared = true
                    status = R.string.files_share_ready
                }
            } catch (e: CancellationException) {
                status = R.string.files_export_cancelled
                throw e
            } catch (_: ExportTooLarge) {
                status = R.string.files_export_too_large
            } catch (_: Exception) {
                status = R.string.files_export_failed
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!shared) cached?.parentFile?.deleteRecursively()
                }
                busy = false
            }
        }
    }

    val folders = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        picking = false
        if (tree != null) start(tree)
    }
    Column {
        Row {
            TextButton(enabled = connection != null && !busy && !picking, onClick = {
                try { picking = true; folders.launch(null) }
                catch (_: Exception) { picking = false; status = R.string.files_export_failed }
            }) { Text(stringResource(R.string.files_save_button)) }
            TextButton(enabled = connection != null && !busy && !picking, onClick = { start(null) }) {
                Text(stringResource(R.string.files_share_button))
            }
        }
        Text(stringResource(R.string.files_export_limit), style = MaterialTheme.typography.bodySmall)
        if (busy) {
            if (saving || total == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { if (total == 0L) 1f else completed.toFloat() / total!!.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(when {
                saving -> stringResource(R.string.files_export_saving)
                total == null -> stringResource(R.string.files_export_starting)
                else -> stringResource(R.string.files_export_progress, completed, total!!)
            })
            TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.cancel_button)) }
        } else if (status != 0) {
            Text(stringResource(status), color = if (status == R.string.files_export_failed || status == R.string.files_export_too_large)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** At most one byte chunk lives in memory; the file is never exposed before EOF. */
internal suspend fun downloadExport(
    connection: HostConnection,
    address: String,
    path: String,
    destination: File,
    sizeLimit: Long = EXPORT_LIMIT,
    progress: suspend (Long, Long) -> Unit = { _, _ -> },
) = withContext(Dispatchers.IO) {
    var offset = 0uL
    var total: ULong? = null
    destination.outputStream().use { out ->
        do {
            ensureActive()
            val chunk = connection.readFileBytes(address, path, offset, EXPORT_CHUNK)
            ensureActive()
            if (chunk.totalSize > sizeLimit.toULong()) throw ExportTooLarge()
            check(chunk.offset == offset && (total == null || total == chunk.totalSize))
            total = chunk.totalSize
            check(chunk.data.size <= EXPORT_CHUNK.toInt() && offset <= chunk.totalSize)
            val end = offset + chunk.data.size.toULong()
            check(end <= chunk.totalSize && chunk.eof == (end == chunk.totalSize))
            check(chunk.data.isNotEmpty() || chunk.eof)
            out.write(chunk.data)
            offset = end
            progress(offset.toLong(), chunk.totalSize.toLong())
        } while (!chunk.eof)
    }
}

/** SAF creates a new document, preserving existing files (provider chooses a unique name).
 * Failure/cancellation removes only the document this operation created. */
private suspend fun saveExport(context: Context, tree: Uri, file: File, name: String, mime: String) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    var created: Uri? = null
    var finished = false
    try {
        ensureActive()
        created = checkNotNull(DocumentsContract.createDocument(resolver, parent, mime, name))
        checkNotNull(resolver.openOutputStream(created, "w")).use { out ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
        }
        ensureActive()
        finished = true
    } finally {
        if (!finished && created != null) withContext(NonCancellable) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
        }
    }
}
