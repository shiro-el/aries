package io.github.shiroel.aries.guidance

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

val LocalAppGuidance = staticCompositionLocalOf<AppGuidanceState?> { null }

@Composable
fun AppGuidance(
    state: AppGuidanceState,
    content: @Composable () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var downloadFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        state.release?.let { release ->
            val openDownload = {
                try {
                    uriHandler.openUri(release.apkUrl)
                    downloadFailed = false
                } catch (_: ActivityNotFoundException) {
                    downloadFailed = true
                } catch (_: IllegalArgumentException) {
                    downloadFailed = true
                }
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().statusBarsPadding().testTag("app-update-banner")
                    .clickable(onClick = openDownload).padding(12.dp)) {
                    Text(stringResource(R.string.app_update_available, release.versionName))
                    TextButton(onClick = openDownload, modifier = Modifier.testTag("app-update-download")) {
                        Text(stringResource(R.string.app_update_download_button))
                    }
                    if (downloadFailed) Text(stringResource(R.string.app_update_browser_failed))
                }
            }
        }
        val consumedStatusBar = if (state.release != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Box(Modifier.weight(1f).fillMaxWidth().then(consumedStatusBar)) {
            CompositionLocalProvider(LocalAppGuidance provides state) { content() }
        }
    }
    if (state.onboardingStep == null && state.showWhatsNew) {
        AlertDialog(
            modifier = Modifier.testTag("whats-new-dialog"),
            onDismissRequest = state::dismissWhatsNew,
            title = { Text(stringResource(R.string.whats_new_title, state.versionName)) },
            text = { Text(stringResource(R.string.whats_new_body), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(state::dismissWhatsNew, Modifier.testTag("whats-new-dismiss")) {
                    Text(stringResource(R.string.ok_button))
                }
            },
        )
    }
}
