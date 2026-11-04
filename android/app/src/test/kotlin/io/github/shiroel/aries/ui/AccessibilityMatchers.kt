package io.github.shiroel.aries.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher

/** The action stays named while the row's actual text remains readable. */
internal fun hasToolAction(label: String) = SemanticsMatcher("Click action: $label") {
    it.config.getOrNull(SemanticsActions.OnClick)?.label == label
}
