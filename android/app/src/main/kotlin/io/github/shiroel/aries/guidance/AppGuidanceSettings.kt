package io.github.shiroel.aries.guidance

import io.github.shiroel.aries.ui.ConsoleField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import kotlinx.coroutines.launch

@Composable
fun AppGuidanceSettings(state: AppGuidanceState) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable(state.updateUrl) { mutableStateOf(state.updateUrl) }
    val valid = url.isBlank() || isAppUpdateUrl(url.trim())
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.app_update_settings_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.app_update_installed, state.versionName, state.versionCode))
        TextButton(state::openOnboarding, Modifier.testTag("guidance-open")) { Text(stringResource(R.string.onboarding_open_button)) }
        TextButton(state::openWhatsNew, Modifier.testTag("whats-new-open")) { Text(stringResource(R.string.whats_new_open_button)) }
        ConsoleField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.app_update_url_label)) },
            modifier = Modifier.fillMaxWidth().testTag("app-update-url"),
            singleLine = true,
            isError = !valid,
            supportingText = if (!valid) ({ Text(stringResource(R.string.app_update_url_invalid)) }) else ({ Text(stringResource(R.string.app_update_url_hint)) }),
        )
        TextButton(onClick = { state.setUpdateUrl(url) }, enabled = valid,
            modifier = Modifier.testTag("app-update-save")) {
            Text(stringResource(R.string.app_update_save_button))
        }
        TextButton(onClick = { scope.launch { state.checkForUpdate() } },
            enabled = state.updateUrl.isNotEmpty() && state.updateCheck != AppUpdateCheck.CHECKING && url.trim() == state.updateUrl,
            modifier = Modifier.testTag("app-update-check")) {
            Text(stringResource(R.string.app_update_check_button))
        }
        val status = when (state.updateCheck) {
            AppUpdateCheck.IDLE -> null
            AppUpdateCheck.CHECKING -> R.string.app_update_checking
            AppUpdateCheck.CURRENT -> R.string.app_update_current
            AppUpdateCheck.AVAILABLE -> R.string.app_update_found
            AppUpdateCheck.FAILED -> R.string.app_update_failed
        }
        status?.let { Text(stringResource(it), Modifier.testTag("app-update-status")) }
        state.release?.let { release ->
            Text(stringResource(R.string.app_update_available, release.versionName))
            Text(release.notes)
        }
    }
}
