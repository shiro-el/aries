package io.github.shiroel.aries.security

import io.github.shiroel.aries.ui.ConsoleToggle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
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
        AppLockNotice(session)
        if (lock.enabled) {
            Text(stringResource(R.string.app_lock_delay_label))
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                LockDelay.entries.forEach { delay ->
                    io.github.shiroel.aries.ui.SingleChoiceRow(
                        selected = lock.delay == delay,
                        onClick = { lock.chooseDelay(delay) }, enabled = !session.busy,
                        modifier = Modifier.fillMaxWidth().testTag("app-lock-delay-${delay.name}"),
                    ) {
                        Text(stringResource(when (delay) {
                            LockDelay.IMMEDIATE -> R.string.app_lock_delay_immediate
                            LockDelay.ONE_MINUTE -> R.string.app_lock_delay_one_minute
                            LockDelay.FIVE_MINUTES -> R.string.app_lock_delay_five_minutes
                        }))
                    }
                }
            }
            LockToggle(R.string.app_lock_previews_label, "app-lock-previews", lock.hidePreviews, !session.busy, lock::chooseHidePreviews)
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
        ConsoleToggle(checked, onCheckedChange = null, enabled = enabled)
    }
}
