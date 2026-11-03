package io.github.shiroel.aries

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Call notification answered through the real receiver, WorkManager,
 * Rust core and SSH, with the phone locked. Run by
 * `scripts/test-call-notification.sh`, which starts the SSH Host (a docker
 * sshd with a fake Beacon that records each reply and says `delivered`
 * once, `answered` after), authorizes this app's key and passes `step`,
 * `callHostPort` and `callHostKey`. Skipped otherwise.
 *
 * Steps: `key` makes sure the app has a key; `locked` is the whole test in
 * one go (screen off, PIN keyguard up, the button's PendingIntent sent as
 * SystemUI does); `setup` / `check` surround the script killing the app
 * process and tapping the button on the lock screen itself.
 */
@RunWith(AndroidJUnit4::class)
class CallNotificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val step = args.getString("step")
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as AriesApplication
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var hostId: String? = null
    private var keep = false
    private var pinSet = false

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().readText()

    @Before
    fun setUp() {
        assumeTrue(step != null)
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    @After
    fun tearDown() {
        if (keep) return
        hostId?.let { id ->
            manager.cancel(TAG, id.hashCode())
            app.core.removeHost(id)
        }
        if (pinSet) shell("locksettings clear --old $PIN")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    /** The script's SSH Host: the emulator reaches the computer's loopback at 10.0.2.2. */
    private fun addHost(): String {
        val port = args.getString("callHostPort")!!.toUShort()
        val id = app.core.addHost(HOST_NAME, Endpoint.Ssh(args.getString("callHostAddress") ?: "10.0.2.2", port, "aries")).id
        app.core.trustHostKey(id, args.getString("callHostKey")!!)
        hostId = id
        return id
    }

    private fun post(hostId: String) = PushService.show(
        context,
        PushNotice(
            hostId, HOST_NAME,
            PushContent.Call(CALL, "t1", true, "Deploy?", "Ready to ship.", CallUrgency.URGENT, listOf("Go", "Stop"), System.currentTimeMillis().toULong()),
        ),
    ) { app.core.canAnswerInBackground(it) }

    private fun shown(hostId: String): Notification? =
        manager.activeNotifications.find { it.tag == TAG && it.id == hostId.hashCode() }?.notification

    /** Posting is asynchronous: waits for it to show with its two Choices. */
    private fun posted(hostId: String): Notification {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            shown(hostId)?.takeIf { it.actions?.size == 2 }?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("the Call notification was not posted")
    }

    private fun text(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun waitForText(hostId: String, want: String): Notification {
        val deadline = SystemClock.uptimeMillis() + 40_000
        while (SystemClock.uptimeMillis() < deadline) {
            val n = shown(hostId)
            if (text(n) == want) return n!!
            SystemClock.sleep(100)
        }
        throw AssertionError("notification text is ${text(shown(hostId))}, not $want")
    }

    @Test
    fun key() {
        assumeTrue(step == "key")
        app.core.ensureKey()
    }

    @Test
    fun locked() {
        assumeTrue(step == "locked")
        val id = addHost()
        post(id)
        val n = posted(id)
        assertEquals(listOf("Go", "Stop"), n.actions.map { it.title.toString() })

        shell("locksettings set-pin $PIN")
        pinSet = true
        shell("input keyevent KEYCODE_SLEEP")
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while ((power.isInteractive || !keyguard.isKeyguardLocked) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertFalse("screen off", power.isInteractive)
        assertTrue("keyguard up", keyguard.isKeyguardLocked)

        // What SystemUI does when the button is tapped.
        n.actions[1].actionIntent.send()

        val done = waitForText(id, context.getString(R.string.call_answer_delivered, "Stop"))
        assertTrue("no buttons left", done.actions.isNullOrEmpty())
        assertFalse("screen still off", power.isInteractive)
        assertTrue("still locked", keyguard.isKeyguardLocked)

        // The same button again (an old notification, another tap): the Host refuses it.
        post(id)
        SystemClock.sleep(500) // past the notification update rate limit
        posted(id).actions[1].actionIntent.send()
        waitForText(id, context.getString(R.string.call_reply_answered))
    }

    /** Leaves the Host and the notification for the script, which kills the process next. */
    @Test
    fun setup() {
        assumeTrue(step == "setup")
        val id = addHost()
        post(id)
        assertEquals(2, posted(id).actions.size)
        keep = true
    }

    /** After the script tapped "Stop" on the lock screen: the result on the notification. */
    @Test
    fun check() {
        assumeTrue(step == "check")
        hostId = app.core.hosts().first { it.name == HOST_NAME }.id
        val n = shown(hostId!!)
        assertEquals(context.getString(R.string.call_answer_delivered, "Stop"), text(n))
        assertTrue(n!!.actions.isNullOrEmpty())
    }

    companion object {
        private const val HOST_NAME = "call-notification-test"
        private const val CALL = "c36"
        private const val TAG = "call:$CALL"
        private const val PIN = "3636"
    }
}
