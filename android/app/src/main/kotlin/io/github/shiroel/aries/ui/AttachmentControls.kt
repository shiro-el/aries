package io.github.shiroel.aries.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import io.github.shiroel.aries.R
import java.io.File

/** System document picker and camera: each result only appends to the Chat draft. */
@Composable
fun AttachmentControls(chat: ChatModel) {
    val context = LocalContext.current
    LaunchedEffect(chat, chat.pendingShare) {
        chat.pendingShare?.let {
            chat.pendingShare = null
            chat.attach(context.applicationContext, it)
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) chat.attach(context.applicationContext, SharedContent(uris))
    }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        if (file != null) {
            if (success) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                chat.attach(context.applicationContext, SharedContent(listOf(uri))) { file.delete() }
            } else file.delete()
        }
    }
    val enabled = !chat.uploading && chat.sending == null
    val fileLabel = stringResource(R.string.attachments_file_button)
    val cameraLabel = stringResource(R.string.attachments_camera_button)
    Row {
        TextButton(
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = fileLabel },
            onClick = {
                try { files.launch(arrayOf("*/*")) } catch (e: Exception) { chat.attachmentFailed(e) }
            },
        ) { Text("📎") }
        TextButton(
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = cameraLabel },
            onClick = {
                try {
                    val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
                    val file = File.createTempFile("photo-", ".jpg", directory)
                    cameraPath = file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.attachments", file))
                } catch (e: Exception) {
                    cameraPath?.let { File(it).delete() }
                    cameraPath = null
                    chat.attachmentFailed(e)
                }
            },
        ) { Text("📷") }
    }
}
