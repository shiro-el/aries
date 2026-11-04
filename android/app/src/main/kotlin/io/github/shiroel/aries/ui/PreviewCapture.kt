package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Context is frozen with the pixels, even if the page refreshes behind the editor. */
internal data class PreviewCapture(val bitmap: Bitmap, val url: String, val desktop: Boolean) {
    var tool by mutableStateOf(PreviewMarkTool.Pen)
    var marks by mutableStateOf<List<PreviewMark>>(emptyList())
    var note by mutableStateOf("")
}

internal enum class PreviewMarkTool { Pen, Rectangle }
internal data class PreviewMark(val tool: PreviewMarkTool, val points: List<Offset>)

/** Use the same bitmap coordinates and renderer for the editor and the uploaded image. */
private fun drawPreviewMarks(canvas: AndroidCanvas, marks: List<PreviewMark>, width: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.RED
        style = Paint.Style.STROKE
        strokeWidth = maxOf(3f, width / 180f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    marks.forEach { mark ->
        val first = mark.points.firstOrNull() ?: return@forEach
        val last = mark.points.last()
        if (mark.tool == PreviewMarkTool.Rectangle) {
            canvas.drawRect(minOf(first.x, last.x), minOf(first.y, last.y),
                maxOf(first.x, last.x), maxOf(first.y, last.y), paint)
        } else {
            val path = Path().apply {
                moveTo(first.x, first.y)
                mark.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
    }
}

private fun previewImageRect(size: Size, bitmap: Bitmap): Rect {
    val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    return Rect((size.width - width) / 2, (size.height - height) / 2,
        (size.width + width) / 2, (size.height + height) / 2)
}

@Composable
internal fun PreviewCaptureEditor(
    capture: PreviewCapture,
    onDismiss: () -> Unit,
    onAttach: (SharedContent, () -> Unit) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tool by capture::tool
    var marks by capture::marks
    var current by remember(capture) { mutableStateOf<PreviewMark?>(null) }
    var note by capture::note
    var exporting by remember(capture) { mutableStateOf(false) }
    var failed by remember(capture) { mutableStateOf(false) }
    val mode = stringResource(if (capture.desktop) R.string.preview_desktop else R.string.preview_mobile)
    val canvasDescription = stringResource(R.string.preview_capture_canvas)

    Dialog(onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.preview_capture_title), style = MaterialTheme.typography.titleLarge)
                Text(capture.url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text(mode, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tool == PreviewMarkTool.Pen, onClick = { tool = PreviewMarkTool.Pen },
                        enabled = !exporting, label = { Text(stringResource(R.string.preview_capture_pen)) })
                    FilterChip(selected = tool == PreviewMarkTool.Rectangle, onClick = { tool = PreviewMarkTool.Rectangle },
                        enabled = !exporting, label = { Text(stringResource(R.string.preview_capture_rectangle)) })
                    TextButton(onClick = { marks = marks.dropLast(1) }, enabled = marks.isNotEmpty() && !exporting) {
                        Text(stringResource(R.string.preview_capture_undo))
                    }
                }
                Canvas(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("preview-capture-canvas")
                    .semantics { contentDescription = canvasDescription }
                    .pointerInput(capture, tool, exporting) {
                        if (!exporting) detectDragGestures(
                            onDragStart = { point ->
                                val rect = previewImageRect(Size(size.width.toFloat(), size.height.toFloat()), capture.bitmap)
                                if (rect.contains(point)) {
                                    val scale = rect.width / capture.bitmap.width
                                    current = PreviewMark(tool, listOf((point - rect.topLeft) / scale))
                                }
                            },
                            onDrag = { change, _ ->
                                current?.let { mark ->
                                    val rect = previewImageRect(Size(size.width.toFloat(), size.height.toFloat()), capture.bitmap)
                                    val point = (change.position - rect.topLeft) / (rect.width / capture.bitmap.width)
                                    val bounded = Offset(point.x.coerceIn(0f, capture.bitmap.width.toFloat()),
                                        point.y.coerceIn(0f, capture.bitmap.height.toFloat()))
                                    current = mark.copy(points = if (mark.tool == PreviewMarkTool.Rectangle)
                                        listOf(mark.points.first(), bounded) else mark.points + bounded)
                                    change.consume()
                                }
                            },
                            onDragEnd = { current?.let { marks = marks + it }; current = null },
                            onDragCancel = { current = null },
                        )
                    }) {
                    val rect = previewImageRect(size, capture.bitmap)
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        try {
                            native.translate(rect.left, rect.top)
                            val scale = rect.width / capture.bitmap.width
                            native.scale(scale, scale)
                            native.drawBitmap(capture.bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                            drawPreviewMarks(native, marks + listOfNotNull(current), capture.bitmap.width)
                        } finally { native.restore() }
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, enabled = !exporting,
                    label = { Text(stringResource(R.string.preview_capture_note)) }, maxLines = 3,
                    modifier = Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.preview_capture_attach_failed), color = MaterialTheme.colorScheme.error)
                val captureContext = stringResource(R.string.preview_capture_context, capture.url, mode)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !exporting) { Text(stringResource(R.string.cancel_button)) }
                    Button(enabled = !exporting, onClick = {
                        val annotations = marks.toList()
                        val text = captureContext +
                            note.trim().takeIf { it.isNotEmpty() }?.let { "\n\n$it" }.orEmpty()
                        exporting = true
                        failed = false
                        scope.launch {
                            var temporary: File? = null
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
                                    val output = File.createTempFile("preview-", ".png", directory)
                                    temporary = output
                                    val bitmap = checkNotNull(capture.bitmap.copy(Bitmap.Config.ARGB_8888, true))
                                    try {
                                        drawPreviewMarks(AndroidCanvas(bitmap), annotations, bitmap.width)
                                        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                    } finally { bitmap.recycle() }
                                    output
                                }
                                if (onAttach(SharedContent(listOf(Uri.fromFile(file)), text)) { file.delete() }) {
                                    temporary = null // The attachment job now owns cleanup.
                                    onDismiss()
                                } else failed = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                temporary?.delete()
                                exporting = false
                            }
                        }
                    }) { Text(stringResource(if (exporting) R.string.preview_capture_preparing else R.string.preview_capture_attach)) }
                }
            }
        }
    }
}
