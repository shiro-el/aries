package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Only image bytes are retained in the draft; the Host owns the uploaded file. */
data class DraftAttachment(val path: String, val name: String, val size: Int, val image: ByteArray? = null)

/** A phone camera source; an uploaded path permits a storage-only retry. */
data class CapturedPhoto(val path: String, val uploadedPath: String? = null)

internal data class ChatAttachments(val text: String, val paths: List<String>)

// terminal.put in herdr-core 0.1.17 uses these ID symbols and sanitizes basenames.
private val uploadedTerminal = Regex("t[1-9][0-9bcdfghjkmnpqrstvwxyz]*")
private val uploadedName = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
// Match the exact shellQuote spelling too, including apostrophes in the state root.
private val attachmentTokens = Regex("""(?<!\S)(?:'(?:[^']|'"'"')*'|"[^"\n]+"|`[^`\n]+`|[^\s]+)(?=$|\s)""")

/** Path identity survives a reinstall and other phones. Never treat arbitrary /files/ as uploads. */
internal fun chatAttachments(text: String): ChatAttachments {
    val paths = mutableListOf<String>()
    val display = attachmentTokens.replace(text) { match ->
        val token = match.value
        val path = when {
            token.startsWith('\'') && token.endsWith('\'') -> token.drop(1).dropLast(1).replace("'\"'\"'", "'")
            (token.startsWith('"') && token.endsWith('"')) || (token.startsWith('`') && token.endsWith('`')) -> token.drop(1).dropLast(1)
            else -> token
        }
        val parts = path.split('/')
        val tail = parts.takeLast(5)
        val upload = path.startsWith('/') && parts.drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
            tail.size == 5 && tail[0] == "herdr-core" &&
            tail[1].isNotBlank() && tail[1].none { it.isISOControl() || it in "\\:*?\"<>|" } &&
            tail[2] == "files" && uploadedTerminal.matches(tail[3]) && uploadedName.matches(tail[4])
        if (upload) { paths += path; "" } else token
    }
    return ChatAttachments(if (paths.isEmpty()) text else display.trim(), paths.distinct())
}

/** A horizontal strip keeps multiple attachments from consuming the entire composer. */
@Composable
internal fun DraftAttachments(chat: ChatModel) {
    if (chat.attachments.isEmpty() && chat.capturedPhotos.isEmpty() && chat.failedAttachments.isEmpty()) return
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chat.failedAttachments.forEach { name ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.widthIn(max = 280.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.attachments_failed_file, name), Modifier.weight(1f, fill = false).padding(8.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onErrorContainer)
                    IconButton(onClick = { chat.removeFailedAttachment(name) }) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.attachments_remove_button, name))
                    }
                }
            }
        }
        chat.capturedPhotos.forEach { photo -> key(photo.path) {
            val file = File(photo.path)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.widthIn(max = 280.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, Modifier.weight(1f, fill = false).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { chat.attachPhoto(context.applicationContext, file) }, enabled = !chat.uploading && chat.canEdit) {
                        Text(stringResource(R.string.attachments_retry_photo_button))
                    }
                    IconButton(onClick = { chat.removeCapturedPhoto(photo) }, enabled = !chat.uploading) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.attachments_remove_button, file.name))
                    }
                }
            }
        } }
        chat.attachments.forEach { attachment -> key(attachment) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.widthIn(max = 280.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AttachmentThumbnail(attachment.name, attachment.image)
                    AttachmentLabel(attachment.name, attachment.size.toLong(), Modifier.weight(1f, fill = false).padding(8.dp))
                    IconButton(onClick = { chat.removeAttachment(attachment) }, enabled = chat.sending == null) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.attachments_remove_button, attachment.name))
                    }
                }
            }
        } }
    }
}

/** Both the following and reading-history renderers use this same header. */
@Composable
internal fun ChatAttachmentCards(attachments: ChatAttachments?, chat: ChatModel) {
    if (attachments == null || attachments.paths.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.paths.forEach { path -> key(path) { RemoteAttachmentCard(path, chat) } }
    }
}

@Composable
private fun RemoteAttachmentCard(path: String, chat: ChatModel) {
    val context = LocalContext.current
    val connection = chat.link?.connection
    val openFile = LocalOpenRemoteFile.current
    val name = path.substringAfterLast('/')
    val isImage = remoteMediaKind(path) == RemoteMediaKind.IMAGE
    var bytes by remember(path, connection) { mutableStateOf<ByteArray?>(null) }
    var size by remember(path, connection) { mutableStateOf<Long?>(null) }
    var unavailable by remember(path, connection) { mutableStateOf(false) }
    var viewing by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, connection) {
        if (connection == null) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                if (isImage) {
                    val temporary = File.createTempFile("chat-attachment-", ".bin", context.cacheDir)
                    try {
                        downloadExport(connection, chat.terminal.address, path, temporary, MAX_ATTACHMENT_BYTES.toLong())
                        val loaded = temporary.readBytes()
                        withContext(Dispatchers.Main) { bytes = loaded; size = loaded.size.toLong() }
                    } finally { temporary.delete() }
                } else {
                    val length = connection.readFileBytes(chat.terminal.address, path, 0uL, 1u).totalSize.toLong()
                    withContext(Dispatchers.Main) { size = length }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { unavailable = true }
    }
    val loaded = bytes
    if (viewing && loaded != null) ImageViewer(loaded, onDismiss = { viewing = false }, name = name)
    Surface(
        onClick = {
            if (isImage && loaded != null) viewing = true
            else openFile?.invoke(FileLocation(path))
        },
        enabled = loaded != null || openFile != null,
        modifier = Modifier.fillMaxWidth().testTag("attachment:$path"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AttachmentThumbnail(name, loaded)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                AttachmentLabel(name, size)
                if (unavailable) Text(stringResource(R.string.files_preview_failed), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AttachmentLabel(name: String, size: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        if (size != null) Text(Formatter.formatShortFileSize(context, size), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AttachmentThumbnail(name: String, bytes: ByteArray?) {
    val bitmap by produceState<Bitmap?>(null, bytes, name) {
        value = if (bytes != null) withContext(Dispatchers.Default) {
            decodeImageThumbnail(bytes, name)
        } else null
    }
    val picture = bitmap
    if (picture != null) Image(picture.asImageBitmap(), name, Modifier.size(56.dp), contentScale = ContentScale.Crop)
    else FileTypeIcon(name, Modifier.size(40.dp).padding(8.dp))
}
