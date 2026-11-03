package io.github.shiroel.aries.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

@Composable
fun AppLockSettings(session: AppLockSession) {
    val lock = session.lock
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.app_lock_settings_title), style = MaterialTheme.typography.titleSmall)
        LockToggle(R.string.app_lock_enabled_label, "app-lock-enabled", lock.enabled, !session.busy) {
            if (it) session.enable() else session.disable()
        }
        Text(stringResource(R.string.app_lock_settings_hint), style = MaterialTheme.typography.bodySmall)
        AppLockNotice(session)
        if (lock.enabled) {
            Text(stringResource(R.string.app_lock_delay_label))
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                LockDelay.entries.forEach { delay ->
                    val selected = lock.delay == delay
                    Column(
                        Modifier.weight(1f).testTag("app-lock-delay-${delay.name}")
                            .selectable(selected, enabled = !session.busy, role = Role.RadioButton, onClick = { lock.chooseDelay(delay) })
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RadioButton(selected, onClick = null)
                        Text(stringResource(when (delay) {
                            LockDelay.IMMEDIATE -> R.string.app_lock_delay_immediate
                            LockDelay.ONE_MINUTE -> R.string.app_lock_delay_one_minute
                            LockDelay.FIVE_MINUTES -> R.string.app_lock_delay_five_minutes
                        }))
                    }
                }
            }
            LockToggle(R.string.app_lock_previews_label, "app-lock-previews", lock.hidePreviews, !session.busy, lock::chooseHidePreviews)
            Text(stringResource(R.string.app_lock_previews_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LockToggle(label: Int, tag: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
