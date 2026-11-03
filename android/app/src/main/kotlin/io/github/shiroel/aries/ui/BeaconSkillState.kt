package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.BeaconSkillOutcome
import io.github.shiroel.aries.core.BeaconSkillRoot

/** Read-only per-path diagnostics, reusable in the Host's Settings section. */
@Composable
fun BeaconSkillState(skills: List<BeaconSkillRoot>?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.beacon_skill_title),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
        if (skills == null) {
            Text(
                stringResource(R.string.beacon_skill_unknown),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        skills.orEmpty().forEach { skill ->
            val message = when (skill.outcome) {
                BeaconSkillOutcome.KEPT_FOREIGN -> R.string.beacon_skill_foreign
                BeaconSkillOutcome.KEPT_MODIFIED -> R.string.beacon_skill_modified
                BeaconSkillOutcome.KEPT_SYMLINK -> R.string.beacon_skill_symlink
                BeaconSkillOutcome.ABSENT, BeaconSkillOutcome.REMOVED -> R.string.beacon_skill_absent
                BeaconSkillOutcome.INSTALLED, BeaconSkillOutcome.UPDATED, BeaconSkillOutcome.UNCHANGED -> R.string.beacon_skill_ready
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(skill.path, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text(
                    stringResource(message),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (message == R.string.beacon_skill_ready) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
