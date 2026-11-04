package io.github.shiroel.aries.speech

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import java.util.Locale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

@Composable
fun ReadAloudButton(state: ReadAloudState, id: String, source: String) {
    IconButton(
        onClick = { if (state.activeId == id) state.stop() else state.read(id, source) },
        enabled = state.ready,
        modifier = Modifier.testTag("read-aloud:$id"),
    ) {
        Icon(painterResource(if (state.activeId == id) R.drawable.ic_square else R.drawable.ic_volume),
            stringResource(if (state.activeId == id) R.string.read_aloud_stop else if (!state.ready && state.notice == null) R.string.read_aloud_loading else R.string.read_aloud_button),
            Modifier.size(18.dp))
    }
}

/** Stays outside the lazy transcript so Stop remains reachable while scrolling. */
@Composable
fun ReadAloudPlayback(state: ReadAloudState, showSpeed: Boolean = true, showInactiveNotice: Boolean = true) {
    val active = state.activeId != null
    if (active || (showInactiveNotice && state.notice != null)) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val status = if (active) {
            if (state.speaking) R.string.read_aloud_playing else R.string.read_aloud_preparing
        } else state.notice
        status?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
        if (active) {
            TextButton(onClick = state::stop, modifier = Modifier.testTag("read-aloud-stop")) { Text(stringResource(R.string.read_aloud_stop)) }
            if (showSpeed) ReadAloudSpeed(state)
        }
    }
}

@Composable
private fun ReadAloudSpeed(state: ReadAloudState) {
    Text(stringResource(R.string.read_aloud_speed, state.rate), style = MaterialTheme.typography.bodySmall)
    val label = stringResource(R.string.read_aloud_speed_label)
    Slider(
        value = state.rate, onValueChange = state::chooseRate, valueRange = .5f..2f, steps = 5,
        modifier = Modifier.testTag("read-aloud-speed").semantics { contentDescription = label },
    )
}

@Composable
fun ReadAloudSettings(state: ReadAloudState) {
    var picker by remember { mutableStateOf(false) }
    Text(stringResource(R.string.read_aloud_title), style = MaterialTheme.typography.titleSmall)
    ReadAloudSpeed(state)
    val selected = state.voices.find { it.id == state.voice }
    TextButton(onClick = { picker = true }, enabled = state.ready, modifier = Modifier.testTag("read-aloud-voice")) {
        Text(stringResource(R.string.read_aloud_voice, selected?.let { "${it.name} (${it.languageTag})" } ?: stringResource(R.string.read_aloud_default_voice)))
    }
    val context = LocalContext.current
    val currentConfiguration = LocalConfiguration.current
    val defaultSample = stringResource(R.string.read_aloud_sample)
    val sample = remember(context, currentConfiguration, selected, defaultSample) {
        selected?.let {
            val configuration = Configuration(currentConfiguration).apply { setLocale(Locale.forLanguageTag(it.languageTag)) }
            context.createConfigurationContext(configuration).getString(R.string.read_aloud_sample)
        } ?: defaultSample
    }
    TextButton(
        onClick = { if (state.activeId != null) state.stop() else state.read("preview", sample) },
        enabled = state.ready, modifier = Modifier.testTag("read-aloud-preview"),
    ) { Text(stringResource(if (state.activeId != null) R.string.read_aloud_stop else R.string.read_aloud_preview)) }
    ReadAloudPlayback(state, showSpeed = false)
    if (picker) AlertDialog(
        onDismissRequest = { picker = false },
        title = { Text(stringResource(R.string.read_aloud_voice_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp).selectableGroup()) {
                item {
                    VoiceRow(stringResource(R.string.read_aloud_default_voice), state.voice == null) {
                        state.chooseVoice(null); picker = false
                    }
                }
                items(state.voices, key = { it.id }) { voice ->
                    val label = stringResource(
                        R.string.read_aloud_voice_label, voice.name, voice.languageTag,
                        stringResource(if (voice.network) R.string.read_aloud_voice_network else R.string.read_aloud_voice_local),
                    )
                    VoiceRow(label, state.voice == voice.id) { state.chooseVoice(voice.id); picker = false }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.back_button)) } },
    )
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    io.github.shiroel.aries.ui.SingleChoiceRow(selected, onClick) { Text(label) }
}
