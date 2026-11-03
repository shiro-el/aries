package io.github.shiroel.aries.security

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class AppLockUiTest {
    @get:Rule val compose = createComposeRule()
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("app-lock-ui-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private class FakeUnlock : AppAuthenticator {
        lateinit var pending: (UnlockResult) -> Unit
        override fun authenticate(result: (UnlockResult) -> Unit) { pending = result }
    }

    @Test fun hidingTheScreenPreservesAnUnsentDraftUntilUnlock() {
        val lock = AppLock(prefs).also { it.enableAfterAuthentication() }
        val auth = FakeUnlock()
        val session = AppLockSession(lock, auth)
        compose.setContent {
            MaterialTheme {
                AppLockGate(session) {
                    var draft by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                }
            }
        }
        compose.onNodeWithTag("draft").performTextReplacement("Do not send yet")
        compose.runOnIdle { lock.backgrounded(); lock.foregrounded() }
        compose.onNodeWithTag("draft").assertDoesNotExist()
        compose.onNodeWithTag("app-lock-unlock").performClick()
        compose.runOnIdle { auth.pending(UnlockResult.SUCCESS) }
        compose.onNodeWithTag("draft").assertTextContains("Do not send yet")
    }

    @Test fun settingsRequireAuthenticationAndPersistDelayAndPreviewChoice() {
        val lock = AppLock(prefs)
        val auth = FakeUnlock()
        val session = AppLockSession(lock, auth)
        compose.setContent { MaterialTheme { AppLockSettings(session) } }
        assertFalse(lock.enabled)
        compose.onNodeWithTag("app-lock-enabled").performClick()
        assertFalse(lock.enabled)
        compose.runOnIdle { auth.pending(UnlockResult.CANCELLED) }
        assertFalse(AppLock(prefs).enabled)
        compose.onNodeWithTag("app-lock-enabled").performClick()
        compose.runOnIdle { auth.pending(UnlockResult.SUCCESS) }
        assertTrue(AppLock(prefs).enabled)
        assertTrue(lock.hidePreviews)
        compose.onNodeWithTag("app-lock-delay-FIVE_MINUTES").performClick()
        compose.onNodeWithTag("app-lock-previews").performClick()
        val restored = AppLock(prefs)
        assertEquals(LockDelay.FIVE_MINUTES, restored.delay)
        assertFalse(restored.hidePreviews)
        assertTrue(restored.locked)
        compose.onNodeWithTag("app-lock-enabled").performClick()
        assertFalse(AppLock(prefs).enabled)
    }

    @Test fun cancellationAndFailureKeepContentHiddenAndSuccessfulRetryRevealsIt() {
        AppLock(prefs).enableAfterAuthentication()
        val lock = AppLock(prefs)
        val auth = FakeUnlock()
        val session = AppLockSession(lock, auth)
        compose.setContent {
            MaterialTheme {
                AppLockGate(session) { Box(Modifier.testTag("protected-content")) }
            }
        }
        compose.onNodeWithTag("protected-content").assertDoesNotExist()
        compose.onNodeWithTag("app-lock-unlock").performClick()
        compose.runOnIdle { auth.pending(UnlockResult.CANCELLED) }
        compose.onNodeWithTag("app-lock-screen").assertExists()
        compose.onNodeWithTag("protected-content").assertDoesNotExist()
        compose.onNodeWithTag("app-lock-unlock").performClick()
        compose.runOnIdle { auth.pending(UnlockResult.FAILED) }
        compose.onNodeWithTag("protected-content").assertDoesNotExist()
        compose.onNodeWithTag("app-lock-unlock").performClick()
        compose.runOnIdle { auth.pending(UnlockResult.SUCCESS) }
        compose.onNodeWithTag("app-lock-screen").assertDoesNotExist()
        compose.onNodeWithTag("protected-content").assertExists()
        assertFalse(lock.locked)
    }
}
