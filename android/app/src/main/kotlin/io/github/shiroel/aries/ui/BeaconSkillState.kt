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

/** One readiness summary; only roots needing attention expose their paths. */
@Composable
fun BeaconSkillState(skills: List<BeaconSkillRoot>?) {
    // Older Beacons reported the old name. Those paths cannot establish
    // readiness or conflicts for aries-call-human.
    val currentSkills = skills?.filter { it.path.substringAfterLast('/') == "aries-call-human" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (currentSkills.isNullOrEmpty()) {
            Text(
                stringResource(R.string.beacon_skill_unknown),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        val issues = currentSkills.orEmpty().filter { it.outcome !in setOf(
            BeaconSkillOutcome.INSTALLED, BeaconSkillOutcome.UPDATED, BeaconSkillOutcome.UNCHANGED,
        ) }
        if (!currentSkills.isNullOrEmpty()) {
            Text(
                if (issues.isEmpty()) stringResource(R.string.beacon_skill_summary_ready)
                else stringResource(R.string.beacon_skill_summary_problems, issues.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        issues.forEach { skill ->
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
