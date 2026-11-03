package io.github.shiroel.aries.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

val LocalAppLock = staticCompositionLocalOf<AppLockSession?> { null }

/** Locked content is removed from drawing, input, accessibility and back handling. */
@Composable
fun AppLockGate(session: AppLockSession, onLeave: () -> Unit = {}, content: @Composable () -> Unit) {
    val saved = rememberSaveableStateHolder()
    if (!session.lock.locked) {
        saved.SaveableStateProvider("unlocked") { content() }
        return
    }
    BackHandler(onBack = onLeave)
    Surface(Modifier.fillMaxSize().testTag("app-lock-screen")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.app_lock_screen_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.app_lock_screen_body))
            AppLockNotice(session)
            TextButton(onClick = session::unlock, enabled = !session.busy, modifier = Modifier.testTag("app-lock-unlock")) {
                Text(stringResource(if (session.busy) R.string.app_lock_authenticating else R.string.app_lock_unlock_button))
            }
        }
    }
}

@Composable
internal fun AppLockNotice(session: AppLockSession) {
    val message = when (session.notice) {
        UnlockResult.CANCELLED -> R.string.app_lock_cancelled
        UnlockResult.FAILED -> R.string.app_lock_failed
        UnlockResult.UNAVAILABLE -> R.string.app_lock_unavailable
        else -> null
    }
    message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}
