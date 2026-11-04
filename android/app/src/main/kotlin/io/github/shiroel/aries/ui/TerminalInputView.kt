package io.github.shiroel.aries.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.shiroel.aries.core.KeyMods
import io.github.shiroel.aries.core.TerminalKey

/**
 * The Terminal's input field: a plain focusable View so the soft keyboard
 * talks to our own [TerminalInputConnection] instead of a text editor.
 * Tapping it focuses it and shows the keyboard. The first key or paste starts
 * typing mode, so opening the keyboard alone preserves the desktop's grid.
 */
@SuppressLint("ViewConstructor")
class TerminalInputView(context: Context, viewer: TerminalViewer) : View(context) {
    private val interruptKeyboard: () -> Unit = ::resetKeyboard
    private var keyboardConnection = viewer.link?.connection
    var viewer: TerminalViewer = viewer
        set(value) {
            if (field !== value && field.onCompositionInterrupted === interruptKeyboard) field.onCompositionInterrupted = null
            field = value
            value.onCompositionInterrupted = interruptKeyboard
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { showKeyboard() }
        viewer.onCompositionInterrupted = interruptKeyboard
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewer.onCompositionInterrupted = interruptKeyboard
    }

    override fun onDetachedFromWindow() {
        if (viewer.onCompositionInterrupted === interruptKeyboard) viewer.onCompositionInterrupted = null
        super.onDetachedFromWindow()
    }

    fun showKeyboard() {
        if (!viewer.canInput) return
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    /** The key bar ended a composition: the keyboard starts afresh instead of extending it. */
    private fun resetKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
    }

    fun updateAvailability() {
        val enabled = viewer.canInput
        val connection = viewer.link?.connection
        if (isEnabled == enabled && keyboardConnection === connection) return
        keyboardConnection = connection
        isEnabled = enabled
        if (!enabled) {
            clearFocus()
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        }
        resetKeyboard()
    }

    override fun onCheckIsTextEditor(): Boolean = isEnabled && viewer.canInput

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!viewer.canInput) return null
        // Plain text, so keyboards keep their Korean layout (visible-password
        // makes Gboard and Samsung Keyboard Latin-only). No suggestions, so
        // Latin keys mostly arrive one by one; when a keyboard composes words
        // anyway, TerminalViewer.compose still sends them key by key. No
        // learning: passwords are typed here too.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, viewer)
    }

    /** Hardware keys (and `adb shell input`) arrive here, not through the IME. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        sendKey(viewer, event) || super.onKeyDown(keyCode, event)
}

/**
 * What the keyboard types, turned into Terminal input on [viewer]: committed
 * and composing text go through [TerminalViewer.commit] and
 * [TerminalViewer.compose] (a Hangul syllable waits until complete), deletes
 * outside a composition become Backspace/Delete, and key events become
 * [TerminalKey]s. The editor stays empty: the Terminal is the text.
 */
class TerminalInputConnection(view: View, private val viewer: TerminalViewer) : BaseInputConnection(view, true) {
    private val connection = viewer.link?.connection
    private val generation = viewer.inputGeneration
    private fun currentLine(): Boolean = viewer.canInput && viewer.link?.connection === connection &&
        viewer.inputGeneration == generation

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (!currentLine()) return true
        if (text.isNotEmpty()) viewer.startTyping()
        viewer.commit(text.toString())
        editable?.clear()
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (!currentLine()) return true
        if (text.isNotEmpty()) viewer.startTyping()
        viewer.compose(text.toString())
        return true
    }

    override fun finishComposingText(): Boolean {
        if (!currentLine()) return true
        viewer.finishComposing()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!currentLine()) return true
        if (beforeLength > 0 || afterLength > 0) viewer.startTyping()
        if (viewer.deleteComposing(beforeLength)) return true
        repeat(beforeLength) { viewer.press(TerminalKey.Backspace) }
        repeat(afterLength) { viewer.press(TerminalKey.Delete) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (!currentLine()) return true
        if (event.action == KeyEvent.ACTION_DOWN) sendKey(viewer, event)
        return true
    }
}

/** Sends a key-down [event] to [viewer]; false when it is not a key a Terminal takes. */
internal fun sendKey(viewer: TerminalViewer, event: KeyEvent): Boolean {
    val mods = KeyMods(ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed)
    val key = when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.Enter
        KeyEvent.KEYCODE_DEL -> TerminalKey.Backspace
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.Delete
        KeyEvent.KEYCODE_TAB -> TerminalKey.Tab
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.Escape
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.Right
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.Home
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.End
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PageDown
        KeyEvent.KEYCODE_INSERT -> TerminalKey.Insert
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            TerminalKey.F((event.keyCode - KeyEvent.KEYCODE_F1 + 1).toUByte())
        else -> null
    }
    if (key != null) {
        // Shift only matters to Tab and the cursor/function keys, not to Enter.
        viewer.startTyping()
        viewer.press(key, mods)
        return true
    }
    // A character: what the key types without Ctrl/Alt (they are sent as mods).
    val ch = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
    if (ch == 0 || ch and KeyCharacterMapCombining != 0) return false
    val text = String(Character.toChars(ch))
    viewer.startTyping()
    if (mods.ctrl || mods.alt) {
        viewer.press(TerminalKey.Char(text), mods.copy(shift = false))
    } else {
        viewer.type(text)
    }
    return true
}

private const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
