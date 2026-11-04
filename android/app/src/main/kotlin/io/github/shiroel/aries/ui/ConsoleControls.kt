package io.github.shiroel.aries.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A single switch target, or a decorative indicator when its parent row owns the action. */
@Composable
internal fun ConsoleToggle(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius.coerceAtMost(4.dp))
    val action = if (onCheckedChange == null) Modifier else Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    Box(modifier.then(action), contentAlignment = Alignment.Center) {
        val ink = (if (checked) colors.primary else colors.outline).copy(alpha = if (enabled) 1f else .38f)
        Box(Modifier.size(36.dp, 22.dp).border(1.dp, ink, shape).padding(4.dp)) {
            Box(Modifier.size(12.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .background(if (checked) ink else colors.onSurfaceVariant.copy(alpha = if (enabled) .6f else .38f), shape))
        }
    }
}

/** Label above the outline: it never moves or cuts a notch when focus/value changes. */
@Composable
internal fun ConsoleField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    shape: Shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius),
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    ConsoleField(fieldValue.copy(text = value), { next ->
        fieldValue = next
        if (next.text != value) onValueChange(next.text)
    }, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon, supportingText,
        isError, visualTransformation, keyboardOptions, keyboardActions, singleLine, maxLines, minLines, shape)
}

/** Label above the outline: it never moves or cuts a notch when focus/value changes. */
@Composable
internal fun ConsoleField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    shape: Shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius),
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border = when { isError -> colors.error; focused -> colors.primary; else -> colors.outline }
    val invalidLabel = stringResource(R.string.console_field_invalid)
    val ink = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f)
    BasicTextField(
        value, onValueChange, modifier.defaultMinSize(minWidth = 120.dp).semantics { if (isError) error(invalidLabel) }, enabled, readOnly,
        textStyle.merge(TextStyle(color = ink)), keyboardOptions, keyboardActions,
        singleLine, maxLines, minLines, visualTransformation, interactionSource = interactions,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { input ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label != null) CompositionLocalProvider(LocalContentColor provides if (isError) colors.error else colors.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall, label)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(if (focused) 2.dp else 1.dp, border, shape)
                    .background(colors.surfaceContainerLow, shape).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    leadingIcon?.invoke()
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty() && placeholder != null) CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { placeholder() }
                        input()
                    }
                    trailingIcon?.invoke()
                }
                if (supportingText != null) CompositionLocalProvider(LocalContentColor provides if (isError) colors.error else colors.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall, supportingText)
                }
            }
        },
    )
}

/** All busy states use the same flat strip; compact slots show three square cells. */
@Composable
internal fun ConsoleProgress(
    modifier: Modifier = Modifier, progress: (() -> Float)? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val position by rememberInfiniteTransition(label = "console-progress").animateFloat( // i18n-ok: animation tooling label
        0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "position", // i18n-ok: animation tooling label
    )
    val semantics = if (progress == null) Modifier.progressSemantics() else Modifier.progressSemantics(progress().coerceIn(0f, 1f))
    Canvas(modifier.then(semantics).fillMaxWidth().height(3.dp)) {
        drawRect(color.copy(alpha = .18f))
        if (progress != null) drawRect(color, size = Size(size.width * progress().coerceIn(0f, 1f), size.height))
        else {
            val block = size.width / 4
            val left = position * (size.width + block) - block
            val start = left.coerceAtLeast(0f)
            val end = (left + block).coerceAtMost(size.width)
            drawRect(color, Offset(start, 0f), Size((end - start).coerceAtLeast(0f), size.height))
        }
    }
}

@Composable
internal fun ConsoleBusyIndicator(modifier: Modifier = Modifier.size(24.dp), strokeWidth: Dp = 2.dp) {
    val position by rememberInfiniteTransition(label = "console-busy").animateFloat( // i18n-ok: animation tooling label
        0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "cell", // i18n-ok: animation tooling label
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.progressSemantics()) {
        val cell = size.width / 5
        repeat(3) { index ->
            drawRect(color.copy(alpha = if (position.toInt() == index) 1f else .25f),
                Offset(index * cell * 2, (size.height - cell) / 2), Size(cell, maxOf(cell, strokeWidth.toPx())))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsoleSheet(
    onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest, modifier, sheetState,
        shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 2.dp).background(MaterialTheme.colorScheme.outline))
            }
        }, content = content)
}
