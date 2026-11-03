package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ChatDiff
import io.github.shiroel.aries.core.ChatImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Diff lines a collapsed tool row shows. */
private const val DIFF_PREVIEW_LINES = 12

/** Longest side, in pixels, an image is decoded to. */
private const val IMAGE_MAX_PIXELS = 1024

/**
 * A file edit as a unified diff: a short preview while collapsed, the whole
 * (Beacon-capped) diff when expanded. One [Text] for all lines keeps big
 * diffs cheap to lay out.
 */
@Composable
fun DiffView(diff: ChatDiff, expanded: Boolean) {
    val added = Color(0x3328A745)
    val removed = Color(0x33D73A49)
    val header = MaterialTheme.colorScheme.onSurfaceVariant
    val limit = if (expanded) Int.MAX_VALUE else DIFF_PREVIEW_LINES
    val total = remember(diff) { diff.hunks.sumOf { it.lines.size } }
    val text = remember(diff, limit) { diffText(diff, limit, added, removed, header) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SearchableChatText(
            text, "tool-diff",
            Modifier.horizontalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
        )
        if (total > limit) {
            Text(
                stringResource(R.string.chat_diff_more, total - limit),
                style = MaterialTheme.typography.labelSmall,
                color = header,
            )
        }
        if (expanded && diff.truncated) {
            Text(
                stringResource(R.string.chat_diff_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = header,
            )
        }
    }
}

internal fun diffText(diff: ChatDiff, limit: Int, added: Color, removed: Color, header: Color): AnnotatedString =
    buildAnnotatedString {
        var shown = 0
        var first = true
        fun newline() {
            if (!first) append('\n')
            first = false
        }
        for (hunk in diff.hunks) {
            if (shown >= limit) break
            newline()
            withStyle(SpanStyle(color = header)) { append("@@ -${hunk.oldStart} +${hunk.newStart} @@") }
            for (line in hunk.lines) {
                if (shown >= limit) break
                newline()
                val bg = when {
                    line.startsWith("+") -> added
                    line.startsWith("-") -> removed
                    else -> Color.Unspecified
                }
                withStyle(SpanStyle(background = bg)) { append(line) }
                shown++
            }
        }
    }

private sealed interface Picture {
    data object Loading : Picture
    data object Unreadable : Picture
    data class Ready(val bitmap: Bitmap, val bytes: ByteArray) : Picture
}

/**
 * An image of the Chat. The bytes come from the core when shown and are
 * decoded, downsampled, off the main thread; an image over the Beacon's cap
 * is a placeholder with its size.
 */
@Composable
fun ChatImageView(image: ChatImage, load: (String) -> ByteArray?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!image.available) {
        val mb = String.format(Locale.ROOT, "%.1f", image.bytes.toDouble() / (1024 * 1024))
        Text(stringResource(R.string.chat_image_too_large, mb), style = MaterialTheme.typography.bodySmall, color = muted)
        return
    }
    val picture by produceState<Picture>(Picture.Loading, image.id) {
        value = withContext(Dispatchers.Default) {
            load(image.id)?.let { bytes -> decode(bytes)?.let { Picture.Ready(it, bytes) } } ?: Picture.Unreadable
        }
    }
    var viewing by remember(image.id) { mutableStateOf(false) }
    val openLabel = stringResource(R.string.image_viewer_open)
    when (val p = picture) {
        Picture.Loading -> Text(stringResource(R.string.chat_image_loading), style = MaterialTheme.typography.bodySmall, color = muted)
        Picture.Unreadable -> Text(stringResource(R.string.chat_image_unreadable), style = MaterialTheme.typography.bodySmall, color = muted)
        is Picture.Ready -> {
            if (viewing) ImageViewer(p.bytes, onDismiss = { viewing = false })
            Image(
                p.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.chat_image),
                modifier = Modifier.heightIn(max = 320.dp).background(MaterialTheme.colorScheme.surface)
                    .clickable(onClickLabel = openLabel) { viewing = true },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Decodes at most [IMAGE_MAX_PIXELS] on the longest side. */
private fun decode(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > IMAGE_MAX_PIXELS) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
