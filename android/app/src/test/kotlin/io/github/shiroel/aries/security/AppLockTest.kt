package io.github.shiroel.aries.security

import android.app.Activity
import android.view.WindowManager
import org.robolectric.Robolectric
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phone app-lock public boundary: monotonic background time and persisted policy. */
@RunWith(RobolectricTestRunner::class)
class AppLockTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("app-lock-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun previewPrivacyUsesSecureWindowAndCanBeDisabled() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lock = AppLock(prefs)
        lock.applyPrivacy(window)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        lock.enableAfterAuthentication()
        lock.applyPrivacy(window)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        lock.chooseHidePreviews(false)
        lock.applyPrivacy(window)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        lock.chooseHidePreviews(true)
        lock.disable()
        lock.applyPrivacy(window)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test fun immediatePolicyLocksOnLeavingAndColdStartsAlwaysLock() {
        val lock = AppLock(prefs)
        assertFalse(lock.enabled)
        assertFalse(lock.locked)
        assertEquals(LockDelay.IMMEDIATE, lock.delay)
        lock.enableAfterAuthentication()
        lock.foregrounded()
        assertFalse(lock.locked)
        lock.backgrounded()
        lock.foregrounded()
        assertTrue(lock.locked)
        lock.authenticationSucceeded()
        lock.chooseDelay(LockDelay.FIVE_MINUTES)
        assertTrue(AppLock(prefs).locked)
        lock.disable()
        assertFalse(AppLock(prefs).locked)
    }

    @Test fun locksAtTheSelectedBackgroundDeadlineAndNotBefore() {
        for ((delay, before, deadline) in listOf(
            Triple(LockDelay.ONE_MINUTE, 59_999L, 60_000L),
            Triple(LockDelay.FIVE_MINUTES, 299_999L, 300_000L),
        )) {
            var now = 1_000L
            val lock = AppLock(prefs) { now }
            lock.enableAfterAuthentication()
            lock.chooseDelay(delay)
            lock.backgrounded()
            now = 1_000L + before
            lock.foregrounded()
            assertFalse(lock.locked)
            lock.backgrounded()
            now += deadline
            lock.foregrounded()
            assertTrue(lock.locked)
            lock.authenticationSucceeded()
            assertFalse(lock.locked)
        }
    }
}
