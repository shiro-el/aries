package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ViewerPicture(val bitmap: Bitmap, val mime: String)

/**
 * Full-screen raster image viewer shared by Chat and remote file previews.
 * [bytes] are the original encoded image, kept unchanged for export. Callers own
 * loading/size limits and must keep the bytes immutable while this is displayed.
 * [name] is a suggested export filename; it may include a remote path.
 */
@Composable
fun ImageViewer(bytes: ByteArray, onDismiss: () -> Unit, name: String = "image") {
    key(bytes) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        )) {
            var loading by remember { mutableStateOf(true) }
            val picture by produceState<ViewerPicture?>(null, bytes) {
                value = withContext(Dispatchers.Default) { decodeViewerPicture(bytes) }
                loading = false
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.back_button)) }
                        ImageViewerExport(bytes, picture?.mime, name)
                    }
                    val p = picture
                    when {
                        loading -> Text(stringResource(R.string.chat_image_loading))
                        p == null -> Text(stringResource(R.string.chat_image_unreadable), color = MaterialTheme.colorScheme.error)
                        else -> ImageViewport(p.bitmap.asImageBitmap(), Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

/** Bound decode memory while retaining enough detail to read screenshots. */
private fun decodeViewerPicture(bytes: ByteArray): ViewerPicture? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return ViewerPicture(bitmap, bounds.outMimeType ?: "image/png")
}
