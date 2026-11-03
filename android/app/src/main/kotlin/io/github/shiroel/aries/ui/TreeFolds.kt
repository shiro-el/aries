package io.github.shiroel.aries.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Phone-only expansion state, shared by the sidebar and Agent tab, survives relaunch. */
internal class TreeFolds(private val preferences: SharedPreferences, private val scope: String) {
    private var folded by mutableStateOf(preferences.getStringSet(scope, emptySet()).orEmpty().toSet())
    fun contains(key: String) = key in folded
    fun toggle(key: String) {
        val next = if (contains(key)) folded - key else folded + key
        folded = next
        preferences.edit().putStringSet(scope, next).apply()
    }
    fun expand(key: String) {
        if (contains(key)) toggle(key)
    }
    fun reload() { folded = preferences.getStringSet(scope, emptySet()).orEmpty().toSet() }
}

@Composable
internal fun rememberTreeFolds(scope: String): TreeFolds {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("tree-expansion", Context.MODE_PRIVATE) }
    val folds = remember(preferences, scope) { TreeFolds(preferences, scope) }
    DisposableEffect(preferences, folds) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> folds.reload() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return folds
}

internal val AgentKey.foldKey get() = "agent:$hostId\n$address"
