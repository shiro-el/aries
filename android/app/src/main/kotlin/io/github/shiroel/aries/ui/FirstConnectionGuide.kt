package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.guidance.AppGuidanceState
import kotlinx.coroutines.launch

/** Uses the real Host flow; only a successful connection unlocks Beacon and push setup. */
@Composable
internal fun FirstConnectionGuide(
    model: HostsModel,
    state: AppGuidanceState?,
    allowNotifications: suspend () -> Boolean,
    content: @Composable () -> Unit,
) {
    val step = state?.onboardingStep
    if (state == null || step == null) {
        content()
        return
    }
    val browser = (model.screen as? Screen.Browse)?.browser
    LaunchedEffect(browser, step) {
        if (browser != null && step == 1) state.nextOnboardingStep()
    }
    BackHandler(enabled = step == 0 || (step == 2 && browser != null)) { state.finishOnboarding() }
    when {
        step == 0 -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.onboarding_welcome_body))
            TextButton(onClick = {
                model.startAddingHost(HostAddMethod.QR)
                state.nextOnboardingStep()
            }, modifier = Modifier.testTag("onboarding-qr")) { Text(stringResource(R.string.onboarding_qr_button)) }
            TextButton(onClick = {
                model.startAddingHost(HostAddMethod.MANUAL)
                state.nextOnboardingStep()
            }, modifier = Modifier.testTag("onboarding-manual")) { Text(stringResource(R.string.onboarding_manual_button)) }
            if (model.hosts.isNotEmpty()) TextButton(onClick = {
                model.back()
                state.nextOnboardingStep()
            }) { Text(stringResource(R.string.onboarding_hosts_button)) }
            SkipGuide(state)
        }
        step == 2 && browser != null && model.beacon.host?.id == browser.host.id -> {
            val scope = rememberCoroutineScope()
            var requesting by remember { mutableStateOf(false) }
            var allowed by remember { mutableStateOf<Boolean?>(null) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.onboarding_connected, browser.host.name),
                    Modifier.testTag("onboarding-connected"), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.onboarding_beacon_body))
                BeaconSection(model.beacon)
                TextButton(enabled = !requesting, modifier = Modifier.testTag("onboarding-permission"), onClick = {
                    scope.launch {
                        requesting = true
                        try { allowed = allowNotifications() } finally { requesting = false }
                    }
                }) { Text(stringResource(R.string.onboarding_permission_button)) }
                allowed?.let {
                    Text(stringResource(if (it) R.string.onboarding_permission_granted else R.string.onboarding_permission_denied))
                }
                PushSection(model.push, beaconInstalled = model.beacon.installed)
                TextButton(state::finishOnboarding, Modifier.testTag("onboarding-finish")) {
                    Text(stringResource(R.string.onboarding_finish_button))
                }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.onboarding_connect_body), style = MaterialTheme.typography.bodySmall)
                SkipGuide(state)
            }
            Column(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun SkipGuide(state: AppGuidanceState) {
    TextButton(state::finishOnboarding, Modifier.testTag("onboarding-skip")) {
        Text(stringResource(R.string.onboarding_skip_button))
    }
}
