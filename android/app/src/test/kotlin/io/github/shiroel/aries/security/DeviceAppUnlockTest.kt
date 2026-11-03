package io.github.shiroel.aries.security

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android 9's device-credential fallback, through the real Activity Result boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceAppUnlockTest {
    @Test fun onlyAConfirmedDeviceCredentialSucceedsAndMissingDeviceLockCannotUnlock() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        val auth = DeviceAppUnlock(activity)
        controller.start().resume()
        val keyguard = shadowOf(activity.getSystemService(KeyguardManager::class.java))
        var result: UnlockResult? = null
        keyguard.setIsDeviceSecure(false)
        auth.authenticate { result = it }
        assertEquals(UnlockResult.UNAVAILABLE, result)
        keyguard.setIsDeviceSecure(true)
        auth.authenticate { result = it }
        val cancelled = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(cancelled)
        shadowOf(activity).receiveResult(cancelled.intent, Activity.RESULT_CANCELED, null)
        assertEquals(UnlockResult.CANCELLED, result)
        auth.authenticate { result = it }
        val confirmed = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(confirmed.intent, Activity.RESULT_OK, null)
        assertEquals(UnlockResult.SUCCESS, result)
        auth.dispose()
        controller.pause().stop().destroy()
    }
}
