package io.github.shiroel.aries.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.CursorPos
import io.github.shiroel.aries.core.KeyMods
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.TerminalKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalExit
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalSearch
import java.text.BreakIterator
import java.util.Locale

/** Stored separately from an attach stream, which is recreated on every view switch. */
class TerminalComposerState(private val changed: () -> Unit = {}) {
    private var text by mutableStateOf("")
    var draft: String
        get() = text
        set(value) { text = value; changed() }
    private var visible by mutableStateOf(false)
    var open: Boolean
        get() = visible
        set(value) { visible = value; changed() }
}

/**
 * One open Terminal's screen as the core's frames built it. The grid is
 * the Terminal's own size (observe mode): the desktop decides it.
 */
class TerminalViewer(terminal: TerminalInfo, private val composer: TerminalComposerState = TerminalComposerState()) {
    var inputHistory: InputHistoryModel? = null
    var terminal by mutableStateOf(terminal)
        private set

    /** Reuse the Host's existing Terminal watch to keep header metadata current. */
    fun update(terminals: List<TerminalInfo>) {
        terminals.find { it.address == terminal.address }?.let { terminal = it }
    }

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

    /** A stopped attach may leave the SSH connection healthy. */
    var watchError by mutableStateOf<CoreException?>(null)

    /** The Host line this screen streams over; its state shows while it reconnects. */
    var link: HostLink? = null

    /** Bumped on every frame; the canvas redraws when it changes. */
    var version by mutableIntStateOf(0)
        private set

    private var lines: Array<ScreenLine?> = emptyArray()

    /** Lines the view is scrolled back into the scrollback; 0 = live. */
    var scrolled by mutableIntStateOf(0)
        private set

    /** Scrollback lines above the live screen. */
    var history by mutableIntStateOf(0)
        private set

    /** Moves the core's view (`TerminalView.scrollTo`); set while attached. */
    var scroller: ((UInt) -> UInt)? = null

    var search by mutableStateOf<TerminalSearch?>(null)
        private set
    var searcher by mutableStateOf<(suspend (String, Boolean, Boolean) -> Unit)?>(null)
    var searchNavigator: (suspend (Boolean) -> Unit)? = null

    private var liveScroll: Job? = null

    /** The button animates locally through the cached scrollback, then follows live output. */
    suspend fun animateToLive() {
        liveScroll?.cancel()
        val job = currentCoroutineContext().job
        liveScroll = job
        try {
            animate(scrolled.toFloat(), 0f, animationSpec = tween(250)) { value, _ ->
                val to = value.roundToInt().coerceAtLeast(0)
                if (to != scrolled) scrollTo(to)
            }
            scrollTo(0)
        } finally {
            if (liveScroll === job) liveScroll = null
        }
    }

    /** Reads [lines] further back (negative: towards live), within the scrollback. */
    fun scrollBack(lines: Int) {
        liveScroll?.cancel()
        val to = (scrolled + lines).coerceIn(0, maxOf(history, scrolled))
        if (to != scrolled) scrollTo(to)
    }

    fun scrollToLive() {
        liveScroll?.cancel()
        scrollTo(0)
    }

    private fun scrollTo(to: Int) {
        val at = scroller?.invoke(to.toUInt()) ?: return
        scrolled = at.toInt()
    }

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
        scrolled = frame.scrolled.toInt()
        history = frame.history.toInt()
        search = frame.search
        version++
    }

    fun exited(e: TerminalExit) {
        exit = e
        discardInput()
    }

    fun markClosed() {
        closed = true
        discardInput()
    }

    fun line(row: Int): ScreenLine? = lines.getOrNull(row)

    /** Copy the row references on Main; frames replace rows rather than mutating them. */
    fun snapshot(): TerminalSnapshot = TerminalSnapshot(cols, lines.toList())

    /**
     * Typing mode: attached as an ordinary viewer whose grid is the phone's
     * ([phoneGrid]). Starts on a key or paste ([startTyping]) or right away
     * when the default attach mode is input.
     */
    var typing by mutableStateOf(false)
        private set

    /** Columns and rows the screen has room for while [typing]; the model attaches and resizes to it. */
    var phoneGrid by mutableStateOf<Pair<Int, Int>?>(null)
        internal set

    /**
     * The part of the keyboard's composing text not sent yet: the Hangul
     * syllable still being built (shown at the cursor and in the input bar).
     */
    var composing by mutableStateOf("")
        private set

    /** The rest of the composing text, already typed into the Terminal. */
    private var echoed = ""

    /**
     * Called when typing outside the keyboard (the key bar, a key event) ends
     * a composition the keyboard may still think is open, so it can reset.
     */
    var onCompositionInterrupted: (() -> Unit)? = null

    /** The editable draft stays local until Send, including across input-mode switches. */
    var draft: String
        get() = composer.draft
        set(value) { composer.draft = value }
    var composerOpen: Boolean
        get() = composer.open
        private set(value) { composer.open = value }

    private var pendingPaste: TerminalInput.Paste? = null
    private var pasteConnection: HostConnection? = null
    var pasteSending by mutableStateOf(false)
        private set
    var pasteUncertain by mutableStateOf(false)
        private set

    fun openComposer() {
        interruptComposition()
        composerOpen = true
    }

    fun closeComposer() {
        composerOpen = false
    }

    val canInput: Boolean
        get() = exit == null && !closed && terminal.state == "live" &&
            (link == null || (link?.state == LinkState.Up && link?.connection != null))

    internal val inputUnavailableReason: Int?
        get() = when {
            exit != null || terminal.state != "live" -> R.string.terminal_input_ended
            !canInput -> R.string.terminal_input_offline
            else -> null
        }

    val canPasteDraft: Boolean
        get() = draft.isNotEmpty() && canInput && !pasteSending

    fun pasteDraft() {
        if (!canPasteDraft) return
        startTyping()
        val paste = TerminalInput.Paste(draft)
        pendingPaste = paste
        pasteConnection = link?.connection
        pasteSending = true
        pasteUncertain = false
        send(paste)
    }

    internal fun pasteFinished(paste: TerminalInput.Paste, delivered: Boolean) {
        if (pendingPaste !== paste) return
        pendingPaste = null
        pasteConnection = null
        pasteSending = false
        pasteUncertain = !delivered
        // A user may have edited or restored another draft while this send was pending.
        if (delivered && draft == paste.text) draft = ""
    }

    var keyBarVisible by mutableStateOf(true)
        private set

    fun toggleKeyBar() {
        keyBarVisible = !keyBarVisible
        if (!keyBarVisible) {
            ctrl = false
            alt = false
        }
    }

    /** Ctrl from the key bar, consumed by the next key. */
    var ctrl by mutableStateOf(false)
        private set

    /** Alt from the key bar, consumed by the next key. */
    var alt by mutableStateOf(false)
        private set

    /** What was typed, in order, until the model sends it. */
    internal val inputs = Channel<PendingTerminalInput>(Channel.UNLIMITED)

    /** A lost attachment must not leave a composition or commands for its replacement. */
    internal fun discardInput(connection: HostConnection? = null) {
        val keep = mutableListOf<PendingTerminalInput>()
        while (true) {
            val pending = inputs.tryReceive().getOrNull() ?: break
            if (connection != null && pending.connection !== connection) keep += pending
        }
        keep.forEach { inputs.trySend(it) }
        if (connection == null || pasteConnection === connection) {
            pendingPaste?.let { pasteFinished(it, delivered = false) }
        }
        if (connection == null || inputConnection === connection) resetInputState()
    }

    private var inputConnection: HostConnection? = null

    private fun resetInputState() {
        echoed = ""
        composing = ""
        ctrl = false
        alt = false
        onCompositionInterrupted?.invoke()
    }

    private fun readyForInput(): Boolean {
        if (!canInput) return false
        if (inputConnection !== link?.connection) {
            resetInputState()
            inputConnection = link?.connection
        }
        return true
    }

    fun startTyping() {
        if (!readyForInput()) return
        typing = true
    }

    fun toggleCtrl() {
        if (!readyForInput()) return
        interruptComposition()
        ctrl = !ctrl
    }

    fun toggleAlt() {
        if (!readyForInput()) return
        interruptComposition()
        alt = !alt
    }

    /**
     * The keyboard's composing text is now [text]. All of it but a trailing
     * Hangul syllable goes to the Terminal right away, so keyboards that
     * compose whole Latin words still type key by key; the syllable is held
     * until the keyboard moves on, as it can still change (한 + ㅏ -> 하나).
     */
    fun compose(text: String) {
        if (!readyForInput()) return
        val last = if (text.isEmpty()) 0 else text.codePointBefore(text.length)
        // A syllable already sent and unchanged (하나 -> 하) stays sent.
        val held = if (isHangul(last) && !echoed.startsWith(text)) Character.charCount(last) else 0
        retype(text.substring(0, text.length - held))
        composing = text.substring(text.length - held)
    }

    /** The keyboard committed [text] in place of what it was composing. */
    fun commit(text: String) {
        if (!readyForInput()) return
        retype(text)
        echoed = ""
        composing = ""
    }

    /** The keyboard finished composing: what it composed stays as is. */
    fun finishComposing() {
        if (readyForInput()) commit(echoed + composing)
    }

    /** Deletes the last [count] characters being composed; false when nothing is. */
    fun deleteComposing(count: Int): Boolean {
        if (!readyForInput()) return false
        val text = echoed + composing
        if (text.isEmpty()) return false
        var end = text.length
        repeat(count) { if (end > 0) end = text.offsetByCodePoints(end, -1) }
        compose(text.substring(0, end))
        return true
    }

    /** Makes the composed text already in the Terminal [target]: Backspace over what differs, type the rest. */
    private fun retype(target: String) {
        var same = 0
        while (same < echoed.length && same < target.length && echoed.codePointAt(same) == target.codePointAt(same)) {
            same += Character.charCount(echoed.codePointAt(same))
        }
        // IME corrections are editing already echoed text, not the user's next modified key.
        repeat(echoed.codePointCount(same, echoed.length)) { send(TerminalInput.Key(TerminalKey.Backspace, NoMods)) }
        typeNow(target.substring(same))
        echoed = target
    }

    /** Typing that is not the keyboard's composing ends the composition first, keeping its text. */
    private fun interruptComposition() {
        if (!readyForInput()) return
        if (echoed.isEmpty() && composing.isEmpty()) return
        finishComposing()
        onCompositionInterrupted?.invoke()
    }

    /** Types [text]; a newline is Enter. Key-bar modifiers apply to the next character only. */
    fun type(text: String) {
        if (!readyForInput()) return
        interruptComposition()
        typeNow(text)
    }

    private fun typeNow(text: String) {
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest[0] == '\n') {
                pressNow(TerminalKey.Enter, NoMods)
                rest = rest.substring(1)
                continue
            }
            if (ctrl || alt) {
                val n = Character.charCount(rest.codePointAt(0))
                pressNow(TerminalKey.Char(rest.substring(0, n)), NoMods)
                rest = rest.substring(n)
                continue
            }
            val end = rest.indexOf('\n').let { if (it < 0) rest.length else it }
            send(TerminalInput.Text(rest.substring(0, end)))
            rest = rest.substring(end)
        }
    }

    /** Presses [key] with active key-bar modifiers and extra [mods]. */
    fun press(key: TerminalKey, mods: KeyMods = NoMods) {
        if (!readyForInput()) return
        interruptComposition()
        pressNow(key, mods)
    }

    private fun pressNow(key: TerminalKey, mods: KeyMods) {
        val modified = mods.copy(ctrl = mods.ctrl || ctrl, alt = mods.alt || alt)
        ctrl = false
        alt = false
        send(TerminalInput.Key(key, modified))
    }

    /** Typing while scrolled back returns to the live screen, as terminals do. */
    private fun send(input: TerminalInput) {
        if (!canInput) return
        if (scrolled != 0) scrollToLive()
        inputs.trySend(PendingTerminalInput(link?.connection, input))
    }

    /** A bounded excerpt; never build the whole screen merely to focus its Canvas. */
    fun cursorLineText(): String? {
        val row = cursor?.row?.toInt() ?: return null
        val runs = line(row)?.runs ?: return null
        val excerpt = StringBuilder()
        for (run in runs) {
            if (excerpt.length >= 240) break
            val column = run.column.toInt().coerceAtMost(240)
            while (excerpt.length < column) excerpt.append(' ')
            excerpt.append(run.text.take(240 - excerpt.length))
        }
        // Avoid exposing half of an emoji when the cap falls between UTF-16 surrogates.
        if (excerpt.lastOrNull()?.isHighSurrogate() == true) excerpt.deleteCharAt(excerpt.lastIndex)
        return excerpt.toString().trimEnd()
    }

    /** The visible text, one string per row (explicit copy/export and tests). */
    fun text(): String = snapshot().text()
}

/** A stable viewport for selection and background output parsing. */
class TerminalSnapshot internal constructor(val cols: Int, private val lines: List<ScreenLine?>) {
    val rows: Int get() = lines.size
    fun line(row: Int): ScreenLine? = lines.getOrNull(row)

    fun text(): String = lines.joinToString("\n") { line ->
        val sb = StringBuilder()
        line?.runs?.forEach { run ->
            while (sb.length < run.column.toInt()) sb.append(' ')
            sb.append(run.text)
        }
        sb.toString().trimEnd()
    }
}

private val NoMods = KeyMods(ctrl = false, alt = false, shift = false)

/** Hangul syllables and jamo: what a Korean keyboard builds up one syllable at a time. */
private fun isHangul(cp: Int): Boolean =
    cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F ||
        cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF

/** One thing typed into a Terminal. */
sealed interface TerminalInput {
    data class Text(val text: String) : TerminalInput
    data class Paste(val text: String) : TerminalInput
    data class Key(val key: TerminalKey, val mods: KeyMods) : TerminalInput
}

/** Input belongs to the line on which it was typed, including before the typing attach. */
internal data class PendingTerminalInput(val connection: HostConnection?, val input: TerminalInput)

private val TerminalBackground = Color(0xFF101214)
private val TerminalForeground = Color(0xFFD8DADC)
private val SelectionColor = Color(0x664D8DFF)

@Composable
fun TerminalScreen(
    viewer: TerminalViewer,
    onBack: () -> Unit,
    onView: ((AgentView) -> Unit)? = null,
    displaySettings: TerminalDisplaySettings = rememberTerminalDisplaySettings(),
    onSelectionSend: ((String) -> Unit)? = null,
    onPreviewCapture: ((SharedContent, () -> Unit) -> Boolean)? = null,
) {
    val preview = rememberTerminalLocalhostPreview(viewer)
    var searching by remember(viewer) { mutableStateOf(false) }
    // The keyboard pushes the input and key bar up and shrinks the screen
    // (the Scaffold already keeps clear of the navigation bar).
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalUriHandler provides rememberAgentUriHandler(preview, viewer.link, viewer.terminal.address),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
            AgentScreenHeader(viewer.terminal, viewer.link?.host?.name, onBack, AgentView.TERMINAL, onView,
                title = viewer.terminal.name?.takeIf { it.isNotBlank() } ?: viewer.title?.takeIf { it.isNotBlank() } ?: displayName(viewer.terminal),
                preview = preview, link = viewer.link, onTerminalUpdate = { viewer.update(listOf(it)) }, onSearch = { searching = true })
            PreviewChips(preview)
            if (preview.selected != null) {
                LocalhostPreviewBrowser(preview, viewer.link, Modifier.weight(1f).fillMaxWidth(), change = viewer.terminal.turn,
                    captureEnabled = viewer.terminal.state == "live",
                    onAttachCapture = onPreviewCapture.takeIf { viewer.terminal.agentStatus != null })
            } else {
                val look = remember(viewer) { TerminalLook(viewer) }
                val scope = rememberCoroutineScope()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    TerminalCanvas(viewer, look, displaySettings, Modifier.fillMaxSize())
                    // Transient controls must not change the Host's grid or move a selection.
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                        TerminalSearchBar(viewer, searching, onClose = { searching = false })
                        viewer.exit?.let { Banner(exitText(it)) }
                        val line = viewer.link?.state
                        if (viewer.exit == null) viewer.link?.let {
                            ConnectionNotice(it)
                            if (line == LinkState.Up) viewer.watchError?.let { error -> WatchFailureNotice(it, error) }
                        }
                        if (viewer.exit == null && viewer.closed && (line == null || line == LinkState.Up)) {
                            Banner(stringResource(R.string.terminal_connection_closed))
                        }
                    }
                    Box(Modifier.align(Alignment.BottomStart)) {
                        TerminalLookBar(viewer, look, onSelectionSend)
                    }
                    if (viewer.scrolled > 0 && look.selection == null) {
                        LatestButton(
                            description = stringResource(R.string.terminal_back_to_live),
                            onClick = { scope.launch { viewer.animateToLive() } },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                        )
                    }
                }
                TerminalComposer(viewer)
                if (viewer.typing && viewer.keyBarVisible) TerminalKeyBar(viewer)
            }
        }
    }
}

/** The input field under the screen: tap it to type (and to get the keyboard). */
@Composable
internal fun TerminalInput(viewer: TerminalViewer, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() } + 24.dp).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline),
        contentAlignment = Alignment.CenterStart,
    ) {
        AndroidView(
            factory = { TerminalInputView(it, viewer) },
            update = { it.viewer = viewer; it.updateAvailability() },
            modifier = Modifier.matchParentSize(),
        )
        val composing = viewer.composing
        val unavailable = viewer.inputUnavailableReason
        Text(
            when {
                unavailable != null -> stringResource(unavailable)
                composing.isNotEmpty() -> composing
                viewer.typing -> stringResource(R.string.terminal_input_typing)
                else -> stringResource(R.string.terminal_input_hint)
            },
            Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
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
    AnnouncedText(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TerminalCanvas(viewer: TerminalViewer, look: TerminalLook, settings: TerminalDisplaySettings, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val family = settings.font.family
    // Advance width and line height of one monospace cell at 100sp (in px).
    val probe = remember(measurer, family) {
        measurer.measure("M", TextStyle(fontFamily = family, fontSize = 100.sp))
    }
    val cursorLine = viewer.version.let { viewer.cursorLineText() }
    val summary = stringResource(R.string.terminal_accessibility_summary, viewer.cols, viewer.rows)
    val description = if (cursorLine == null) summary else summary + ". " +
        stringResource(R.string.terminal_accessibility_cursor, viewer.cursor!!.row.toInt() + 1, cursorLine)
    val selected = look.selection?.snapshot
    BoxWithConstraints(modifier.background(TerminalBackground)) {
        val cols = (selected?.cols ?: viewer.cols).coerceAtLeast(1)
        val widthPx = with(density) { maxWidth.toPx() }
        val per100 = probe.size.width.toFloat().coerceAtLeast(1f)
        // The probe's font size in px: cell sizes scale with font px over it.
        val probePx = with(density) { 100.sp.toPx() }
        val maxPx = with(density) { settings.fontSize.sp.toPx() }
        // Typing: the phone sets the grid, at the base text size. Watching:
        // the desktop's grid, shrunk to fit the width.
        val baseFontPx = if (viewer.typing) maxPx else minOf(maxPx, widthPx / cols / per100 * probePx)
        val baseCellW = per100 * baseFontPx / probePx
        val baseCellH = (probe.size.height * baseFontPx / probePx).coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }
        if (viewer.typing) {
            // The Canvas follows the animated inset for drawing. The Host only
            // needs the final keyboard height, independent of animation frames.
            val currentIme = WindowInsets.ime.exclude(WindowInsets.navigationBars).getBottom(density)
            val targetIme = WindowInsets.imeAnimationTarget.exclude(WindowInsets.navigationBars).getBottom(density)
            val targetHeight = heightPx + currentIme - targetIme
            val grid = (widthPx / baseCellW).toInt().coerceAtLeast(20) to (targetHeight / baseCellH).toInt().coerceAtLeast(4)
            LaunchedEffect(viewer, grid) {
                delay(200)
                viewer.phoneGrid = grid
            }
        }
        look.layout(baseCellW, baseCellH, widthPx, heightPx)
        if (WindowInsets.ime.getBottom(density) > 0) look.revealCursor()
        LaunchedEffect(viewer.search, look.selection, look.cellW, look.cellH, heightPx) { look.revealSearchMatch() }
        // Zoom and pan: the phone's view only, the grid stays the Terminal's.
        val fontPx = baseFontPx * look.zoom
        val cellW = look.cellW
        val cellH = look.cellH
        val fontSize = with(density) { fontPx.toSp() }
        val minimumContrast = settings.minimumContrast
        Canvas(
            Modifier.fillMaxSize().clipToBounds().testTag("terminal-screen")
                .semantics { contentDescription = description }
                .terminalGestures(look, viewer),
        ) {
            viewer.version // redraw on every frame
            val base = TextStyle(fontFamily = family, fontSize = fontSize, color = TerminalForeground)
            translate(look.panX, look.panY) {
            for (row in 0 until (selected?.rows ?: viewer.rows)) {
                val y = row * cellH
                for (run in (if (selected != null) selected.line(row) else viewer.line(row))?.runs.orEmpty()) {
                    val x = run.column.toInt() * cellW
                    var fg = run.fg?.let(::rgb) ?: TerminalForeground
                    var bg = run.bg?.let(::rgb)
                    if (run.inverse) {
                        val f = fg
                        fg = bg ?: TerminalBackground
                        bg = f
                    }
                    bg?.let { drawRect(it, Offset(x, y), Size(run.width.toInt() * cellW, cellH)) }
                    if (run.wide || run.text.isNotBlank()) {
                        for (span in terminalTextSpans(run, fg, bg ?: TerminalBackground, minimumContrast)) {
                            val ink = if (run.dim) span.foreground.copy(alpha = 0.6f) else span.foreground
                            drawTerminalRun(measurer, span.text, Offset(x + span.column * cellW, y), styleOf(base, run, ink), cellW, run.wide)
                        }
                    }
                }
            }
            for (mark in (if (selected == null) viewer.search?.highlights else null).orEmpty()) {
                drawRect(
                    if (mark.current) Color(0x99FFB300) else Color(0x55FFE082),
                    Offset(mark.column.toInt() * cellW, mark.row.toInt() * cellH),
                    Size(mark.width.toInt() * cellW, cellH),
                )
            }
            viewer.cursor?.takeIf { selected == null }?.let { c ->
                drawRect(
                    TerminalForeground.copy(alpha = 0.5f),
                    Offset(c.column.toInt() * cellW, c.row.toInt() * cellH),
                    Size(cellW, cellH),
                )
                // The syllable still being composed, where it will land (two cells, as Hangul is wide).
                if (viewer.composing.isNotEmpty()) {
                    val at = Offset(c.column.toInt() * cellW, c.row.toInt() * cellH)
                    drawRect(TerminalForeground, at, Size(2 * cellW, cellH))
                    drawTerminalText(measurer, viewer.composing, at, base.copy(color = TerminalBackground, textDecoration = TextDecoration.Underline))
                }
            }
            look.selection?.ordered()?.let { s ->
                for (row in s.start.row..s.end.row) {
                    val from = if (row == s.start.row) s.start.col else 0
                    val to = if (row == s.end.row) s.end.col else cols - 1
                    drawRect(
                        SelectionColor,
                        Offset(from * cellW, row * cellH),
                        Size((to - from + 1) * cellW, cellH),
                    )
                }
            }
            }
        }
    }
}

private fun DrawScope.drawTerminalText(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    // TextMeasurer caches layout independently of color. Supply paint properties
    // explicitly so a color change also repaints already measured Terminal text.
    drawText(measurer.measure(text, style), color = style.color, topLeft = at, textDecoration = style.textDecoration)
}

private fun DrawScope.drawTerminalRun(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle, cellW: Float, wide: Boolean) {
    if (text.all { it.code < 128 }) {
        drawTerminalText(measurer, text, at, style)
        return
    }
    // Fallback fonts can have a different advance. Place each grapheme on its
    // Terminal cell so a symbol never shifts the text that follows it.
    val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var start = graphemes.first()
    var end = graphemes.next()
    var column = 0
    while (end != BreakIterator.DONE) {
        drawTerminalText(measurer, text.substring(start, end), at + Offset(column * cellW, 0f), style)
        column += if (wide) 2 else 1
        start = end
        end = graphemes.next()
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
