package io.github.shiroel.aries.wear

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.OpenWatchAgent
import io.github.shiroel.aries.weardata.WatchSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/** Tile handoffs run in a foreground Activity and always offer recovery on failure. */
open class OpenOnPhoneActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { WatchStateStore(this) }
    private fun message(resource: Int) = store.summary().localizedContext(this).getString(resource)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val label = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val retry = Button(this).apply { text = message(R.string.wear_detail_try_again); visibility = View.GONE }
        val back = Button(this).apply { text = message(R.string.wear_open_close); setOnClickListener { finish() } }
        setContentView(android.widget.ScrollView(this).apply {
            addView(LinearLayout(this@OpenOnPhoneActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(18, 12, 18, 12)
                addView(label); addView(retry); addView(back)
            })
        })
        val host = intent.getStringExtra("host")
        val terminal = intent.getStringExtra("terminal")
        fun open() {
            retry.visibility = View.GONE
            label.text = message(R.string.wear_opening_phone)
            scope.launch {
                var failure = R.string.wear_connect_phone
                try {
                    withTimeout(15_000) {
                        val remoteIntent = if (!host.isNullOrBlank() && !terminal.isNullOrBlank()) {
                            val command = OpenWatchAgent(host, terminal, intent.getBooleanExtra("chat", false))
                            val reply = acknowledge(command)
                            if (reply.isEmpty()) {
                                label.text = message(R.string.wear_open_missing)
                                retry.visibility = View.VISIBLE
                                return@withTimeout
                            }
                            failure = R.string.wear_open_failed
                            check(OpenWatchAgent.fromBytes(reply) == command)
                            command.remoteIntent(packageName)
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse("aries-watch://agent"))
                                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName)
                        }
                        failure = R.string.wear_open_failed
                        launchPhone(remoteIntent)
                        finish()
                    }
                } catch (_: TimeoutCancellationException) {
                    label.text = message(failure)
                    retry.visibility = View.VISIBLE
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    label.text = message(failure)
                    retry.visibility = View.VISIBLE
                }
            }
        }
        retry.setOnClickListener { open() }
        open()
    }

    protected open suspend fun acknowledge(command: OpenWatchAgent): ByteArray {
        store.hydrate(this)
        val phone = requireNotNull(store.phoneNode())
        return Wearable.getMessageClient(this).sendRequest(phone, WatchSummary.OPEN_PATH, command.toBytes()).await()
    }

    protected open suspend fun launchPhone(intent: Intent) {
        store.hydrate(this)
        val phone = requireNotNull(store.phoneNode())
        val future = RemoteActivityHelper(this).startRemoteActivity(intent, phone)
        suspendCancellableCoroutine<Unit> { continuation ->
            future.addListener({
                try { future.get(); continuation.resumeWith(Result.success(Unit)) }
                catch (error: Exception) { continuation.resumeWith(Result.failure(error)) }
            }, mainExecutor)
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
