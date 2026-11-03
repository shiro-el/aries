package io.github.shiroel.aries

import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
class PushDiagnosticsTest {
    @Test
    fun malformedFcmMessagesReachTheDiagnosticSeamWithoutLoggingContents() {
        val app = ApplicationProvider.getApplicationContext<FakeCoreApp>()
        app.core.rejectPushReason = "secret-token-from-error"
        val controller = Robolectric.buildService(PushService::class.java).create()
        val service = controller.get()
        for (data in listOf(mapOf("v" to "2"), mapOf("v" to "1"), mapOf("v" to "1", "e" to "%%%"), mapOf("v" to "1", "e" to "c2VjcmV0"))) {
            service.onMessageReceived(RemoteMessage.Builder("test").setData(data).build())
        }
        assertEquals(4, app.core.receivedPushEnvelopes.size)
        assertFalse(ShadowLog.getLogsForTag("AriesPush").any { it.msg.contains("secret-token-from-error") })
        controller.destroy()
    }
}
