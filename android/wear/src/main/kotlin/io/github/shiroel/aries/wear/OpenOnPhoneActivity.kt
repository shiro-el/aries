package io.github.shiroel.aries.wear

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.OpenWatchAgent
import io.github.shiroel.aries.weardata.WatchSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Tile LaunchAction runs in a foreground Activity; no background activity launch exemption. */
class OpenOnPhoneActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WatchStateStore(this)
        fun message(resource: Int) = store.summary().localizedContext(this).getString(resource)
        val label = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            text = message(R.string.wear_opening_phone)
        }
        setContentView(label)
        val host = intent.getStringExtra("host")
        val terminal = intent.getStringExtra("terminal")
        scope.launch {
            try {
                withTimeout(10_000) {
                    store.hydrate(this@OpenOnPhoneActivity)
                    label.text = message(R.string.wear_opening_phone)
                    val phone = requireNotNull(store.phoneNode())
                    val remoteIntent = if (!host.isNullOrBlank() && !terminal.isNullOrBlank()) {
                        val command = OpenWatchAgent(host, terminal)
                        // RPC acknowledges that the phone still knows this Host/Agent.
                        val reply = Wearable.getMessageClient(this@OpenOnPhoneActivity)
                            .sendRequest(phone, WatchSummary.OPEN_PATH, command.toBytes()).await()
                        check(OpenWatchAgent.fromBytes(reply) == command)
                        command.remoteIntent(packageName)
                    } else {
                        // The existing phone deep-link filter opens its default screen without an Agent.
                        Intent(Intent.ACTION_VIEW, Uri.parse("aries-watch://agent"))
                            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName)
                    }
                    val future = RemoteActivityHelper(this@OpenOnPhoneActivity)
                        .startRemoteActivity(remoteIntent, phone)
                    // RemoteActivityHelper returns a ListenableFuture, not a Play services Task.
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                        future.addListener({
                            try {
                                future.get()
                                continuation.resumeWith(Result.success(Unit))
                            } catch (error: Exception) { continuation.resumeWith(Result.failure(error)) }
                        }, mainExecutor)
                        continuation.invokeOnCancellation { future.cancel(true) }
                    }
                }
                finish()
            } catch (_: Exception) {
                if (!isDestroyed) label.text = message(R.string.wear_connect_phone)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
