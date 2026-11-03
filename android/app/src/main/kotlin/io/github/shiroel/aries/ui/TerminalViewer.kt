package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.CursorPos
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalExit
import io.github.shiroel.aries.core.TerminalInfo

/**
 * One open Terminal's screen as the core's frames built it. The grid is
 * the Terminal's own size (observe mode): the desktop decides it.
 */
class TerminalViewer(val terminal: TerminalInfo) {
    var cols by mutableIntStateOf(0)
        private set
    var rows by mutableIntStateOf(0)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var cursor by mutableStateOf<CursorPos?>(null)
        private set

    /** How the program ended, once it did. */
    var exit by mutableStateOf<TerminalExit?>(null)
        private set

    /** The stream ended without an exit (connection lost). */
    var closed by mutableStateOf(false)
        private set

    /** The Host line this screen streams over; its state shows while it reconnects. */
    var link: HostLink? = null

    /** Bumped on every frame; the canvas redraws when it changes. */
    var version by mutableIntStateOf(0)
        private set

    private var lines: Array<ScreenLine?> = emptyArray()

    fun apply(frame: ScreenFrame) {
        val c = frame.cols.toInt()
        val r = frame.rows.toInt()
        if (frame.full || c != cols || r != rows) {
            cols = c
            rows = r
            lines = arrayOfNulls(r)
        }
        for (line in frame.lines) {
            val i = line.row.toInt()
            if (i < lines.size) lines[i] = line
        }
        cursor = frame.cursor
        title = frame.title
        version++
    }

    fun exited(e: TerminalExit) {
        exit = e
    }

    fun markClosed() {
        closed = true
    }

    fun line(row: Int): ScreenLine? = lines.getOrNull(row)

    /** The visible text, one string per row (for accessibility and tests). */
    fun text(): String = (0 until rows).joinToString("\n") { row ->
        val sb = StringBuilder()
        line(row)?.runs?.forEach { run ->
            while (sb.length < run.column.toInt()) sb.append(' ')
            sb.append(run.text)
        }
        sb.toString().trimEnd()
    }
}

private val TerminalBackground = Color(0xFF101214)
private val TerminalForeground = Color(0xFFD8DADC)

@Composable
fun TerminalScreen(viewer: TerminalViewer, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            Column(Modifier.weight(1f)) {
                Text(viewer.title?.ifBlank { null } ?: displayName(viewer.terminal), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.terminal_observing, viewer.terminal.address, viewer.cols, viewer.rows),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        viewer.exit?.let { Banner(exitText(it)) }
        val line = viewer.link?.state
        if (viewer.exit == null && line == LinkState.Reconnecting) Banner(stringResource(R.string.reconnecting))
        if (viewer.exit == null && (viewer.closed || line is LinkState.Down)) {
            Banner(stringResource(R.string.terminal_connection_closed))
        }
        TerminalCanvas(viewer, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun exitText(e: TerminalExit): String {
    val code = e.exitCode
    return when {
        e.reason == "exited" && code != null -> stringResource(R.string.terminal_exited_code, code)
        e.signal != null -> stringResource(R.string.terminal_exited_signal, e.signal!!)
        else -> stringResource(R.string.terminal_exited_reason, e.reason)
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Base text size; smaller when the Terminal is too wide for the screen. */
private val MaxFont = 13.sp

@Composable
private fun TerminalCanvas(viewer: TerminalViewer, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Advance width and line height of one monospace cell per font px.
    val probe = remember(measurer) {
        measurer.measure("M", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 100.sp))
    }
    val description = viewer.version.let { viewer.text() }
    BoxWithConstraints(modifier.background(TerminalBackground)) {
        val cols = viewer.cols.coerceAtLeast(1)
        val widthPx = with(density) { maxWidth.toPx() }
        val per100 = probe.size.width.toFloat().coerceAtLeast(1f)
        val maxPx = with(density) { MaxFont.toPx() }
        val fontPx = minOf(maxPx, widthPx / cols / per100 * 100f)
        val cellW = per100 * fontPx / 100f
        val cellH = probe.size.height * fontPx / 100f
        val fontSize = with(density) { fontPx.toSp() }
        val height: Dp = with(density) { (cellH * viewer.rows).toDp() }
        Canvas(
            Modifier.fillMaxWidth().height(height).testTag("terminal-screen")
                .semantics { contentDescription = description },
        ) {
            viewer.version // redraw on every frame
            val base = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, color = TerminalForeground)
            for (row in 0 until viewer.rows) {
                val y = row * cellH
                for (run in viewer.line(row)?.runs.orEmpty()) {
                    val x = run.column.toInt() * cellW
                    var fg = run.fg?.let(::rgb) ?: TerminalForeground
                    var bg = run.bg?.let(::rgb)
                    if (run.inverse) {
                        val f = fg
                        fg = bg ?: TerminalBackground
                        bg = f
                    }
                    if (run.dim) fg = fg.copy(alpha = 0.6f)
                    bg?.let { drawRect(it, Offset(x, y), Size(run.width.toInt() * cellW, cellH)) }
                    val style = styleOf(base, run, fg)
                    if (run.wide) {
                        // Each wide character gets exactly two cells.
                        var cx = x
                        run.text.codePoints().forEach { cp ->
                            drawText(measurer, String(Character.toChars(cp)), Offset(cx, y), style)
                            cx += 2 * cellW
                        }
                    } else if (run.text.isNotBlank()) {
                        drawText(measurer, run.text, Offset(x, y), style)
                    }
                }
            }
            viewer.cursor?.let { c ->
                drawRect(
                    TerminalForeground.copy(alpha = 0.5f),
                    Offset(c.column.toInt() * cellW, c.row.toInt() * cellH),
                    Size(cellW, cellH),
                )
            }
        }
    }
}

private fun styleOf(base: TextStyle, run: CellRun, fg: Color): TextStyle {
    val decorations = buildList {
        if (run.underline) add(TextDecoration.Underline)
        if (run.strikeout) add(TextDecoration.LineThrough)
    }
    return base.copy(
        color = fg,
        fontWeight = if (run.bold) FontWeight.Bold else null,
        fontStyle = if (run.italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}

private fun rgb(v: UInt): Color = Color(0xFF000000.toInt() or v.toInt())
