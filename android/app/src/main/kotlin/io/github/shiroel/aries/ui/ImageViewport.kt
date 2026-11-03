package io.github.shiroel.aries.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import io.github.shiroel.aries.R
import kotlin.math.roundToInt

@Composable
internal fun ImageViewport(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    description: String = stringResource(R.string.image_viewer_title),
) {
    val view = remember(bitmap) { ImageViewportState(bitmap.width, bitmap.height) }
    val zoom = stringResource(R.string.image_viewer_zoom, (view.scale * 100).roundToInt())
    val zoomIn = stringResource(R.string.image_viewer_zoom_in)
    val zoomOut = stringResource(R.string.image_viewer_zoom_out)
    val reset = stringResource(R.string.image_viewer_reset)
    Box(modifier.clipToBounds().onSizeChanged { view.resize(it) }
        .semantics {
            contentDescription = description
            stateDescription = zoom
            horizontalScrollAxisRange = ScrollAxisRange({ view.bound.x - view.offset.x }, { view.bound.x * 2 })
            verticalScrollAxisRange = ScrollAxisRange({ view.bound.y - view.offset.y }, { view.bound.y * 2 })
            scrollBy { x, y -> view.transform(view.center, Offset(-x, -y), 1f); true }
            customActions = listOf(
                CustomAccessibilityAction(zoomIn) { view.transform(view.center, Offset.Zero, 2f); true },
                CustomAccessibilityAction(zoomOut) { view.transform(view.center, Offset.Zero, .5f); true },
                CustomAccessibilityAction(reset) { view.reset(); true },
            )
        }
        .pointerInput(view) {
            detectTapGestures(onDoubleTap = { tap ->
                if (view.scale > 1f) view.reset() else view.transform(tap, Offset.Zero, 3f)
            })
        }
        .pointerInput(view) {
            detectTransformGestures { centroid, pan, scale, _ -> view.transform(centroid, pan, scale) }
        },
    ) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = view.scale
                scaleY = view.scale
                translationX = view.offset.x
                translationY = view.offset.y
            })
    }
}

/** Coordinates refer to a centered, fit-to-window image, never to the Chat layout. */
private class ImageViewportState(private val width: Int, private val height: Int) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport by mutableStateOf(IntSize.Zero)
    val center get() = Offset(viewport.width / 2f, viewport.height / 2f)
    val bound: Offset get() {
        val fit = minOf(viewport.width.toFloat() / width, viewport.height.toFloat() / height)
        return Offset(
            ((width * fit * scale - viewport.width) / 2).coerceAtLeast(0f),
            ((height * fit * scale - viewport.height) / 2).coerceAtLeast(0f),
        )
    }

    fun resize(size: IntSize) { viewport = size; offset = clamp(offset) }
    fun reset() { scale = 1f; offset = Offset.Zero }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val next = (scale * zoom).coerceIn(1f, 8f)
        val ratio = next / scale
        // Keep the image point under the fingers stationary as the scale changes.
        val translated = offset * ratio + (centroid - center) * (1f - ratio) + pan
        scale = next
        offset = clamp(translated)
    }

    private fun clamp(value: Offset): Offset {
        val limit = bound
        return Offset(value.x.coerceIn(-limit.x, limit.x), value.y.coerceIn(-limit.y, limit.y))
    }
}
