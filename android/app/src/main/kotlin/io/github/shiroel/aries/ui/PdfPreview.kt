package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PdfPicture(val bitmap: Bitmap, val pages: Int)

/** One bounded page bitmap at a time. Every render closes its page, renderer and descriptor. */
@Composable
internal fun PdfPreview(file: File) {
    var index by remember(file) { mutableIntStateOf(0) }
    var pages by remember(file) { mutableIntStateOf(0) }
    var loading by remember(file, index) { mutableStateOf(true) }
    var failed by remember(file, index) { mutableStateOf(false) }
    val picture by produceState<PdfPicture?>(null, file, index) {
        value = null
        try {
            val rendered = withContext(Dispatchers.IO) { renderPdfPage(file, index) }
            pages = rendered.pages
            value = rendered
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (pages > 0) Text(stringResource(R.string.files_pdf_page, index + 1, pages))
        Row {
            ActionIcon(R.drawable.ic_chevron_left, R.string.files_previous_button, { index-- }, enabled = !loading && index > 0)
            ActionIcon(R.drawable.ic_chevron_right, R.string.files_next_button, { index++ }, enabled = !loading && index + 1 < pages)
        }
        when {
            loading -> Text(stringResource(R.string.files_loading))
            failed -> Text(stringResource(R.string.files_preview_failed), color = MaterialTheme.colorScheme.error)
            else -> picture?.let {
                ImageViewport(it.bitmap.asImageBitmap(), Modifier.fillMaxWidth().weight(1f).testTag("pdf-page:${index + 1}"),
                    description = stringResource(R.string.files_pdf_title))
            }
        }
    }
}

private fun renderPdfPage(file: File, index: Int): PdfPicture {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(index).use { page ->
                check(page.width > 0 && page.height > 0)
                val scale = 2048f / maxOf(page.width, page.height)
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceIn(1, 2048),
                    (page.height * scale).toInt().coerceIn(1, 2048), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return PdfPicture(bitmap, renderer.pageCount)
            }
        }
    }
}
