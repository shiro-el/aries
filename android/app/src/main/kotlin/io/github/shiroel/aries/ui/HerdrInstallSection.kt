package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HerdrCoreState
import io.github.shiroel.aries.core.HerdrInstallStep

@Composable
fun HerdrInstallSection(model: HerdrInstallModel, connect: () -> Unit) {
    if (model.host?.endpoint !is Endpoint.Ssh) return
    var advanced by rememberSaveable(model.host?.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.herdr_install_title), style = MaterialTheme.typography.titleMedium)
        val state = model.state
        val healthy = state is HerdrCoreState.Installed && !state.needsUpdate
        when (state) {
            HerdrCoreState.Missing -> Text(stringResource(R.string.herdr_install_missing))
            is HerdrCoreState.Installed -> {
                if (state.needsUpdate) {
                    Text(stringResource(R.string.herdr_install_version, state.version))
                    Text(stringResource(R.string.herdr_install_old, model.minimumCoreVersion))
                } else Text(stringResource(R.string.herdr_install_ready))
            }
            null -> Unit
        }
        if (model.busy) {
            CircularProgressIndicator()
            model.step?.let {
                Text(stringResource(when (it) {
                    HerdrInstallStep.DETECT -> R.string.herdr_install_detect
                    HerdrInstallStep.DOWNLOAD -> R.string.herdr_install_download
                    HerdrInstallStep.VERIFY -> R.string.herdr_install_verify
                    HerdrInstallStep.INSTALL -> R.string.herdr_install_working
                    HerdrInstallStep.DONE -> R.string.herdr_install_done
                }))
            }
        } else {
            if (state == HerdrCoreState.Missing || (state is HerdrCoreState.Installed && state.needsUpdate)) {
                TextButton(onClick = model::askInstall) {
                    Text(stringResource(if (state == HerdrCoreState.Missing) R.string.herdr_install_button else R.string.herdr_install_update))
                }
            }
            if (model.step == HerdrInstallStep.DONE) {
                Text(stringResource(R.string.herdr_install_done))
                TextButton(onClick = connect) { Text(stringResource(R.string.herdr_install_connect)) }
            }
            if (!healthy || advanced) {
                TextButton(onClick = model::refresh) { Text(stringResource(R.string.herdr_install_refresh)) }
            }
        }
        model.problem?.let {
            AnnouncedText(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("herdr-advanced")) {
            Text(stringResource(if (advanced) R.string.settings_less_button else R.string.settings_advanced_button))
        }
        if (advanced && state is HerdrCoreState.Installed && healthy) {
            Text(stringResource(R.string.herdr_install_version, state.version))
        }
        if (advanced && model.output.isNotBlank()) {
            SelectionContainer { Text(model.output, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp)) }
        }
    }
    if (model.confirm) {
        AlertDialog(
            onDismissRequest = model::cancel,
            title = { Text(stringResource(R.string.herdr_install_consent_title)) },
            text = { Text(stringResource(R.string.herdr_install_consent_body)) },
            confirmButton = { TextButton(onClick = model::install) { Text(stringResource(R.string.herdr_install_confirm)) } },
            dismissButton = { TextButton(onClick = model::cancel) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}
