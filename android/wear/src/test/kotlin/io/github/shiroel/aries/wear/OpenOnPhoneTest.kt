package io.github.shiroel.aries.wear

import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.weardata.OpenWatchAgent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w192dp-h192dp-round")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class OpenOnPhoneTest {
    // Catches a missing Agent being misreported as a disconnected phone and
    // the handoff failure screen offering no way to recover.
    @Test fun emptyReplyExplainsMissingAgentAndRetryCanCompleteTheHandoff() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), HandoffActivity::class.java)
            .putExtra("host", "host").putExtra("terminal", "t1")
        val controller = Robolectric.buildActivity(HandoffActivity::class.java, intent).setup()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val labels = mutableListOf<TextView>()
        fun visit(view: android.view.View) { if (view is TextView) labels += view; if (view is android.view.ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) } }
        visit(root)
        assertTrue(labels.any { it.text.toString() == activity.getString(R.string.wear_open_missing) })
        assertFalse(activity.launched)
        System.getenv("ARIES_FRESHNESS_SHOTS")?.let { dir ->
            val view = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(dir, "handoff-missing-agent.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        activity.missing = false
        labels.filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.wear_detail_try_again) }.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(activity.launched)
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}

class HandoffActivity : OpenOnPhoneActivity() {
    var missing = true
    var launched = false
    override suspend fun acknowledge(command: OpenWatchAgent): ByteArray = if (missing) byteArrayOf() else command.toBytes()
    override suspend fun launchPhone(intent: Intent) { launched = true }
}
