package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ScreenLine
import kotlin.math.roundToInt

/** A cell of the viewport grid. */
data class Cell(val row: Int, val col: Int)

/**
 * Selected cells, in reading order from [start] to [end] (both included).
 * [link] is the link under the long press, if there was one.
 */
data class TerminalSelection(val start: Cell, val end: Cell, val link: String? = null, val snapshot: TerminalSnapshot? = null) {
    fun ordered(): TerminalSelection =
        if (end.row < start.row || (end.row == start.row && end.col < start.col)) copy(start = end, end = start) else this

    fun contains(row: Int, col: Int): Boolean {
        val s = ordered()
        if (row < s.start.row || row > s.end.row) return false
        if (row == s.start.row && col < s.start.col) return false
        if (row == s.end.row && col > s.end.col) return false
        return true
    }
}

/**
 * One row's cells: index = column, `""` for the right half of a wide
 * character, `" "` for a blank cell.
 */
fun rowCells(line: ScreenLine?, cols: Int): Array<String> {
    val cells = Array(cols) { " " }
    line?.runs?.forEach { run ->
        var c = run.column.toInt()
        run.text.codePoints().forEach { cp ->
            if (c < cols) cells[c] = String(Character.toChars(cp))
            if (run.wide) {
                if (c + 1 < cols) cells[c + 1] = ""
                c += 2
            } else {
                c += 1
            }
        }
    }
    return cells
}

/** The link target of the cell at [col], from an OSC 8 hyperlink. */
private fun hyperlinkAt(line: ScreenLine?, col: Int): String? =
    line?.runs?.firstOrNull { col >= it.column.toInt() && col < it.column.toInt() + it.width.toInt() }?.link

/** One char per column, so regex offsets are columns. */
private fun flat(cells: Array<String>): String =
    cells.joinToString("") { if (it.length == 1) it else "\u0001" }

private val UrlPattern = Regex("""(https?|ftp|file)://[^\s\u0001<>"'`]+""")

/** Columns of the printed URL covering [col], trailing punctuation dropped. */
private fun urlAt(cells: Array<String>, col: Int): Pair<IntRange, String>? {
    for (m in UrlPattern.findAll(flat(cells))) {
        val url = trimWebUrl(m.value)
        val range = m.range.first until m.range.first + url.length
        if (col in range) return range to url
    }
    return null
}

/** What a long press on [cell] selects: the link there, else the word. */
fun selectAt(viewer: TerminalViewer, cell: Cell): TerminalSelection = selectAt(viewer.snapshot(), cell)

private fun selectAt(snapshot: TerminalSnapshot, cell: Cell): TerminalSelection {
    val line = snapshot.line(cell.row)
    val cells = rowCells(line, snapshot.cols)
    val col = cell.col.coerceIn(0, (snapshot.cols - 1).coerceAtLeast(0))
    hyperlinkAt(line, col)?.let { uri ->
        val runs = line!!.runs.filter { it.link == uri }
        val first = runs.minOf { it.column.toInt() }
        val last = runs.maxOf { it.column.toInt() + it.width.toInt() } - 1
        return TerminalSelection(Cell(cell.row, first), Cell(cell.row, last), uri, snapshot)
    }
    urlAt(cells, col)?.let { (range, url) ->
        return TerminalSelection(Cell(cell.row, range.first), Cell(cell.row, range.last), url, snapshot)
    }
    // A wide character's right half ("") belongs to the word.
    val inWord = { c: Int -> c in cells.indices && (cells[c].isEmpty() || cells[c].isNotBlank()) }
    if (!inWord(col)) return TerminalSelection(Cell(cell.row, col), Cell(cell.row, col), snapshot = snapshot)
    var a = col
    var b = col
    while (inWord(a - 1)) a--
    while (inWord(b + 1)) b++
    return TerminalSelection(Cell(cell.row, a), Cell(cell.row, b), snapshot = snapshot)
}

/** The selected text; each row's trailing blanks are dropped. */
fun selectedText(viewer: TerminalViewer, selection: TerminalSelection): String {
    val s = selection.ordered()
    val snapshot = s.snapshot ?: viewer.snapshot()
    return (s.start.row..s.end.row).joinToString("\n") { row ->
        val cells = rowCells(snapshot.line(row), snapshot.cols)
        val from = if (row == s.start.row) s.start.col else 0
        val to = if (row == s.end.row) s.end.col else snapshot.cols - 1
        (from.coerceAtLeast(0)..to.coerceAtMost(snapshot.cols - 1)).joinToString("") { cells[it] }.trimEnd()
    }
}

/**
 * How the phone looks at the grid: zoom, pan and selection. None of it
 * reaches the Terminal (observe mode): only [TerminalViewer.scrollBack]
 * moves the core's view into the scrollback.
 */
class TerminalLook(private val viewer: TerminalViewer) {
    /** 1 = the grid fits the screen width (or the base font size). */
    var zoom by mutableFloatStateOf(1f)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var panY by mutableFloatStateOf(0f)
        private set
    var selection by mutableStateOf<TerminalSelection?>(null)

    private val cols get() = selection?.snapshot?.cols ?: viewer.cols
    private val rows get() = selection?.snapshot?.rows ?: viewer.rows

    /** Cell size at zoom 1 and the viewport, from the last layout. */
    private var baseW = 1f
    private var baseH = 1f
    private var viewW = 0f
    private var viewH = 0f
    private var topInset = 0f

    /** Vertical drag not yet turned into whole scrollback lines. */
    private var scrollRest = 0f

    val cellW get() = baseW * zoom
    val cellH get() = baseH * zoom

    fun layout(cellW: Float, cellH: Float, width: Float, height: Float, topInset: Float = 0f) {
        baseW = cellW.coerceAtLeast(0.01f)
        baseH = cellH.coerceAtLeast(0.01f)
        viewW = width
        viewH = height
        this.topInset = topInset.coerceAtLeast(0f)
        clamp()
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** Keep live input visible while the Host's resized grid is still in flight. */
    fun revealCursor() {
        if (selection != null || !viewer.typing || viewer.scrolled != 0 || viewer.search != null) return
        val cursor = viewer.cursor ?: return
        val y = cursor.row.toInt() * cellH
        if (y + panY < topInset) panY = topInset - y
        else if (y + cellH + panY > viewH) panY = viewH - y - cellH
        clamp()
    }

    /** Bring the selected search cells into the phone's visible part of the grid. */
    fun revealSearchMatch() {
        if (selection != null) return
        val match = viewer.search?.highlights?.firstOrNull { it.current } ?: return
        val x = match.column.toInt() * cellW
        val y = match.row.toInt() * cellH
        if (x + panX < 0) panX = -x
        else if (x + cellW + panX > viewW) panX = viewW - x - cellW
        if (y + panY < topInset) panY = topInset - y
        else if (y + cellH + panY > viewH) panY = viewH - y - cellH
        clamp()
    }

    fun cellAt(p: Offset): Cell = Cell(
        ((p.y - panY) / cellH).toInt().coerceIn(0, (rows - 1).coerceAtLeast(0)),
        ((p.x - panX) / cellW).toInt().coerceIn(0, (cols - 1).coerceAtLeast(0)),
    )

    /** A pinch around [centroid] by [zoomBy], and a drag by [pan]. */
    fun transform(centroid: Offset, pan: Offset, zoomBy: Float) {
        val next = (zoom * zoomBy).coerceIn(MinZoom, MaxZoom)
        if (next != zoom) {
            val k = next / zoom
            panX = centroid.x - (centroid.x - panX) * k
            panY = centroid.y - (centroid.y - panY) * k
            zoom = next
        }
        panX += pan.x
        drag(pan.y, centroid)
        clamp()
    }

    /**
     * Down ([dy] > 0) pans to the grid's top, then reads back into the
     * scrollback; up first returns towards the live screen, then pans.
     */
    private fun drag(dy: Float, at: Offset) {
        if (viewer.altScreen) return dragProgram(dy, at)
        if (dy > 0) {
            val room = topInset - panY
            val used = minOf(dy, room)
            panY += used
            scrollBy(dy - used)
        } else if (dy < 0) {
            if (viewer.scrolled > 0) scrollBy(dy) else panY += dy
        }
    }

    /**
     * The program keeps its own history (alternate screen): pan to the
     * grid's edge first, then the rest of the drag scrolls the program.
     */
    private fun dragProgram(dy: Float, at: Offset) {
        val room = if (dy > 0) topInset - panY else minOf(0f, viewH - cellH * rows) - panY
        val used = if (dy > 0) minOf(dy, room.coerceAtLeast(0f)) else maxOf(dy, room.coerceAtMost(0f))
        panY += used
        val lines = wholeLines(dy - used)
        if (lines != 0) viewer.scrollProgram(lines, cellAt(at))
    }

    private fun scrollBy(dy: Float) {
        val lines = wholeLines(dy)
        if (lines != 0) viewer.scrollBack(lines)
    }

    /** [dy] added to the drag not yet turned into lines; the whole lines it now makes. */
    private fun wholeLines(dy: Float): Int {
        if (dy == 0f) return 0
        scrollRest += dy
        val lines = (scrollRest / cellH).toInt()
        scrollRest -= lines * cellH
        return lines
    }

    private fun clamp() {
        val minX = minOf(0f, viewW - cellW * cols)
        val minY = minOf(0f, viewH - cellH * rows)
        panX = panX.coerceIn(minX, 0f)
        panY = panY.coerceIn(minY, topInset)
    }

    private companion object {
        const val MinZoom = 0.5f
        const val MaxZoom = 6f
    }
}

/**
 * The grid's gestures: drag and pinch to pan, zoom and read back; long
 * press (then drag) to select. Taps are left to other handlers; they only
 * clear the selection.
 */
fun Modifier.terminalGestures(look: TerminalLook, viewer: TerminalViewer): Modifier = pointerInput(look) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        // null: still pressed when the long press timeout ran out.
        val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.none { it.pressed }) return@withTimeoutOrNull false
                if (ev.changes.size > 1) return@withTimeoutOrNull true
                if ((ev.changes[0].position - down.position).getDistance() > slop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        when (moved) {
            null -> {
                val at = look.cellAt(down.position)
                val picked = selectAt(look.selection?.snapshot ?: viewer.snapshot(), at)
                var dragging = picked
                look.selection = dragging
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    // Input or another action ended this selection while the finger
                    // stayed down. That gesture no longer owns the frozen snapshot.
                    if (!c.pressed || look.selection !== dragging) break
                    val to = look.cellAt(c.position)
                    if (to != at) {
                        val extended = picked.copy(end = to, link = null)
                        if (extended != dragging) {
                            dragging = extended
                            look.selection = dragging
                        }
                    }
                    c.consume()
                }
            }
            false -> look.selection = null
            true -> {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.none { it.pressed }) break
                    look.transform(ev.calculateCentroid(useCurrent = true), ev.calculatePan(), ev.calculateZoom())
                    ev.changes.forEach { it.consume() }
                }
            }
        }
    }
}

/** What the phone's own view shows above the grid: scrollback position, zoom, selection actions. */
@Composable
fun TerminalLookBar(viewer: TerminalViewer, look: TerminalLook, onSendToAgent: ((String) -> Unit)? = null) {
    val selection = look.selection
    if (viewer.scrolled == 0 && look.zoom == 1f && selection == null) return
    val context = LocalContext.current
    val uris = LocalUriHandler.current
    val copy = { text: String ->
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        look.selection = null
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selection != null) {
                ActionIcon(R.drawable.ic_copy, R.string.terminal_copy, { copy(selectedText(viewer, selection)) })
                onSendToAgent?.let { send ->
                    ActionIcon(R.drawable.ic_send, R.string.terminal_send_to_agent, {
                        val text = selectedText(viewer, selection)
                        look.selection = null
                        send(text)
                    })
                }
                selection.link?.let { link ->
                    ActionIcon(R.drawable.ic_external_link, R.string.terminal_open_link, {
                        look.selection = null
                        uris.openUri(link)
                    })
                    ActionIcon(R.drawable.ic_link, R.string.terminal_copy_link, { copy(link) })
                }
                ActionIcon(R.drawable.ic_x, R.string.cancel_button, { look.selection = null })
            }
            if (look.zoom != 1f) {
                TextButton(onClick = look::resetZoom) {
                    Text(stringResource(R.string.terminal_zoom_reset, (look.zoom * 100).roundToInt()))
                }
            }
            if (viewer.scrolled > 0) {
                Text(
                    pluralStringResource(R.plurals.terminal_scrolled_back, viewer.scrolled, viewer.scrolled),
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
