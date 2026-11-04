package io.github.shiroel.aries.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
internal const val ATTACHMENT_IMAGE_SIDE = 2048

data class PreparedAttachment(val name: String, val bytes: ByteArray)

/** One literal POSIX shell argument, including embedded quotes and newlines. */
fun shellQuote(path: String): String = "'" + path.replace("'", "'\"'\"'") + "'"

/** Decode to the upload dimensions, rather than allocating a full camera-sized bitmap. */
fun prepareAttachment(context: Context, uri: Uri, resizeImages: Boolean): PreparedAttachment {
    val resolver = context.contentResolver
    val name = if (uri.scheme == "file") File(uri.path!!).name else {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "attachment"
    }
    val mime = resolver.getType(uri) ?: MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
    if (resizeImages && mime?.startsWith("image/") == true) {
        // ImageDecoder applies image orientation and decodes only the first frame.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > ATTACHMENT_IMAGE_SIDE) {
                decoder.setTargetSize(
                    maxOf(1, (info.size.width.toLong() * ATTACHMENT_IMAGE_SIDE / longest).toInt()),
                    maxOf(1, (info.size.height.toLong() * ATTACHMENT_IMAGE_SIDE / longest).toInt()),
                )
            }
        }
        val bytes = try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
        checkAttachmentSize(bytes.size)
        return PreparedAttachment(name.substringBeforeLast('.', name) + ".jpg", bytes)
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            checkAttachmentSize(output.size() + read)
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw IOException("attachment could not be opened")
    return PreparedAttachment(name, bytes)
}

class AttachmentTooLarge : IOException()

private fun checkAttachmentSize(size: Int) {
    if (size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLarge()
}

/** Incoming data stays on the phone until the user chooses an Agent. */
data class SharedContent(val uris: List<Uri> = emptyList(), val text: String? = null) {
    companion object {
        @Suppress("DEPRECATION")
        fun from(intent: Intent): SharedContent? {
            if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
            val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<Uri>()
            } else listOfNotNull(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
            val clip = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
            // External shares may only grant content URIs, never access to an app-private file:// path.
            val uris = (streams + clip).filter { it.scheme == "content" }.distinct()
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            return SharedContent(uris, text).takeIf { uris.isNotEmpty() || text != null }
        }
    }
}

/** Device preference: resizing is on by default, including after an app restart. */
class AttachmentSettings(private val preferences: SharedPreferences) {
    var resizeImages by mutableStateOf(preferences.getBoolean("resize_images", true))
        private set

    fun chooseResizeImages(value: Boolean) {
        preferences.edit().putBoolean("resize_images", value).apply()
        resizeImages = value
    }

    companion object {
        fun from(context: Context) = AttachmentSettings(
            context.applicationContext.getSharedPreferences("attachments", Context.MODE_PRIVATE),
        )
    }
}
